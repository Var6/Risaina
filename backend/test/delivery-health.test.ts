import { randomUUID } from 'node:crypto';
import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { REFRESH_GRACE_MS } from '../src/modules/auth/service.js';
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

async function registerDevice(t: TestApp, s: Session, deviceId: string, token: string) {
  const res = await t.app.inject({
    method: 'PUT',
    url: `${API}/devices`,
    headers: bearer(s),
    payload: { deviceId, platform: 'android', token, appVersion: '1.0.0', model: 'RMX3741' },
  });
  expect(res.statusCode).toBe(204);
}

describe('test push and delivery health', () => {
  let t: TestApp;
  let push: FakePushService;
  let a: Session;
  let b: Session;
  const tokA1 = `fcm-a1-${randomUUID()}`;
  const tokA2 = `fcm-a2-${randomUUID()}`;
  const tokB = `fcm-b-${randomUUID()}`;

  beforeAll(async () => {
    push = new FakePushService();
    t = await createTestApp({ push, envOverrides: { PING_COOLDOWN_SECONDS: '0' } });
    a = await register(t.app, 'Ha');
    b = await register(t.app, 'Hb');
  });
  afterAll(async () => {
    await t.close();
  });

  const testPush = (s: Session, payload: Record<string, unknown> = {}) =>
    t.app.inject({ method: 'POST', url: `${API}/devices/test-push`, headers: bearer(s), payload });

  it('says when the caller has no registered phone', async () => {
    const res = await testPush(a);
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ scheduled: false, devices: 0, reason: 'no_tokens' });
    expect(push.calls).toHaveLength(0);
  });

  it('sends a high-priority test.push to my own phones only, never the partner', async () => {
    await pair(t.app, a, b);
    await registerDevice(t, a, 'a-phone', tokA1);
    await registerDevice(t, a, 'a-tablet', tokA2);
    await registerDevice(t, b, 'b-phone', tokB);
    const eventsBefore = await t.db.events.countDocuments({});

    const res = await testPush(a);
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ scheduled: true, devices: 2, reason: null });
    await t.ctx.background.drain();
    const call = push.calls.at(-1)!;
    expect(call.tokens.sort()).toEqual([tokA1, tokA2].sort());
    expect(call.data.type).toBe('test.push');
    expect(call.data.pr).toBe('h');
    expect(call.data.coupleId).toBe('');
    expect(call.opts.priority).toBe('high');
    // Not couple data: nothing is stored as an event.
    expect(await t.db.events.countDocuments({})).toBe(eventsBefore);

    // One phone only.
    const one = await testPush(a, { deviceId: 'a-phone' });
    expect(one.json()).toEqual({ scheduled: true, devices: 1, reason: null });
    await t.ctx.background.drain();
    expect(push.calls.at(-1)!.tokens).toEqual([tokA1]);
  });

  it('rejects a delay over a minute and unknown fields', async () => {
    expect((await testPush(a, { delaySeconds: 61 })).statusCode).toBe(400);
    expect((await testPush(a, { token: 'x' })).statusCode).toBe(400);
  });

  it('records push acks on my own device only, and flags FCM downgrades', async () => {
    const ack = await t.app.inject({
      method: 'POST',
      url: `${API}/devices/a-phone/push-ack`,
      headers: bearer(a),
      payload: {
        priority: 'normal',
        originalPriority: 'high',
        healthFlags: ['battery_optimized', 'standby_rare'],
      },
    });
    expect(ack.statusCode).toBe(204);
    const row = await t.db.deviceTokens.findOne({ token: tokA1 });
    expect(row!.lastPushAckAt).toBeInstanceOf(Date);
    expect(row!.lastPushPriority).toBe('normal');
    expect(row!.lastPushDowngraded).toBe(true);
    expect(row!.healthFlags).toEqual(['battery_optimized', 'standby_rare']);

    // b cannot write a's row (deviceId is scoped to the caller).
    const other = await t.app.inject({
      method: 'POST',
      url: `${API}/devices/a-tablet/push-ack`,
      headers: bearer(b),
      payload: { priority: 'high', originalPriority: 'high' },
    });
    expect(other.statusCode).toBe(204);
    expect((await t.db.deviceTokens.findOne({ token: tokA2 }))!.lastPushAckAt).toBeUndefined();

    const bad = await t.app.inject({
      method: 'POST',
      url: `${API}/devices/a-phone/push-ack`,
      headers: bearer(a),
      payload: { priority: 'high', originalPriority: 'high', healthFlags: ['Not A Code'] },
    });
    expect(bad.statusCode).toBe(400);
  });

  it("shows my phones and my partner's, without tokens or the partner's device ids", async () => {
    await sendPing(t.app, b); // a's phones get a push → lastPushSentAt
    await t.outbox.runOnce();
    const res = await t.app.inject({ method: 'GET', url: `${API}/devices/health`, headers: bearer(a) });
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(JSON.stringify(body)).not.toContain('fcm-');
    expect(body.mine).toHaveLength(2);
    const phone = body.mine.find((d: { deviceId: string }) => d.deviceId === 'a-phone');
    expect(phone).toMatchObject({
      model: 'RMX3741',
      lastPushPriority: 'normal',
      lastPushDowngraded: true,
      healthFlags: ['battery_optimized', 'standby_rare'],
    });
    expect(typeof phone.lastPushSentAt).toBe('string');
    expect(body.partner).toHaveLength(1);
    expect(body.partner[0]).toMatchObject({ deviceId: null, model: 'RMX3741', lastPushAckAt: null });

    // b sees a as the partner.
    const fromB = (
      await t.app.inject({ method: 'GET', url: `${API}/devices/health`, headers: bearer(b) })
    ).json();
    expect(fromB.mine).toHaveLength(1);
    expect(fromB.partner).toHaveLength(2);
  });

  it('caps test pushes per user', async () => {
    let last = 200;
    for (let i = 0; i < 8; i++) last = (await testPush(b)).statusCode;
    expect(last).toBe(429);
  });
});

