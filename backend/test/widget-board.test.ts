import { randomUUID } from 'node:crypto';
import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  API,
  bearer,
  createTestApp,
  FakePushService,
  pair,
  register,
  type Session,
  type TestApp,
  uploadPhoto,
} from './helpers/app.js';

let t: TestApp;
let push: FakePushService;
let a: Session;
let b: Session;
let c: Session;
let d: Session;

beforeAll(async () => {
  push = new FakePushService();
  t = await createTestApp({ push, envOverrides: { RATE_LIMIT_WIDGET_WRITES_PER_MIN: '30' } });
  a = await register(t.app, 'Ari');
  b = await register(t.app, 'Bea');
  c = await register(t.app, 'Cyd');
  d = await register(t.app, 'Dax');
  await pair(t.app, a, b);
  await pair(t.app, c, d);
  for (const [s, token] of [
    [a, 'tok-a'],
    [b, 'tok-b'],
  ] as const) {
    const res = await t.app.inject({
      method: 'PUT',
      url: `${API}/devices`,
      headers: bearer(s),
      payload: { deviceId: `dev-${token}`, platform: 'android', token, appVersion: '1.0.0', model: 'Pixel' },
    });
    expect(res.statusCode).toBe(204);
  }
  await t.outbox.runOnce();
  push.calls.length = 0;
});
afterAll(async () => {
  await t.close();
});

const put = (s: Session, payload: Record<string, unknown>) =>
  t.app.inject({ method: 'PUT', url: `${API}/widget-board`, headers: bearer(s), payload });
const get = (s: Session) => t.app.inject({ method: 'GET', url: `${API}/widget-board`, headers: bearer(s) });

