import { randomUUID } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import { ObjectId } from 'mongodb';
import { pino } from 'pino';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import { CouplePurger } from '../src/modules/couples/purge.js';
import { CleanupWorker } from '../src/workers/cleanup.js';
import {
  API,
  bearer,
  createTestApp,
  FakePushService,
  pair,
  register,
  type Session,
  sendPing,
  type TestApp,
  uploadPhoto,
} from './helpers/app.js';

let t: TestApp;
let push: FakePushService;
let wsUrl: string;

beforeAll(async () => {
  push = new FakePushService();
  t = await createTestApp({
    push,
    envOverrides: { PING_COOLDOWN_SECONDS: '0', RATE_LIMIT_GAME_WRITES_PER_MIN: '1000' },
  });
  await t.app.listen({ host: '127.0.0.1', port: 0 });
  wsUrl = `ws://127.0.0.1:${(t.app.server.address() as AddressInfo).port}${API}/ws`;
});
afterAll(async () => {
  await t.close();
});

const silent = pino({ level: 'silent' });
const cleanupWorker = () =>
  new CleanupWorker(t.db, t.storage, t.clock.now, silent, { intervalMs: 60_000, abandonedUploadHours: 24 });

const req = (
  s: Session,
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  url: string,
  payload?: object,
) => t.app.inject({ method, url: `${API}${url}`, headers: bearer(s), ...(payload ? { payload } : {}) });

/** Collections that must hold nothing of an unlinked couple (events: only couple.unlinked). */
const SCOPED = [
  'messages',
  'pings',
  'memories',
  'milestones',
  'lists',
  'games',
  'widgetBoards',
  'sos',
  'invitations',
  'media',
] as const;

async function counts(coupleId: ObjectId): Promise<Record<string, number>> {
  const out: Record<string, number> = {};
  for (const name of [...SCOPED, 'events'] as const) {
    out[name] = await t.db[name].countDocuments({ coupleId });
  }
  return out;
}

/** Creates at least one document of every couple-scoped kind, plus an avatar and a wallpaper. */
async function seed(x: Session, y: Session): Promise<{ coupleId: ObjectId; keys: string[] }> {
  const coupleId = new ObjectId(await pair(t.app, x, y));
  expect((await sendPing(t.app, x)).statusCode).toBe(201);
  const text = await req(x, 'POST', '/messages', {
    type: 'text',
    text: 'hello',
    idempotencyKey: randomUUID(),
  });
  expect(text.statusCode).toBe(201);
  const chatPhoto = await uploadPhoto(t, y);
  expect(
    (await req(y, 'POST', '/messages', { type: 'photo', mediaId: chatPhoto, idempotencyKey: randomUUID() }))
      .statusCode,
  ).toBe(201);
  const memoryPhoto = await uploadPhoto(t, x);
  expect((await req(x, 'POST', '/memories', { mediaId: memoryPhoto, caption: 'us' })).statusCode).toBe(201);
  expect(
    (await req(x, 'POST', '/milestones', { title: 'First call', date: '2025-01-02', kind: 'custom' }))
      .statusCode,
  ).toBe(201);
  expect((await req(y, 'POST', '/lists', { title: 'Trips', kind: 'note', body: 'Goa' })).statusCode).toBe(
    201,
  );
  expect(
    (await req(x, 'PUT', '/widget-board', { mood: 'sleepy', photoMediaId: memoryPhoto })).statusCode,
  ).toBe(200);
  expect((await req(y, 'POST', '/sos', { message: 'call me' })).statusCode).toBe(201);
  expect((await req(x, 'POST', '/games', { kind: 'rps' })).statusCode).toBe(201);
  const avatar = await uploadPhoto(t, x, undefined, 'avatar');
  expect((await req(x, 'PATCH', '/me', { avatarMediaId: avatar })).statusCode).toBe(200);
  const wallpaper = await uploadPhoto(t, y, undefined, 'wallpaper');
  const patched = await req(y, 'PATCH', '/couple', {
    relationshipStartDate: '2024-02-14',
    nextMeetingDate: '2026-12-24T18:00:00.000Z',
    nextMeetingLabel: 'Christmas',
    sharedPreferences: { theme: 'dusk' },
    chatWallpaper: { style: 'PHOTO', photoMediaId: wallpaper },
  });
  expect(patched.statusCode).toBe(200);
  const media = await t.db.media.find({ coupleId }).toArray();
  expect(media).toHaveLength(4);
  for (const m of media) expect(await t.storage.head(m.storageKey)).not.toBeNull();
  const c = await counts(coupleId);
  for (const [name, n] of Object.entries(c)) expect(n, name).toBeGreaterThan(0);
  return { coupleId, keys: media.map((m) => m.storageKey) };
}

