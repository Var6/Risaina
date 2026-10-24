import { randomUUID } from 'node:crypto';
import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { isInQuietHours, parseHHmm } from '../src/lib/quiet-hours.js';
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
} from './helpers/app.js';

describe('outbox with push disabled', () => {
  let t: TestApp;
  beforeAll(async () => {
    t = await createTestApp();
  });
  afterAll(async () => {
    await t.close();
  });

  it('marks every pending event skipped (push_disabled) and never throws', async () => {
    const a = await register(t.app, 'Oa');
    const b = await register(t.app, 'Ob');
    await pair(t.app, a, b);
    await sendPing(t.app, a);
    const processed = await t.outbox.runOnce();
    expect(processed).toBeGreaterThanOrEqual(2); // couple.updated + ping.created
    const events = await t.db.events.find({}).toArray();
    expect(events.every((e) => e.push.state === 'skipped')).toBe(true);
    expect(events.every((e) => e.push.skipReason === 'push_disabled')).toBe(true);
    expect(await t.outbox.runOnce()).toBe(0);
  });
});

describe('outbox with a push service', () => {
  let t: TestApp;
  let push: FakePushService;
  let a: Session;
  let b: Session;

  beforeAll(async () => {
    push = new FakePushService();
    t = await createTestApp({
      push,
      outbox: { backoffBaseMs: 1000, maxAttempts: 5 },
      envOverrides: { PING_COOLDOWN_SECONDS: '0' },
    });
    a = await register(t.app, 'Pa');
    b = await register(t.app, 'Pb');
    await pair(t.app, a, b);
    await t.outbox.runOnce(); // drain couple.updated
  });
  afterAll(async () => {
    await t.close();
  });

  async function registerDevice(s: Session, token: string, deviceId = `dev-${randomUUID().slice(0, 6)}`) {
    const res = await t.app.inject({
      method: 'PUT',
      url: `${API}/devices`,
      headers: bearer(s),
      payload: { deviceId, platform: 'android', token, appVersion: '1.0.0', model: 'Pixel' },
    });
    expect(res.statusCode).toBe(204);
  }

  it('skips when the recipient has no device tokens', async () => {
    const ping = await sendPing(t.app, a);
    await t.outbox.runOnce();
    const ev = await t.db.events.findOne({ type: 'ping.created', 'payload.ping.id': ping.json().ping.id });
    expect(ev!.push.state).toBe('skipped');
    expect(ev!.push.skipReason).toBe('no_tokens');
    expect(push.calls).toHaveLength(0);
  });

  it('sends a data-only payload {type,eventId,coupleId,seq} to the OTHER member only', async () => {
    const tokenB = `fcm-b-${randomUUID()}`;
    const tokenA = `fcm-a-${randomUUID()}`;
    await registerDevice(b, tokenB);
    await registerDevice(a, tokenA);
    const ping = await sendPing(t.app, a);
    await t.outbox.runOnce();
    const ev = await t.db.events.findOne({ type: 'ping.created', 'payload.ping.id': ping.json().ping.id });
    expect(ev!.push.state).toBe('sent');
    expect(ev!.push.attempts).toBe(1);
    expect(ev!.push.sentAt).toBeInstanceOf(Date);
    const call = push.calls[push.calls.length - 1]!;
    expect(call.tokens).toEqual([tokenB]);
    expect(call.data).toEqual({
      type: 'ping.created',
      eventId: ev!._id.toHexString(),
      coupleId: ev!.coupleId.toHexString(),
      seq: String(ev!.seq),
    });
    expect(Object.keys(call.data).sort()).toEqual(['coupleId', 'eventId', 'seq', 'type']); // no content leaks
  });

  it('retries with exponential backoff; attempts increment and the state becomes sent', async () => {
    push.failTimes = 2;
    const ping = await sendPing(t.app, b); // recipient = a, who has a token
    const filter = { type: 'ping.created' as const, 'payload.ping.id': ping.json().ping.id };

    const t0 = t.clock.now().getTime();
    await t.outbox.runOnce();
    let ev = await t.db.events.findOne(filter);
    expect(ev!.push.state).toBe('pending');
    expect(ev!.push.attempts).toBe(1);
    expect(ev!.push.lastError).toBe('messaging/internal-error');
    // backoff: not before now + 1000 ms (2^0 * base); allow for the few ms the run itself took
    expect(ev!.push.leaseUntil!.getTime()).toBeGreaterThanOrEqual(t0 + 1000);
    expect(ev!.push.leaseUntil!.getTime()).toBeLessThanOrEqual(t.clock.now().getTime() + 1000);

    // not leasable until the backoff has elapsed
    expect(await t.outbox.runOnce()).toBe(0);
    t.clock.advance(1001);
    const t1 = t.clock.now().getTime();
    await t.outbox.runOnce();
    ev = await t.db.events.findOne(filter);
    expect(ev!.push.state).toBe('pending');
    expect(ev!.push.attempts).toBe(2);
    expect(ev!.push.leaseUntil!.getTime()).toBeGreaterThanOrEqual(t1 + 2000); // 2^1 * base
    expect(ev!.push.leaseUntil!.getTime()).toBeLessThanOrEqual(t.clock.now().getTime() + 2000);

    t.clock.advance(2001);
    await t.outbox.runOnce();
    ev = await t.db.events.findOne(filter);
    expect(ev!.push.state).toBe('sent');
    expect(ev!.push.attempts).toBe(3);
    expect(ev!.push.leaseUntil).toBeNull();
  });

  it('gives up as failed after maxAttempts', async () => {
    push.failTimes = 10;
    const ping = await sendPing(t.app, b);
    const filter = { type: 'ping.created' as const, 'payload.ping.id': ping.json().ping.id };
    for (let i = 0; i < 6; i++) {
      await t.outbox.runOnce();
      t.clock.advance(20_000); // > max backoff (2^3 * 1000 ms)
    }
    const ev = await t.db.events.findOne(filter);
    expect(ev!.push.state).toBe('failed');
    expect(ev!.push.attempts).toBe(5);
    expect(ev!.push.lastError).toBe('messaging/internal-error');
    push.failTimes = 0;
  });

  it('removes tokens FCM reports as unregistered', async () => {
    const dead = `fcm-dead-${randomUUID()}`;
    await registerDevice(a, dead, 'dev-dead');
    push.unregistered.add(dead);
    const ping = await sendPing(t.app, b);
    await t.outbox.runOnce();
    const ev = await t.db.events.findOne({ type: 'ping.created', 'payload.ping.id': ping.json().ping.id });
    expect(ev!.push.state).toBe('sent'); // a still has one live token
    expect(await t.db.deviceTokens.countDocuments({ token: dead })).toBe(0);
    expect(await t.db.deviceTokens.countDocuments({ userId: new ObjectId(a.user.id) })).toBe(1);
  });

  it('skips when the recipient disabled notifications', async () => {
    await t.app.inject({
      method: 'PUT',
      url: `${API}/me/preferences`,
      headers: bearer(a),
      payload: { notificationsEnabled: false },
    });
    const ping = await sendPing(t.app, b);
    await t.outbox.runOnce();
    const ev = await t.db.events.findOne({ type: 'ping.created', 'payload.ping.id': ping.json().ping.id });
    expect(ev!.push.state).toBe('skipped');
    expect(ev!.push.skipReason).toBe('notifications_disabled');
    await t.app.inject({
      method: 'PUT',
      url: `${API}/me/preferences`,
      headers: bearer(a),
      payload: { notificationsEnabled: true },
    });
  });

  it('skips inside quiet hours (recipient time zone, window crossing midnight) unless allowPushInQuietHours', async () => {
    // Recipient a in Asia/Kolkata (UTC+5:30). Build a window that contains "now" and crosses midnight.
    const now = t.clock.now();
    const fmt = new Intl.DateTimeFormat('en-US', {
      timeZone: 'Asia/Kolkata',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    });
    const parts = Object.fromEntries(fmt.formatToParts(now).map((p) => [p.type, p.value]));
    const hour = Number(parts.hour) % 24;
    const hh = (h: number) => String(((h % 24) + 24) % 24).padStart(2, '0');
    // A 23-hour window from (H+k):00 to (H+k-1):00 always contains "now" and crosses midnight
    // as long as its start is not exactly 00:00 (then it would be a plain 00:00→23:00 window).
    let k = 12;
    if ((hour + k) % 24 === 0) k = 13;
    const start = `${hh(hour + k)}:00`;
    const end = `${hh(hour + k - 1)}:00`;
    expect(parseHHmm(start)! > parseHHmm(end)!).toBe(true); // proves the window crosses midnight
    const window = { enabled: true, start, end, timeZone: 'Asia/Kolkata' };
    expect(isInQuietHours(window, now)).toBe(true);

    const set = await t.app.inject({
      method: 'PUT',
      url: `${API}/me/preferences`,
      headers: bearer(a),
      payload: { quietHours: window, allowPushInQuietHours: false },
    });
    expect(set.statusCode).toBe(200);
    const ping = await sendPing(t.app, b);
    await t.outbox.runOnce();
    const ev = await t.db.events.findOne({ type: 'ping.created', 'payload.ping.id': ping.json().ping.id });
    expect(ev!.push.state).toBe('skipped');
    expect(ev!.push.skipReason).toBe('quiet_hours');

    // allowPushInQuietHours → sent
    await t.app.inject({
      method: 'PUT',
      url: `${API}/me/preferences`,
      headers: bearer(a),
      payload: { allowPushInQuietHours: true },
    });
    const ping2 = await sendPing(t.app, b);
    await t.outbox.runOnce();
    const ev2 = await t.db.events.findOne({ type: 'ping.created', 'payload.ping.id': ping2.json().ping.id });
    expect(ev2!.push.state).toBe('sent');
  });
});