describe('test push with push disabled', () => {
  let t: TestApp;
  beforeAll(async () => {
    t = await createTestApp();
  });
  afterAll(async () => {
    await t.close();
  });

  it('reports push_disabled', async () => {
    const s = await register(t.app, 'Hd');
    await registerDevice(t, s, 'd', `fcm-d-${randomUUID()}`);
    const res = await t.app.inject({
      method: 'POST',
      url: `${API}/devices/test-push`,
      headers: bearer(s),
      payload: {},
    });
    expect(res.json()).toEqual({ scheduled: false, devices: 0, reason: 'push_disabled' });
  });
});

describe('refresh grace window', () => {
  let t: TestApp;
  beforeAll(async () => {
    t = await createTestApp();
  });
  afterAll(async () => {
    await t.close();
  });

  const refresh = (token: string) =>
    t.app.inject({ method: 'POST', url: `${API}/auth/refresh`, payload: { refreshToken: token } });

  it('a retry with the just-rotated token, whose response was lost, gets a fresh pair instead of a sign-out', async () => {
    const s = await register(t.app, 'Ga');
    const r1 = await refresh(s.tokens.refreshToken);
    expect(r1.statusCode).toBe(200);
    const lost = r1.json().tokens; // the phone was killed before storing these

    t.clock.advance(20_000);
    const retry = await refresh(s.tokens.refreshToken);
    expect(retry.statusCode).toBe(200);
    const fresh = retry.json().tokens;
    expect(fresh.refreshToken).not.toBe(lost.refreshToken);

    // The pair the phone never stored is dead; the new one works; the family is not revoked.
    expect((await refresh(lost.refreshToken)).statusCode).toBe(401);
    expect(
      (await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(fresh.accessToken) }))
        .statusCode,
    ).toBe(200);
    const sessions = await t.db.sessions.find({ userId: new ObjectId(s.user.id) }).toArray();
    expect(sessions.filter((x) => x.revokedReason === 'refresh_grace_replaced')).toHaveLength(1);
    expect(sessions.filter((x) => x.revokedReason === 'refresh_reuse')).toHaveLength(0);
    expect((await refresh(fresh.refreshToken)).statusCode).toBe(200);
  });

  it('after the grace window, presenting a rotated token is reuse and revokes the family', async () => {
    const s = await register(t.app, 'Gb');
    const r1 = await refresh(s.tokens.refreshToken);
    const t1 = r1.json().tokens;
    t.clock.advance(REFRESH_GRACE_MS + 1_000);
    expect((await refresh(s.tokens.refreshToken)).statusCode).toBe(401);
    expect((await refresh(t1.refreshToken)).statusCode).toBe(401);
  });

  it('once the new pair was used, a retry with the old token is reuse even inside the window', async () => {
    const s = await register(t.app, 'Gc');
    const t1 = (await refresh(s.tokens.refreshToken)).json().tokens;
    expect(
      (await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(t1.accessToken) })).statusCode,
    ).toBe(200);
    expect((await refresh(s.tokens.refreshToken)).statusCode).toBe(401);
    const family = await t.db.sessions.find({ userId: new ObjectId(s.user.id) }).toArray();
    expect(family.every((x) => x.revokedReason === 'refresh_reuse')).toBe(true);
  });
});