async function expectPurged(coupleId: ObjectId, keys: string[], members: Session[]): Promise<void> {
  await t.ctx.background.drain();
  const c = await counts(coupleId);
  for (const name of SCOPED) expect(c[name], name).toBe(0);
  const events = await t.db.events.find({ coupleId }).toArray();
  expect(events.map((e) => e.type)).toEqual(['couple.unlinked']);
  for (const key of keys) expect(await t.storage.head(key), key).toBeNull();
  for (const s of members) {
    const u = await t.db.users.findOne({ _id: new ObjectId(s.user.id) });
    expect(u!.avatarMediaId).toBeNull();
    expect(u!.activeCoupleId).toBeNull();
  }
  const couple = await t.db.couples.findOne({ _id: coupleId });
  expect(couple).toMatchObject({
    status: 'ended',
    endedAt: expect.any(Date),
    relationshipStartDate: null,
    nextMeetingDate: null,
    nextMeetingLabel: null,
    sharedPreferences: {},
    purgedAt: expect.any(Date),
  });
  expect(couple).not.toHaveProperty('chatWallpaper');
  expect(couple!.memberIds.map((m) => m.toHexString()).sort()).toEqual(members.map((s) => s.user.id).sort());
}

type Msg = Record<string, unknown> & { type: string };
async function openSocket(token: string) {
  const ws = new WebSocket(wsUrl);
  const queue: Msg[] = [];
  const waiters: ((m: Msg) => void)[] = [];
  ws.on('message', (raw) => {
    const m = JSON.parse(raw.toString()) as Msg;
    const w = waiters.shift();
    if (w) w(m);
    else queue.push(m);
  });
  const closed = new Promise<number>((resolve) => ws.on('close', (code) => resolve(code)));
  const next = () =>
    new Promise<Msg>((resolve, reject) => {
      const q = queue.shift();
      if (q) return resolve(q);
      const timer = setTimeout(() => reject(new Error('timed out waiting for ws message')), 5000);
      waiters.push((m) => {
        clearTimeout(timer);
        resolve(m);
      });
    });
  await new Promise<void>((resolve, reject) => {
    ws.once('open', () => resolve());
    ws.once('error', reject);
  });
  ws.send(JSON.stringify({ type: 'auth', token }));
  return { ws, next, closed };
}