describe('widget board', () => {
  it('starts empty for both', async () => {
    const res = await get(a);
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ mine: null, forMe: null });
  });

  it('PUT sets mine; the partner sees it as forMe; widget.updated is pushed to the partner', async () => {
    const res = await put(a, { note: '  missing you  ', emoji: '❤️', mood: 'sleepy' });
    expect(res.statusCode).toBe(200);
    const board = res.json().board;
    expect(board).toEqual({
      authorId: a.user.id,
      note: 'missing you',
      emoji: '❤️',
      mood: 'sleepy',
      photoMediaId: null,
      updatedAt: expect.any(String),
    });

    expect((await get(a)).json()).toEqual({ mine: board, forMe: null });
    expect((await get(b)).json()).toEqual({ mine: null, forMe: board });

    const ev = await t.db.events.findOne({ type: 'widget.updated' }, { sort: { seq: -1 } });
    expect(ev!.actorId.toHexString()).toBe(a.user.id);
    expect(ev!.payload).toEqual({ board });

    await t.outbox.runOnce();
    const call = push.calls.find((x) => x.data.type === 'widget.updated');
    expect(call).toBeDefined();
    expect(call!.tokens).toEqual(['tok-b']); // only the OTHER member
    expect(call!.data).toEqual({
      type: 'widget.updated',
      eventId: ev!._id.toHexString(),
      coupleId: ev!.coupleId.toHexString(),
      seq: String(ev!.seq),
      pr: 'n', // silent widget refresh: normal priority (services/push/policy.ts)
    });
    expect((await t.db.events.findOne({ _id: ev!._id }))!.push.state).toBe('sent');
  });

  it('partial PUT keeps other fields; null and "" clear', async () => {
    const r1 = await put(a, { mood: null });
    expect(r1.json().board).toMatchObject({ note: 'missing you', emoji: '❤️', mood: null });
    const r2 = await put(a, { note: '' });
    expect(r2.json().board).toMatchObject({ note: null, emoji: '❤️', mood: null });
    const r3 = await put(a, { emoji: null });
    expect(r3.json().board).toMatchObject({ note: null, emoji: null, mood: null, photoMediaId: null });
    expect(await t.db.widgetBoards.countDocuments({ authorId: new ObjectId(a.user.id) })).toBe(1);
  });

  it('validates the body', async () => {
    for (const payload of [
      {},
      { note: 'x'.repeat(141) },
      { mood: 'x'.repeat(41) },
      { emoji: '😀😀😀' },
      { emoji: '' },
      { photoMediaId: 'nope' },
      { coupleId: new ObjectId().toHexString() },
    ]) {
      const res = await put(a, payload);
      expect(res.statusCode, JSON.stringify(payload)).toBe(400);
      expect(res.json().error.code).toBe('validation_failed');
    }
  });

  it('photo: own-couple ready photo/wallpaper works and the partner can fetch it', async () => {
    const photo = await uploadPhoto(t, a);
    const res = await put(a, { photoMediaId: photo });
    expect(res.statusCode).toBe(200);
    expect(res.json().board.photoMediaId).toBe(photo);
    const forMe = (await get(b)).json().forMe;
    expect(forMe.photoMediaId).toBe(photo);
    const url = await t.app.inject({
      method: 'GET',
      url: `${API}/media/${forMe.photoMediaId}/url`,
      headers: bearer(b),
    });
    expect(url.statusCode).toBe(200);
    expect((await t.app.inject({ method: 'GET', url: url.json().url })).statusCode).toBe(200);

    const wallpaper = await uploadPhoto(t, b, undefined, 'wallpaper');
    expect((await put(a, { photoMediaId: wallpaper })).statusCode).toBe(200);
    expect((await put(a, { photoMediaId: null })).json().board.photoMediaId).toBeNull();
  });

  it('photo: other couple → 403, unknown / not ready / wrong category / view-once → 400', async () => {
    const foreign = await uploadPhoto(t, c);
    const r1 = await put(a, { photoMediaId: foreign });
    expect(r1.statusCode).toBe(403);
    expect(r1.json().error.code).toBe('forbidden');

    expect((await put(a, { photoMediaId: new ObjectId().toHexString() })).statusCode).toBe(400);

    const pending = await t.app.inject({
      method: 'POST',
      url: `${API}/media/uploads`,
      headers: bearer(a),
      payload: { category: 'photo', mimeType: 'image/png', sizeBytes: 10 },
    });
    const r2 = await put(a, { photoMediaId: pending.json().media.id });
    expect(r2.statusCode).toBe(400);
    expect(r2.json().error.code).toBe('validation_failed');

    const avatar = await uploadPhoto(t, a, undefined, 'avatar');
    expect((await put(a, { photoMediaId: avatar })).statusCode).toBe(400);

    const vo = await uploadPhoto(t, a);
    const sent = await t.app.inject({
      method: 'POST',
      url: `${API}/messages`,
      headers: bearer(a),
      payload: { type: 'photo', mediaId: vo, viewOnce: true, idempotencyKey: randomUUID() },
    });
    expect(sent.statusCode).toBe(201);
    const r3 = await put(a, { photoMediaId: vo });
    expect(r3.statusCode).toBe(400);
    expect(r3.json().error.message).toMatch(/view-once/);
  });

  it('cross-couple isolation: C never sees the A/B boards', async () => {
    await put(b, { note: 'for Ari only' });
    const res = await get(c);
    expect(res.json()).toEqual({ mine: null, forMe: null });
    await put(c, { note: 'for Dax' });
    expect((await get(d)).json().forMe.note).toBe('for Dax');
    expect((await get(a)).json().forMe.note).toBe('for Ari only');
    expect(JSON.stringify((await get(a)).json())).not.toContain('for Dax');
  });

  it('export includes the boards of the couple', async () => {
    const exp = await t.app.inject({ method: 'GET', url: `${API}/me/export`, headers: bearer(a) });
    expect(exp.statusCode).toBe(200);
    const authors = exp
      .json()
      .widgetBoards.map((x: { authorId: string }) => x.authorId)
      .sort();
    expect(authors).toEqual([a.user.id, b.user.id].sort());
  });

  it('requires an active couple', async () => {
    const solo = await register(t.app, 'Solo');
    const res = await get(solo);
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('not_paired');
  });

  it('unlink removes the couple boards', async () => {
    const coupleId = (await t.db.users.findOne({ _id: new ObjectId(c.user.id) }))!.activeCoupleId!;
    expect(await t.db.widgetBoards.countDocuments({ coupleId })).toBe(1);
    const un = await t.app.inject({
      method: 'POST',
      url: `${API}/couple/unlink`,
      headers: bearer(d),
      payload: { confirm: true },
    });
    expect(un.statusCode).toBeLessThan(300);
    expect(await t.db.widgetBoards.countDocuments({ coupleId })).toBe(0);
  });

  it('account deletion removes the user boards', async () => {
    const del = await t.app.inject({
      method: 'DELETE',
      url: `${API}/me`,
      headers: bearer(b),
      payload: { password: 'correct-horse-battery' },
    });
    expect(del.statusCode).toBe(204);
    expect(await t.db.widgetBoards.countDocuments({ authorId: new ObjectId(b.user.id) })).toBe(0);
    expect(await t.db.widgetBoards.countDocuments({ authorId: new ObjectId(a.user.id) })).toBe(0); // couple ended
  });
});

describe('widget board rate limit', () => {
  it('is per user: 429 rate_limited with Retry-After past the budget', async () => {
    const t2 = await createTestApp({ envOverrides: { RATE_LIMIT_WIDGET_WRITES_PER_MIN: '2' } });
    try {
      const x = await register(t2.app, 'Xo');
      const y = await register(t2.app, 'Yo');
      await pair(t2.app, x, y);
      const p = (s: Session) =>
        t2.app.inject({
          method: 'PUT',
          url: `${API}/widget-board`,
          headers: bearer(s),
          payload: { mood: 'ok' },
        });
      expect((await p(x)).statusCode).toBe(200);
      expect((await p(x)).statusCode).toBe(200);
      const third = await p(x);
      expect(third.statusCode).toBe(429);
      expect(third.json().error.code).toBe('rate_limited');
      expect(third.headers['retry-after']).toBeDefined();
      expect((await p(y)).statusCode).toBe(200); // separate budget
    } finally {
      await t2.close();
    }
  });
});
