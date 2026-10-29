import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  API,
  bearer,
  createTestApp,
  pair,
  register,
  type Session,
  sendPing,
  type TestApp,
  uploadPhoto,
} from './helpers/app.js';

let t: TestApp;
let a: Session;
let b: Session;
let c: Session;
let coupleAB: string;
let pingId: string;
let messageId: string;
let memoryId: string;
let mediaId: string;
let milestoneId: string;

beforeAll(async () => {
  t = await createTestApp();
  a = await register(t.app, 'A');
  b = await register(t.app, 'B');
  c = await register(t.app, 'C');
  const d = await register(t.app, 'D');
  coupleAB = await pair(t.app, a, b);
  await pair(t.app, c, d);

  pingId = (await sendPing(t.app, a)).json().ping.id;
  messageId = (
    await t.app.inject({
      method: 'POST',
      url: `${API}/messages`,
      headers: bearer(a),
      payload: { type: 'text', text: 'secret', idempotencyKey: randomUUID() },
    })
  ).json().message.id;
  mediaId = await uploadPhoto(t, a);
  memoryId = (
    await t.app.inject({
      method: 'POST',
      url: `${API}/memories`,
      headers: bearer(a),
      payload: { mediaId, caption: 'us' },
    })
  ).json().memory.id;
  milestoneId = (
    await t.app.inject({
      method: 'POST',
      url: `${API}/milestones`,
      headers: bearer(a),
      payload: { title: 'First date', date: '2024-02-14', kind: 'anniversary' },
    })
  ).json().milestone.id;
});
afterAll(async () => {
  await t.close();
});

describe('cross-couple isolation (user C is paired with D, not with A/B)', () => {
  it('lists are scoped to the caller couple', async () => {
    for (const path of ['/pings', '/messages', '/memories', '/milestones']) {
      const res = await t.app.inject({ method: 'GET', url: `${API}${path}`, headers: bearer(c) });
      expect(res.statusCode, path).toBe(200);
      expect(res.json().items, path).toEqual([]);
    }
    // C sees only the events of couple CD (its own pairing event), never AB's
    const theirs = await t.app.inject({ method: 'GET', url: `${API}/events`, headers: bearer(c) });
    expect(theirs.statusCode).toBe(200);
    expect(theirs.json().items.length).toBeGreaterThan(0);
    expect(theirs.json().items.every((e: { coupleId: string }) => e.coupleId !== coupleAB)).toBe(true);
    expect(theirs.json().items.map((e: { type: string }) => e.type)).toEqual(['couple.updated']);

    const mine = await t.app.inject({ method: 'GET', url: `${API}/events`, headers: bearer(b) });
    expect(mine.json().items.length).toBeGreaterThan(1);
    expect(mine.json().items.every((e: { coupleId: string }) => e.coupleId === coupleAB)).toBe(true);
  });

  it('cannot act on AB pings / messages / memories / milestones by id', async () => {
    expect(
      (await t.app.inject({ method: 'POST', url: `${API}/pings/${pingId}/delivered`, headers: bearer(c) }))
        .statusCode,
    ).toBe(404);
    expect(
      (await t.app.inject({ method: 'POST', url: `${API}/pings/${pingId}/seen`, headers: bearer(c) }))
        .statusCode,
    ).toBe(404);
    expect(
      (await t.app.inject({ method: 'POST', url: `${API}/messages/${messageId}/read`, headers: bearer(c) }))
        .statusCode,
    ).toBe(404);
    expect(
      (await t.app.inject({ method: 'DELETE', url: `${API}/messages/${messageId}`, headers: bearer(c) }))
        .statusCode,
    ).toBe(404);
    expect(
      (
        await t.app.inject({
          method: 'PATCH',
          url: `${API}/memories/${memoryId}`,
          headers: bearer(c),
          payload: { pinned: true },
        })
      ).statusCode,
    ).toBe(404);
    expect(
      (await t.app.inject({ method: 'DELETE', url: `${API}/memories/${memoryId}`, headers: bearer(c) }))
        .statusCode,
    ).toBe(404);
    expect(
      (
        await t.app.inject({
          method: 'PATCH',
          url: `${API}/milestones/${milestoneId}`,
          headers: bearer(c),
          payload: { title: 'x' },
        })
      ).statusCode,
    ).toBe(404);
    expect(
      (await t.app.inject({ method: 'DELETE', url: `${API}/milestones/${milestoneId}`, headers: bearer(c) }))
        .statusCode,
    ).toBe(404);
    // nothing changed
    const memory = await t.app.inject({ method: 'GET', url: `${API}/memories`, headers: bearer(b) });
    expect(memory.json().items[0].pinned).toBe(false);
  });

  it('cannot obtain AB media URLs, complete or delete AB media, or reference AB media in own messages', async () => {
    expect(
      (await t.app.inject({ method: 'GET', url: `${API}/media/${mediaId}/url`, headers: bearer(c) }))
        .statusCode,
    ).toBe(403);
    expect(
      (await t.app.inject({ method: 'POST', url: `${API}/media/${mediaId}/complete`, headers: bearer(c) }))
        .statusCode,
    ).toBe(403);
    expect(
      (await t.app.inject({ method: 'DELETE', url: `${API}/media/${mediaId}`, headers: bearer(c) }))
        .statusCode,
    ).toBe(403);
    const msg = await t.app.inject({
      method: 'POST',
      url: `${API}/messages`,
      headers: bearer(c),
      payload: { type: 'photo', mediaId, idempotencyKey: randomUUID() },
    });
    expect(msg.statusCode).toBe(404);
    const mem = await t.app.inject({
      method: 'POST',
      url: `${API}/memories`,
      headers: bearer(c),
      payload: { mediaId },
    });
    expect(mem.statusCode).toBe(404);
  });

  it('coupleId is never taken from the request: a forged coupleId in bodies/queries is ignored', async () => {
    const res = await t.app.inject({
      method: 'GET',
      url: `${API}/events?since=0&coupleId=${coupleAB}`,
      headers: bearer(c),
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().items.every((e: { coupleId: string }) => e.coupleId !== coupleAB)).toBe(true);
    const ping = await t.app.inject({
      method: 'POST',
      url: `${API}/pings`,
      headers: bearer(c),
      payload: {
        type: 'hug',
        emoji: '🤗',
        idempotencyKey: randomUUID(),
        coupleId: coupleAB,
        recipientId: a.user.id,
      },
    });
    // unknown keys are rejected outright by the strict schema
    expect(ping.statusCode).toBe(400);
    const abPings = await t.app.inject({ method: 'GET', url: `${API}/pings`, headers: bearer(a) });
    expect(abPings.json().items.every((p: { senderId: string }) => p.senderId !== c.user.id)).toBe(true);
  });

  it('an unpaired user is 409 not_paired everywhere couple-scoped', async () => {
    const solo = await register(t.app, 'Solo');
    for (const path of [
      '/pings',
      '/messages',
      '/memories',
      '/milestones',
      '/events',
      '/couple',
      '/calendar',
      '/alarms',
      `/lists/${'0'.repeat(24)}`,
    ]) {
      const res = await t.app.inject({ method: 'GET', url: `${API}${path}`, headers: bearer(solo) });
      expect(res.statusCode, path).toBe(409);
      expect(res.json().error.code, path).toBe('not_paired');
    }
  });
});
