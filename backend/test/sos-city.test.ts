import { randomUUID } from 'node:crypto';
import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { REDACT_PATHS } from '../src/lib/logger.js';
import {
  API,
  bearer,
  createTestApp,
  FakePushService,
  pair,
  register,
  type Session,
  type TestApp,
} from './helpers/app.js';

let t: TestApp;
let push: FakePushService;
let a: Session;
let b: Session;
let c: Session;
let coupleId: string;

beforeAll(async () => {
  push = new FakePushService();
  t = await createTestApp({ push, envOverrides: { PING_COOLDOWN_SECONDS: '0' } });
  a = await register(t.app, 'Ama');
  b = await register(t.app, 'Bo');
  c = await register(t.app, 'Cy');
  coupleId = await pair(t.app, a, b);
  await pair(t.app, c, await register(t.app, 'Di'));
  for (const [s, token] of [
    [a, 'sos-a'],
    [b, 'sos-b'],
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

const raise = (s: Session, payload: Record<string, unknown> = {}) =>
  t.app.inject({ method: 'POST', url: `${API}/sos`, headers: bearer(s), payload });
const ack = (s: Session, id: string) =>
  t.app.inject({ method: 'POST', url: `${API}/sos/${id}/ack`, headers: bearer(s) });

function hhmm(d: Date): string {
  return `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`;
}

describe('SOS', () => {
  it('creates an SOS, emits sos.raised, lists it; recipient acks once; sender ack 403; other couple 404', async () => {
    const res = await raise(a, { message: '  please call me  ' });
    expect(res.statusCode).toBe(201);
    const sos = res.json().sos;
    expect(sos).toEqual({
      id: expect.any(String),
      coupleId,
      senderId: a.user.id,
      recipientId: b.user.id,
      message: 'please call me',
      createdAt: expect.any(String),
      acknowledgedAt: null,
    });
    const ev = await t.db.events.findOne({ type: 'sos.raised', 'payload.sos.id': sos.id });
    expect(ev!.payload).toEqual({ sos });

    const list = await t.app.inject({ method: 'GET', url: `${API}/sos?limit=5`, headers: bearer(b) });
    expect(list.statusCode).toBe(200);
    expect(list.json().items[0]).toEqual(sos);
    expect(
      (await t.app.inject({ method: 'GET', url: `${API}/sos?limit=21`, headers: bearer(b) })).statusCode,
    ).toBe(400);

    const bySender = await ack(a, sos.id);
    expect(bySender.statusCode).toBe(403);
    expect(bySender.json().error.code).toBe('forbidden');
    expect((await ack(c, sos.id)).statusCode).toBe(404);
    expect((await ack(b, new ObjectId().toHexString())).statusCode).toBe(404);

    const first = await ack(b, sos.id);
    expect(first.statusCode).toBe(200);
    const acknowledgedAt = first.json().sos.acknowledgedAt;
    expect(acknowledgedAt).not.toBeNull();
    t.clock.advance(1000);
    const again = await ack(b, sos.id);
    expect(again.statusCode).toBe(200);
    expect(again.json().sos.acknowledgedAt).toBe(acknowledgedAt);
    const acks = await t.db.events.find({ type: 'sos.acknowledged', 'payload.sosId': sos.id }).toArray();
    expect(acks).toHaveLength(1);
    expect(acks[0]!.payload).toEqual({ sosId: sos.id, acknowledgedAt });

    // C's couple sees nothing
    const forC = await t.app.inject({ method: 'GET', url: `${API}/sos`, headers: bearer(c) });
    expect(forC.json().items).toEqual([]);

    // export includes SOS
    const exp = await t.app.inject({ method: 'GET', url: `${API}/me/export`, headers: bearer(b) });
    expect(exp.json().sos.map((x: { id: string }) => x.id)).toContain(sos.id);
  });

  it('validates the body', async () => {
    expect((await raise(a, { message: 'x'.repeat(141) })).statusCode).toBe(400);
    expect((await raise(a, { extra: 1 })).statusCode).toBe(400);
  });

  it('allows 3 per sender per 10 minutes, then 429 rate_limited with Retry-After', async () => {
    t.clock.advance(11 * 60_000); // forget the first test's SOS
    // a fresh access token: the clock moved past the access token TTL
    const refresh = async (s: Session) => {
      const r = await t.app.inject({
        method: 'POST',
        url: `${API}/auth/refresh`,
        payload: { refreshToken: s.tokens.refreshToken },
      });
      expect(r.statusCode).toBe(200);
      s.tokens = r.json().tokens;
    };
    await refresh(a);
    await refresh(b);
    for (let i = 0; i < 3; i++) expect((await raise(a)).statusCode).toBe(201);
    const limited = await raise(a);
    expect(limited.statusCode).toBe(429);
    expect(limited.json().error.code).toBe('rate_limited');
    expect(Number(limited.headers['retry-after'])).toBeGreaterThan(0);
    expect(Number(limited.headers['retry-after'])).toBeLessThanOrEqual(600);
    expect((await raise(b)).statusCode).toBe(201); // per sender
    expect((await raise(b, { message: '' })).json().sos.message).toBeNull();
  });

  it('log redaction covers SOS content', () => {
    for (const p of ['sos', '*.sos', 'sos.message', '*.sos.message', 'city', '*.city']) {
      expect(REDACT_PATHS).toContain(p);
    }
  });
});

describe('SOS push bypass', () => {
  it('pushes sos.raised despite notificationsEnabled=false and quiet hours; other events keep the old rules', async () => {
    const t2 = await createTestApp({ push, envOverrides: { PING_COOLDOWN_SECONDS: '0' } });
    try {
      const x = await register(t2.app, 'Xu');
      const y = await register(t2.app, 'Ye');
      await pair(t2.app, x, y);
      const dev = await t2.app.inject({
        method: 'PUT',
        url: `${API}/devices`,
        headers: bearer(y),
        payload: {
          deviceId: 'dev-y',
          platform: 'android',
          token: 'tok-y',
          appVersion: '1.0.0',
          model: 'Pixel',
        },
      });
      expect(dev.statusCode).toBe(204);
      await t2.outbox.runOnce();
      push.calls.length = 0;

      let sends = 0;
      const send = async () => {
        // stay under 3 SOS per 10 minutes (and inside the 15-minute access token TTL)
        if (sends++ > 0) t2.clock.advance(3.5 * 60_000);
        const sos = (
          await t2.app.inject({ method: 'POST', url: `${API}/sos`, headers: bearer(x), payload: {} })
        ).json().sos;
        const ping = await t2.app.inject({
          method: 'POST',
          url: `${API}/pings`,
          headers: bearer(x),
          payload: { type: 'hug', emoji: '🤗', idempotencyKey: randomUUID() },
        });
        expect(ping.statusCode).toBe(201);
        await t2.outbox.runOnce();
        const sosEv = await t2.db.events.findOne({ type: 'sos.raised', 'payload.sos.id': sos.id });
        const pingEv = await t2.db.events.findOne({
          type: 'ping.created',
          'payload.ping.id': ping.json().ping.id,
        });
        return { sosEv: sosEv!, pingEv: pingEv! };
      };

      // 1. notifications disabled
      const p1 = await t2.app.inject({
        method: 'PUT',
        url: `${API}/me/preferences`,
        headers: bearer(y),
        payload: { notificationsEnabled: false },
      });
      expect(p1.statusCode).toBe(200);
      const r1 = await send();
      expect(r1.sosEv.push.state).toBe('sent');
      // Notifications off: the ping is pushed silently (normal), the SOS at full priority.
      expect(r1.pingEv.push.state).toBe('sent');
      expect(push.calls.find((call) => call.data.type === 'ping.created')!.opts.priority).toBe('normal');
      expect(push.calls.find((call) => call.data.type === 'sos.raised')!.opts.priority).toBe('high');
      expect(push.calls.filter((call) => call.data.type === 'sos.raised')).toHaveLength(1);
      expect(push.calls.find((call) => call.data.type === 'sos.raised')!.tokens).toEqual(['tok-y']);

      // 2. inside quiet hours (window around "now", UTC)
      const now = t2.clock.now();
      const p2 = await t2.app.inject({
        method: 'PUT',
        url: `${API}/me/preferences`,
        headers: bearer(y),
        payload: {
          notificationsEnabled: true,
          allowPushInQuietHours: false,
          quietHours: {
            enabled: true,
            start: hhmm(new Date(now.getTime() - 3600_000)),
            end: hhmm(new Date(now.getTime() + 3600_000)),
            timeZone: 'UTC',
          },
        },
      });
      expect(p2.statusCode).toBe(200);
      const r2 = await send();
      expect(r2.sosEv.push.state).toBe('sent');
      // Quiet hours keep the ping high: the app posts it on its silent quiet-hours channel. Only
      // notificationsEnabled=false (step 1) downgrades it to a silent normal push.
      expect(r2.pingEv.push.state).toBe('sent');
      const quietPing = push.calls.filter((call) => call.data.type === 'ping.created').at(-1);
      expect(quietPing!.opts.priority).toBe('high');

      // 3. normal rules: notifications on, no quiet hours ⇒ both pushed
      await t2.app.inject({
        method: 'PUT',
        url: `${API}/me/preferences`,
        headers: bearer(y),
        payload: { quietHours: { enabled: false } },
      });
      const r3 = await send();
      expect(r3.sosEv.push.state).toBe('sent');
      expect(r3.pingEv.push.state).toBe('sent');

      // 4. no tokens still skips an SOS
      await t2.db.deviceTokens.deleteMany({});
      const r4 = await send();
      expect(r4.sosEv.push).toMatchObject({ state: 'skipped', skipReason: 'no_tokens' });
    } finally {
      await t2.close();
    }
  });
});

describe('weather city', () => {
  const patchMe = (s: Session, payload: Record<string, unknown>) =>
    t.app.inject({ method: 'PATCH', url: `${API}/me`, headers: bearer(s), payload });
  const getMe = async (s: Session) =>
    (await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(s) })).json();

  it('defaults to city null, shareWeather true', async () => {
    const me = await getMe(c);
    expect(me.user).toMatchObject({ city: null, shareWeather: true });
    expect(me.partner.city).toBeNull();
  });

  it('validates city', async () => {
    for (const city of [
      { name: '', latitude: 1, longitude: 1 },
      { name: 'x'.repeat(81), latitude: 1, longitude: 1 },
      { name: 'Nowhere', latitude: 90.01, longitude: 0 },
      { name: 'Nowhere', latitude: -91, longitude: 0 },
      { name: 'Nowhere', latitude: 0, longitude: 180.5 },
      { name: 'Nowhere', latitude: 0, longitude: -181 },
      { name: 'Nowhere', latitude: '12', longitude: 0 },
      { name: 'Nowhere', latitude: 0 },
      { name: 'Nowhere', latitude: 0, longitude: 0, country: 'IN' },
    ]) {
      const res = await patchMe(c, { city });
      expect(res.statusCode, JSON.stringify(city)).toBe(400);
      expect(res.json().error.code).toBe('validation_failed');
    }
    expect((await patchMe(c, { shareWeather: 'yes' })).statusCode).toBe(400);
  });

  it('rounds coordinates to 2 decimals, shows the partner, hides with shareWeather=false, emits partner.updated', async () => {
    const before = await t.db.events.countDocuments({
      coupleId: new ObjectId(coupleId),
      type: 'partner.updated',
    });
    const res = await patchMe(a, { city: { name: '  Patna  ', latitude: 25.594095, longitude: 85.137566 } });
    expect(res.statusCode).toBe(200);
    const city = { name: 'Patna', latitude: 25.59, longitude: 85.14 };
    expect(res.json().user).toMatchObject({ city, shareWeather: true });
    expect((await getMe(b)).partner.city).toEqual(city);

    const evs = () =>
      t.db.events
        .find({ coupleId: new ObjectId(coupleId), type: 'partner.updated' })
        .sort({ seq: 1 })
        .toArray();
    let all = await evs();
    expect(all.length).toBe(before + 1);
    expect((all.at(-1)!.payload as { partner: { city: unknown } }).partner.city).toEqual(city);

    // a no-op PATCH emits nothing
    await patchMe(a, { city: { name: 'Patna', latitude: 25.59, longitude: 85.14 } });
    expect((await evs()).length).toBe(before + 1);

    const hide = await patchMe(a, { shareWeather: false });
    expect(hide.json().user).toMatchObject({ city, shareWeather: false });
    expect((await getMe(b)).partner.city).toBeNull();
    all = await evs();
    expect(all.length).toBe(before + 2);
    expect((all.at(-1)!.payload as { partner: { city: unknown } }).partner.city).toBeNull();

    await patchMe(a, { shareWeather: true });
    expect((await getMe(b)).partner.city).toEqual(city);

    // negative coordinates round too; null clears
    const neg = await patchMe(a, { city: { name: 'Lima', latitude: -12.04637, longitude: -77.042793 } });
    expect(neg.json().user.city).toEqual({ name: 'Lima', latitude: -12.05, longitude: -77.04 });
    const cleared = await patchMe(a, { city: null });
    expect(cleared.json().user.city).toBeNull();
    expect((await getMe(b)).partner.city).toBeNull();
    expect((await evs()).length).toBe(before + 5);
  });

  it('old documents without the fields read as null / true', async () => {
    await t.db.users.updateOne({ _id: new ObjectId(c.user.id) }, { $unset: { city: '', shareWeather: '' } });
    const me = await getMe(c);
    expect(me.user).toMatchObject({ city: null, shareWeather: true });
  });
});