describe('purge on unlink', () => {
  let a: Session;
  let b: Session;
  let c: Session;
  let d: Session;
  let ab: { coupleId: ObjectId; keys: string[] };
  let cd: { coupleId: ObjectId; keys: string[] };
  let cdBefore: Record<string, number>;
  let unlinkedSeq: number;

  beforeAll(async () => {
    [a, b, c, d] = await Promise.all([
      register(t.app, 'Ana'),
      register(t.app, 'Ben'),
      register(t.app, 'Cal'),
      register(t.app, 'Dee'),
    ]);
    ab = await seed(a, b);
    cd = await seed(c, d);
    cdBefore = await counts(cd.coupleId);
    for (const [s, token] of [
      [a, 'tok-a'],
      [b, 'tok-b'],
    ] as const) {
      expect(
        (
          await req(s, 'PUT', '/devices', {
            deviceId: `dev-${token}`,
            platform: 'android',
            token,
            appVersion: '1.0.0',
          })
        ).statusCode,
      ).toBe(204);
    }
    await t.outbox.runOnce();
  });

  it('unlink deletes every couple-scoped document and object except the final couple.unlinked event', async () => {
    const sock = await openSocket(b.tokens.accessToken);
    expect((await sock.next()).type).toBe('ready');
    push.calls.length = 0;

    const res = await req(a, 'POST', '/couple/unlink', { confirm: true });
    expect(res.statusCode).toBe(204);

    // the partner's live socket gets couple.unlinked, then is revoked
    const ev = await sock.next();
    expect(ev.type).toBe('event');
    const event = ev.event as { type: string; seq: number; payload: unknown };
    expect(event.type).toBe('couple.unlinked');
    expect(event.payload).toEqual({
      coupleId: ab.coupleId.toHexString(),
      endedAt: expect.any(String),
      by: a.user.id,
    });
    unlinkedSeq = event.seq;
    expect(await sock.next()).toEqual({ type: 'revoked' });
    expect(await sock.closed).toBe(4403);

    await expectPurged(ab.coupleId, ab.keys, [a, b]);
    const meB = (await req(b, 'GET', '/me')).json();
    expect(meB.couple).toBeNull();
    expect(meB.user.avatarMediaId).toBeNull();
  });

  it('other couples are untouched', async () => {
    expect(await counts(cd.coupleId)).toEqual(cdBefore);
    for (const key of cd.keys) expect(await t.storage.head(key)).not.toBeNull();
    const cUser = await t.db.users.findOne({ _id: new ObjectId(c.user.id) });
    expect(cUser!.avatarMediaId).not.toBeNull();
    const couple = (await req(d, 'GET', '/couple')).json().couple;
    expect(couple).toMatchObject({ status: 'active', relationshipStartDate: '2024-02-14' });
    expect(couple.sharedPreferences.chatWallpaper).toMatchObject({ style: 'PHOTO' });
  });

  it('the outbox still pushes couple.unlinked to the partner', async () => {
    await t.outbox.runOnce();
    const call = push.calls.find((x) => x.data.type === 'couple.unlinked');
    expect(call).toBeDefined();
    expect(call!.tokens).toEqual(['tok-b']);
    expect(call!.data).toMatchObject({ coupleId: ab.coupleId.toHexString(), seq: String(unlinkedSeq) });
    const stored = await t.db.events.findOne({ coupleId: ab.coupleId, type: 'couple.unlinked' });
    expect(stored!.push.state).toBe('sent');
  });

  it('the partner can still GET /events (only the unlinked event) and replay it over a new socket', async () => {
    for (const s of [b, a]) {
      const all = await req(s, 'GET', '/events?since=0');
      expect(all.statusCode).toBe(200);
      expect(all.json().items.map((e: { type: string; seq: number }) => [e.type, e.seq])).toEqual([
        ['couple.unlinked', unlinkedSeq],
      ]);
      expect(all.json().latestSeq).toBe(unlinkedSeq);
      const after = await req(s, 'GET', `/events?since=${unlinkedSeq}`);
      expect(after.json().items).toEqual([]);
    }

    const sock = await openSocket(b.tokens.accessToken);
    expect(await sock.next()).toEqual({ type: 'ready', coupleId: null, latestSeq: 0 });
    sock.ws.send(JSON.stringify({ type: 'sync', since: unlinkedSeq - 1 }));
    const replayed = await sock.next();
    expect(replayed.type).toBe('event');
    expect((replayed.event as { type: string }).type).toBe('couple.unlinked');
    expect(await sock.next()).toEqual({ type: 'synced' });
    sock.ws.send(JSON.stringify({ type: 'sync', since: unlinkedSeq }));
    expect(await sock.next()).toEqual({ type: 'synced' });
    sock.ws.close();

    // a user who never had a couple still gets 409 not_paired
    const solo = await register(t.app, 'Solo');
    const res = await req(solo, 'GET', '/events');
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('not_paired');
  });

  it('the purge is idempotent: re-running deletes nothing more and keeps the final event', async () => {
    const purger = new CouplePurger(t.db, t.storage, silent, t.clock.now);
    const members = [new ObjectId(a.user.id), new ObjectId(b.user.id)];
    expect(await purger.purgeData(ab.coupleId, members)).toBe(0);
    expect(await purger.purgeMedia(ab.coupleId)).toEqual({
      objectsDeleted: 0,
      objectDeleteFailures: 0,
      mediaDocsDeleted: 0,
    });
    await expectPurged(ab.coupleId, ab.keys, [a, b]);
    // a second unlink attempt is a no-op (409 not_paired, nothing emitted)
    expect((await req(a, 'POST', '/couple/unlink', { confirm: true })).statusCode).toBe(409);
    expect(await t.db.events.countDocuments({ coupleId: ab.coupleId })).toBe(1);
  });

  it('the cleanup worker sweeps writes that raced the unlink', async () => {
    await t.db.messages.insertOne({
      _id: new ObjectId(),
      coupleId: ab.coupleId,
      senderId: new ObjectId(a.user.id),
      recipientId: new ObjectId(b.user.id),
      type: 'text',
      text: 'late',
      mediaId: null,
      createdAt: t.clock.now(),
      editedAt: null,
      deletedAt: null,
      readAt: null,
      idempotencyKey: randomUUID(),
    });
    const report = await cleanupWorker().runOnce();
    expect(report.unlinkedCoupleDocsPurged).toBe(1);
    expect(report.unlinkedEventsPurged).toBe(0); // still within the 7 days
    await expectPurged(ab.coupleId, ab.keys, [a, b]);
  });

  it('re-pairing afterwards works with a fresh invitation; the new couple starts clean', async () => {
    const newId = new ObjectId(await pair(t.app, b, a));
    expect(newId.equals(ab.coupleId)).toBe(false);
    const couple = (await req(a, 'GET', '/couple')).json().couple;
    expect(couple).toMatchObject({ id: newId.toHexString(), status: 'active', relationshipStartDate: null });
    expect(couple.sharedPreferences).toEqual({ chatWallpaper: null });
    expect((await req(a, 'GET', '/messages')).json().items).toEqual([]);
    expect((await req(a, 'GET', '/games')).json().items).toEqual([]);
    expect((await sendPing(t.app, a)).statusCode).toBe(201);
    // the feed is the new couple's again
    const events = (await req(b, 'GET', '/events?since=0')).json().items as { coupleId: string }[];
    expect(events.length).toBeGreaterThan(0);
    expect(events.every((e) => e.coupleId === newId.toHexString())).toBe(true);
    // the old couple's final event is still retained until the cleanup window passes
    expect(await t.db.events.countDocuments({ coupleId: ab.coupleId, type: 'couple.unlinked' })).toBe(1);

    // the cleanup re-run of the old couple's purge leaves the new couple's data and avatar alone
    const avatar = await uploadPhoto(t, a, undefined, 'avatar');
    expect((await req(a, 'PATCH', '/me', { avatarMediaId: avatar })).statusCode).toBe(200);
    const before = await counts(newId);
    await cleanupWorker().runOnce();
    expect(await counts(newId)).toEqual(before);
    expect((await req(a, 'GET', '/me')).json().user.avatarMediaId).toBe(avatar);
    expect(await t.db.events.countDocuments({ coupleId: ab.coupleId, type: 'couple.unlinked' })).toBe(1);
  });
});