describe('quiet hours helper', () => {
  it('handles windows crossing midnight in the recipient zone', () => {
    const w = { enabled: true, start: '22:00', end: '07:00', timeZone: 'America/New_York' };
    // 2026-01-15T04:30Z = 23:30 EST (inside)
    expect(isInQuietHours(w, new Date('2026-01-15T04:30:00Z'))).toBe(true);
    // 2026-01-15T11:30Z = 06:30 EST (inside)
    expect(isInQuietHours(w, new Date('2026-01-15T11:30:00Z'))).toBe(true);
    // 2026-01-15T12:30Z = 07:30 EST (outside)
    expect(isInQuietHours(w, new Date('2026-01-15T12:30:00Z'))).toBe(false);
    // 2026-01-15T17:00Z = 12:00 EST (outside)
    expect(isInQuietHours(w, new Date('2026-01-15T17:00:00Z'))).toBe(false);
    // same instant, Tokyo (UTC+9): 2026-01-15T14:30Z = 23:30 JST (inside)
    expect(isInQuietHours({ ...w, timeZone: 'Asia/Tokyo' }, new Date('2026-01-15T14:30:00Z'))).toBe(true);
    // non-wrapping window
    const day = { enabled: true, start: '09:00', end: '17:00', timeZone: 'UTC' };
    expect(isInQuietHours(day, new Date('2026-01-15T12:00:00Z'))).toBe(true);
    expect(isInQuietHours(day, new Date('2026-01-15T20:00:00Z'))).toBe(false);
    expect(isInQuietHours({ ...day, enabled: false }, new Date('2026-01-15T12:00:00Z'))).toBe(false);
    expect(isInQuietHours({ ...day, start: '09:00', end: '09:00' }, new Date('2026-01-15T12:00:00Z'))).toBe(
      false,
    );
  });
});
