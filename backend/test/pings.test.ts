import { randomUUID } from 'node:crypto';
import { ObjectId } from 'mongodb';
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
} from './helpers/app.js';

let t: TestApp;
let a: Session;
let b: Session;
let coupleId: string;

beforeAll(async () => {
  t = await createTestApp({ envOverrides: { PING_COOLDOWN_SECONDS: '15', PINGS_PER_HOUR: '40' } });
  a = await register(t.app, 'Sam');
  b = await register(t.app, 'Tia');
  coupleId = await pair(t.app, a, b);
});
afterAll(async () => {
  await t.close();
});

describe('pings', () => {
  it('creates a ping (201), replays the same idempotency key (200, same ping), and emits ping.created', async () => {
    const key = randomUUID();
    const first = await sendPing(t.app, a, {
      idempotencyKey: key,
      type: 'miss_you',
      emoji: '💌',
      message: 'thinking of you',
    });
    expect(first.statusCode).toBe(201);
    const ping = first.json().ping;
    expect(ping).toMatchObject({
      coupleId,
      senderId: a.user.id,
      recipientId: b.user.id,
      status: 'accepted',
      type: 'miss_you',
      emoji: '💌',
      message: 'thinking of you',
      idempotencyKey: key,
    });

    const replay = await sendPing(t.app, a, { idempotencyKey: key, type: 'hug', emoji: '🤗' });
    expect(replay.statusCode).toBe(200);
    expect(replay.json().ping).toEqual(ping); // original resource, not the replayed body
    expect(await t.db.pings.countDocuments({ coupleId: new ObjectId(coupleId) })).toBe(1);

    const ev = await t.db.events.findOne({ coupleId: new ObjectId(coupleId), type: 'ping.created' });
    expect(ev!.payload).toEqual({ ping });
    expect(ev!.actorId.toHexString()).toBe(a.user.id);
  });

  it('enforces the cooldown with 429 + Retry-After, and the clock moving forward lifts it', async () => {
    const second = await sendPing(t.app, a);
    expect(second.statusCode).toBe(429);
    expect(second.json().error.code).toBe('rate_limited');
    expect(Number(second.headers['retry-after'])).toBeGreaterThanOrEqual(1);
    expect(Number(second.headers['retry-after'])).toBeLessThanOrEqual(15);

    t.clock.advance(16_000);
    const third = await sendPing(t.app, a);
    expect(third.statusCode).toBe(201);
  });

  it("a user's preference can only tighten the cooldown, never loosen it", async () => {
    // b sets 60 s; server minimum is 15 s → effective 60 s
    const prefs = await t.app.inject({
      method: 'PUT',
      url: `${API}/me/preferences`,
      headers: bearer(b),
      payload: { pingCooldownSeconds: 60 },
    });
    expect(prefs.statusCode).toBe(200);
    expect((await sendPing(t.app, b)).statusCode).toBe(201);
    t.clock.advance(20_000);
    expect((await sendPing(t.app, b)).statusCode).toBe(429);
    t.clock.advance(41_000);
    expect((await sendPing(t.app, b)).statusCode).toBe(201);

    // a sets 0 → still bounded by the 15 s server minimum
    await t.app.inject({
      method: 'PUT',
      url: `${API}/me/preferences`,
      headers: bearer(a),
      payload: { pingCooldownSeconds: 0 },
    });
    t.clock.advance(16_000);
    expect((await sendPing(t.app, a)).statusCode).toBe(201);
    expect((await sendPing(t.app, a)).statusCode).toBe(429);
  });

  it('sender cannot mark delivered/seen; recipient can; both idempotent; events carry pingId + timestamps', async () => {
    t.clock.advance(16_000);
    const created = await sendPing(t.app, a);
    expect(created.statusCode).toBe(201);
    const id = created.json().ping.id;

    const senderDelivered = await t.app.inject({
      method: 'POST',
      url: `${API}/pings/${id}/delivered`,
      headers: bearer(a),
    });
    expect(senderDelivered.statusCode).toBe(403);
    const senderSeen = await t.app.inject({
      method: 'POST',
      url: `${API}/pings/${id}/seen`,
      headers: bearer(a),
    });
    expect(senderSeen.statusCode).toBe(403);

    const delivered = await t.app.inject({
      method: 'POST',
      url: `${API}/pings/${id}/delivered`,
      headers: bearer(b),
    });
    expect(delivered.statusCode).toBe(200);
    expect(delivered.json().ping.status).toBe('delivered');
    const deliveredAt = delivered.json().ping.deliveredAt;
    expect(deliveredAt).toBeTypeOf('string');

    const deliveredAgain = await t.app.inject({
      method: 'POST',
      url: `${API}/pings/${id}/delivered`,
      headers: bearer(b),
    });
    expect(deliveredAgain.statusCode).toBe(200);
    expect(deliveredAgain.json().ping.deliveredAt).toBe(deliveredAt);

    const seen = await t.app.inject({ method: 'POST', url: `${API}/pings/${id}/seen`, headers: bearer(b) });
    expect(seen.statusCode).toBe(200);
    expect(seen.json().ping.status).toBe('seen');
    const seenAgain = await t.app.inject({
      method: 'POST',
      url: `${API}/pings/${id}/seen`,
      headers: bearer(b),
    });
    expect(seenAgain.json().ping.seenAt).toBe(seen.json().ping.seenAt);

    const events = await t.db.events
      .find({ coupleId: new ObjectId(coupleId), 'payload.pingId': id })
      .sort({ seq: 1 })
      .toArray();
    expect(events.map((e) => e.type)).toEqual(['ping.delivered', 'ping.seen']);
    expect(events[0]!.payload).toEqual({ pingId: id, deliveredAt });
    expect(events[1]!.payload).toEqual({ pingId: id, seenAt: seen.json().ping.seenAt });
    expect(events.every((e) => e.actorId.toHexString() === b.user.id)).toBe(true);
  });

  it('validates type, emoji and message length; lists newest first with cursors', async () => {
    const badType = await sendPing(t.app, a, { type: 'wave' });
    expect(badType.statusCode).toBe(400);
    const badEmoji = await sendPing(t.app, a, { emoji: 'abc' });
    expect(badEmoji.statusCode).toBe(400);
    const longMsg = await sendPing(t.app, a, { message: 'x'.repeat(141) });
    expect(longMsg.statusCode).toBe(400);
    const noKey = await t.app.inject({
      method: 'POST',
      url: `${API}/pings`,
      headers: bearer(a),
      payload: { type: 'hug', emoji: '🤗' },
    });
    expect(noKey.statusCode).toBe(400);

    const list = await t.app.inject({ method: 'GET', url: `${API}/pings?limit=2`, headers: bearer(b) });
    expect(list.statusCode).toBe(200);
    expect(list.json().items).toHaveLength(2);
    expect(list.json().nextCursor).toBeTypeOf('string');
    const page2 = await t.app.inject({
      method: 'GET',
      url: `${API}/pings?limit=2&before=${encodeURIComponent(list.json().nextCursor)}`,
      headers: bearer(b),
    });
    expect(page2.statusCode).toBe(200);
    const ids1 = list.json().items.map((p: { id: string }) => p.id);
    const ids2 = page2.json().items.map((p: { id: string }) => p.id);
    expect(ids1.some((id: string) => ids2.includes(id))).toBe(false);
    const dates = [...list.json().items, ...page2.json().items].map(
      (p: { createdAt: string }) => p.createdAt,
    );
    expect([...dates].sort().reverse()).toEqual(dates);
  });

  it('unpaired users get 409 not_paired', async () => {
    const solo = await register(t.app, 'Uma');
    const res = await sendPing(t.app, solo);
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('not_paired');
  });
});