describe('purge when storage deletes fail', () => {
  it('leaves the media documents for the cleanup worker, which deletes objects and documents', async () => {
    const [e, f] = await Promise.all([register(t.app, 'Eve'), register(t.app, 'Fin')]);
    const coupleId = new ObjectId(await pair(t.app, e, f));
    await uploadPhoto(t, e);
    await uploadPhoto(t, f);
    const media = await t.db.media.find({ coupleId }).toArray();
    expect(media).toHaveLength(2);

    const original = t.storage.delete.bind(t.storage);
    t.storage.delete = async () => {
      throw new Error('simulated storage outage');
    };
    try {
      expect((await req(e, 'POST', '/couple/unlink', { confirm: true })).statusCode).toBe(204);
      await t.ctx.background.drain();
    } finally {
      t.storage.delete = original;
    }
    const left = await t.db.media.find({ coupleId }).toArray();
    expect(left).toHaveLength(2);
    for (const m of left) {
      expect(m).toMatchObject({ status: 'deleted', objectDeleted: false });
      expect(await t.storage.head(m.storageKey)).not.toBeNull();
    }

    const report = await cleanupWorker().runOnce();
    expect(report.objectsDeleted).toBe(2);
    expect(await t.db.media.countDocuments({ coupleId })).toBe(0);
    for (const m of media) expect(await t.storage.head(m.storageKey)).toBeNull();
  });
});

describe('purge on account deletion', () => {
  it('deleting an account purges the couple too; the partner still receives couple.unlinked', async () => {
    const [g, h] = await Promise.all([register(t.app, 'Gil', 'leaving-password-1'), register(t.app, 'Hal')]);
    const gh = await seed(g, h);
    // the partner's avatar is under the couple prefix too
    const hAvatar = await uploadPhoto(t, h, undefined, 'avatar');
    expect((await req(h, 'PATCH', '/me', { avatarMediaId: hAvatar })).statusCode).toBe(200);
    const keys = (await t.db.media.find({ coupleId: gh.coupleId }).toArray()).map((m) => m.storageKey);

    const del = await req(g, 'DELETE', '/me', { password: 'leaving-password-1' });
    expect(del.statusCode).toBe(204);
    await expectPurged(gh.coupleId, keys, [g, h]);
    const unlinked = await t.db.events.findOne({ coupleId: gh.coupleId });
    expect(unlinked!.payload).toEqual({
      coupleId: gh.coupleId.toHexString(),
      endedAt: expect.any(String),
      by: g.user.id,
    });

    const feed = await req(h, 'GET', '/events?since=0');
    expect(feed.statusCode).toBe(200);
    expect(feed.json().items.map((e: { type: string }) => e.type)).toEqual(['couple.unlinked']);
  });
});

// Runs last: it moves the shared clock forward 7 days.
describe('retention of the final couple.unlinked event', () => {
  it('the cleanup worker deletes it after 7 days; the ex-partner then gets 409 not_paired', async () => {
    const [x, y] = await Promise.all([register(t.app, 'Xan'), register(t.app, 'Yul')]);
    const coupleId = new ObjectId(await pair(t.app, x, y));
    await sendPing(t.app, x);
    expect((await req(x, 'POST', '/couple/unlink', { confirm: true })).statusCode).toBe(204);
    await t.ctx.background.drain();

    const worker = cleanupWorker();
    t.clock.advance(7 * 24 * 3600 * 1000 - 60_000); // just inside the window
    expect((await worker.runOnce()).unlinkedEventsPurged).toBe(0);
    expect(await t.db.events.countDocuments({ coupleId })).toBe(1);

    t.clock.advance(2 * 60_000); // now past 7 days
    const report = await worker.runOnce();
    expect(report.unlinkedEventsPurged).toBeGreaterThanOrEqual(1);
    expect(await t.db.events.countDocuments({ coupleId })).toBe(0);
    expect(await t.db.events.countDocuments({ type: 'couple.unlinked' })).toBe(0); // every older one too
    expect(await t.db.couples.findOne({ _id: coupleId })).toMatchObject({ status: 'ended' });

    // access tokens expired with the jump: sign in again
    const email = (await t.db.users.findOne({ _id: new ObjectId(y.user.id) }))!.email;
    const login = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/login`,
      payload: { email, password: 'correct-horse-battery' },
    });
    expect(login.statusCode).toBe(200);
    const res = await t.app.inject({
      method: 'GET',
      url: `${API}/events?since=0`,
      headers: bearer(login.json().tokens.accessToken),
    });
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('not_paired');
  });
});
