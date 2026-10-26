import type { AddressInfo } from 'node:net';
import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import type { EventType } from '../src/db/types.js';
import { REDACT_PATHS } from '../src/lib/logger.js';
import { LIVE_LOCATION_IDLE_PURGE_MS } from '../src/modules/location/service.js';
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
let d: Session;
let coupleId: string;
let wsUrl: string;

const oid = () => new ObjectId().toHexString();

beforeAll(async () => {
  push = new FakePushService();
  // Long-lived access tokens: these tests move the clock past expiries.
  t = await createTestApp({ push, envOverrides: { ACCESS_TOKEN_TTL_SECONDS: '172800' } });
  a = await register(t.app, 'Asha');
  b = await register(t.app, 'Bilal');
  c = await register(t.app, 'Chen');
  d = await register(t.app, 'Dara');
  coupleId = await pair(t.app, a, b);
  await pair(t.app, c, d);
  for (const [s, token] of [
    [a, 'loc-a'],
    [b, 'loc-b'],
  ] as const) {
    const res = await t.app.inject({
      method: 'PUT',
      url: `${API}/devices`,
      headers: bearer(s),
      payload: { deviceId: `dev-${token}`, platform: 'android', token, appVersion: '1.0.0', model: 'Pixel' },
    });
    expect(res.statusCode).toBe(204);
  }
  await t.app.listen({ host: '127.0.0.1', port: 0 });
  wsUrl = `ws://127.0.0.1:${(t.app.server.address() as AddressInfo).port}${API}/ws`;
  await t.outbox.runOnce();
  push.calls.length = 0;
});
afterAll(async () => {
  await t.close();
});

const start = (s: Session, payload: Record<string, unknown>) =>
  t.app.inject({ method: 'POST', url: `${API}/location/sharing`, headers: bearer(s), payload });
const putFix = (s: Session, sessionId: string, fix: Record<string, unknown>) =>
  t.app.inject({
    method: 'PUT',
    url: `${API}/location/sharing/fix`,
    headers: bearer(s),
    payload: { sessionId, fix },
  });
const stop = (s: Session, query = '') =>
  t.app.inject({ method: 'DELETE', url: `${API}/location/sharing${query}`, headers: bearer(s) });
const state = async (s: Session) => {
  const res = await t.app.inject({ method: 'GET', url: `${API}/location/sharing`, headers: bearer(s) });
  expect(res.statusCode).toBe(200);
  return res.json<{ mine: Record<string, unknown> | null; partner: Record<string, unknown> | null }>();
};
const fixAt = (lat = 25.594095123, lng = 85.137565987, extra: Record<string, unknown> = {}) => ({
  lat,
  lng,
  accuracyM: 18.4,
  ageMs: 2000,
  batteryPct: 54,
  ...extra,
});
const eventsOf = (type: EventType) => t.db.events.find({ type }).toArray();

type Msg = Record<string, unknown> & { type: string };
async function socketFor(s: Session): Promise<{ ws: WebSocket; frames: Msg[] }> {
  const ws = new WebSocket(wsUrl);
  const frames: Msg[] = [];
  let synced: () => void = () => {};
  const ready = new Promise<void>((resolve) => {
    synced = resolve;
  });
  ws.on('message', (raw) => {
    const m = JSON.parse(raw.toString()) as Msg;
    if (m.type === 'ready') ws.send(JSON.stringify({ type: 'sync', since: Number(m.latestSeq) }));
    else if (m.type === 'synced') synced();
    else frames.push(m);
  });
  await new Promise<void>((resolve, reject) => {
    ws.once('open', () => resolve());
    ws.once('error', reject);
  });
  ws.send(JSON.stringify({ type: 'auth', token: s.tokens.accessToken }));
  await ready;
  return { ws, frames };
}
const settle = () => new Promise((r) => setTimeout(r, 150));

describe('live location: start, fixes, stop', () => {
  let sessionId: string;

  it('starts manual sharing for 1 hour: every required field, one event without coordinates, pushed to the partner', async () => {
    sessionId = oid();
    const res = await start(a, { sessionId, mode: 'timed', durationMinutes: 60 });
    expect(res.statusCode).toBe(201);
    const sharing = res.json().sharing;
    expect(sharing).toMatchObject({
      sessionId,
      userId: a.user.id,
      reason: 'manual',
      sosId: null,
      fix: null,
      startedAt: expect.any(String),
      expiresAt: expect.any(String),
      lastSeenAt: expect.any(String),
    });
    expect(new Date(sharing.expiresAt).getTime() - new Date(sharing.startedAt).getTime()).toBe(60 * 60_000);

    const doc = await t.db.liveLocations.findOne({ _id: new ObjectId(sessionId) });
    expect(doc).toMatchObject({
      coupleId: new ObjectId(coupleId),
      userId: new ObjectId(a.user.id),
      viewerId: new ObjectId(b.user.id),
      reason: 'manual',
      sosId: null,
      live: true,
      fix: null,
      stoppedAt: null,
    });
    expect(doc!.purgeAt.getTime()).toBe(doc!.expiresAt!.getTime()); // timed: purged at expiry

    const started = await eventsOf('location.started');
    expect(started).toHaveLength(1);
    expect(started[0]!.payload).toEqual({
      sharing: {
        sessionId,
        userId: a.user.id,
        reason: 'manual',
        sosId: null,
        startedAt: sharing.startedAt,
        expiresAt: sharing.expiresAt,
        updatedAt: sharing.updatedAt,
      },
    });
    await t.outbox.runOnce();
    const sent = push.calls.filter((p) => p.data.type === 'location.started');
    expect(sent).toHaveLength(1);
    expect(sent[0]!.tokens).toEqual(['loc-b']);
    expect(Object.keys(sent[0]!.data).sort()).toEqual(['coupleId', 'eventId', 'seq', 'type']);

    expect((await state(b)).partner).toMatchObject({ sessionId, fix: null });
    expect((await state(b)).mine).toBeNull();
    expect((await state(a)).mine).toMatchObject({ sessionId });
    expect((await state(a)).partner).toBeNull();
    // the other couple sees nothing
    expect(await state(c)).toEqual({ mine: null, partner: null });
  });

  it('fixes go to the partner as frames only (never to the sharer), rounded, with battery, and never as events or pushes', async () => {
    const viewer = await socketFor(b);
    const sharer = await socketFor(a);
    const eventsBefore = await t.db.events.countDocuments();
    push.calls.length = 0;
    try {
      const res = await putFix(a, sessionId, fixAt());
      expect(res.statusCode).toBe(200);
      expect(res.json()).toEqual({ accepted: true });
      await settle();
      const frames = viewer.frames.filter((f) => f.type === 'location');
      expect(frames).toHaveLength(1);
      const location = frames[0]!.location as { fix: Record<string, unknown>; sessionId: string };
      expect(location.sessionId).toBe(sessionId);
      expect(location.fix).toMatchObject({ lat: 25.5941, lng: 85.13757, accuracyM: 19, batteryPct: 54 });
      expect(sharer.frames.filter((f) => f.type === 'location')).toHaveLength(0);

      for (let i = 1; i <= 4; i++) {
        t.clock.advance(1000); // each fix is newer than the last
        expect((await putFix(a, sessionId, fixAt(25.6 + i / 1000, 85.1, { ageMs: 0 }))).statusCode).toBe(200);
      }
      expect(await t.db.events.countDocuments()).toBe(eventsBefore);
      await t.outbox.runOnce();
      expect(push.calls).toHaveLength(0);
      const stored = await t.db.liveLocations.findOne({ _id: new ObjectId(sessionId) });
      expect(stored!.fix).toMatchObject({ lat: 25.604, lng: 85.1, batteryPct: 54 });
    } finally {
      viewer.ws.close();
      sharer.ws.close();
    }
  });

  it('an older fix is not stored but still counts as a check-in; bad ids and other people get 409', async () => {
    const before = await t.db.liveLocations.findOne({ _id: new ObjectId(sessionId) });
    t.clock.advance(5_000);
    const old = await putFix(a, sessionId, fixAt(10, 10, { ageMs: 600_000 }));
    expect(old.statusCode).toBe(200);
    expect(old.json()).toEqual({ accepted: false });
    const after = await t.db.liveLocations.findOne({ _id: new ObjectId(sessionId) });
    expect(after!.fix).toEqual(before!.fix);
    expect(after!.lastSeenAt.getTime()).toBeGreaterThan(before!.lastSeenAt.getTime());

    const unknown = await putFix(a, oid(), fixAt());
    expect(unknown.statusCode).toBe(409);
    expect(unknown.json().error.code).toBe('location_not_sharing');
    expect((await putFix(b, sessionId, fixAt())).json().error.code).toBe('location_not_sharing');
    expect((await putFix(c, sessionId, fixAt())).json().error.code).toBe('location_not_sharing');
  });

  it('extends with the same session: a new expiry emits location.started again, the same request emits nothing', async () => {
    const before = (await eventsOf('location.started')).length;
    const res = await start(a, { sessionId, mode: 'timed', durationMinutes: 480 });
    expect(res.statusCode).toBe(200);
    expect(res.json().sharing.sessionId).toBe(sessionId);
    expect((await eventsOf('location.started')).length).toBe(before + 1);
    expect((await start(a, { sessionId, mode: 'timed', durationMinutes: 480 })).statusCode).toBe(200);
    expect((await eventsOf('location.started')).length).toBe(before + 1);
    // a start with a different id while live merges into the live session
    const other = await start(a, { sessionId: oid(), mode: 'timed', durationMinutes: 480 });
    expect(other.statusCode).toBe(200);
    expect(other.json().sharing.sessionId).toBe(sessionId);
    expect(await t.db.liveLocations.countDocuments({ userId: new ObjectId(a.user.id) })).toBe(1);
  });

  it('stop removes the coordinates at once, emits location.stopped once, and a late start with that id is refused', async () => {
    const res = await stop(a, `?sessionId=${sessionId}`);
    expect(res.statusCode).toBe(204);
    const doc = await t.db.liveLocations.findOne({ _id: new ObjectId(sessionId) });
    expect(doc).toMatchObject({ live: false, fix: null, stoppedAt: expect.any(Date) });
    expect(doc!.purgeAt.getTime() - t.clock.now().getTime()).toBeLessThanOrEqual(10 * 60_000);
    const stopped = await eventsOf('location.stopped');
    expect(stopped).toHaveLength(1);
    expect(stopped[0]!.payload).toEqual({
      sessionId,
      userId: a.user.id,
      stoppedAt: expect.any(String),
      reason: 'stopped',
    });
    await t.outbox.runOnce();
    expect(push.calls.filter((p) => p.data.type === 'location.stopped')).toHaveLength(1);

    expect((await stop(a, `?sessionId=${sessionId}`)).statusCode).toBe(204);
    expect(await eventsOf('location.stopped')).toHaveLength(1);
    const late = await start(a, { sessionId, mode: 'until_off' });
    expect(late.statusCode).toBe(409);
    expect(late.json().error.code).toBe('location_not_sharing');
    expect((await putFix(a, sessionId, fixAt())).statusCode).toBe(409);
    expect(await state(b)).toEqual({ mine: null, partner: null });
  });

  it('a stop that overtakes its own start leaves a tombstone, so the start arrives stopped', async () => {
    const id = oid();
    expect((await stop(a, `?sessionId=${id}`)).statusCode).toBe(204);
    const late = await start(a, { sessionId: id, mode: 'timed', durationMinutes: 60, fix: fixAt() });
    expect(late.statusCode).toBe(409);
    expect(await t.db.liveLocations.countDocuments({ userId: new ObjectId(a.user.id), live: true })).toBe(0);
  });

  it('a stale stop never ends a newer session; a stop without an id ends every live session', async () => {
    const first = oid();
    expect((await start(a, { sessionId: first, mode: 'until_off' })).statusCode).toBe(201);
    expect((await stop(a, `?sessionId=${first}`)).statusCode).toBe(204);
    const second = oid();
    expect((await start(a, { sessionId: second, mode: 'until_off' })).statusCode).toBe(201);
    expect((await stop(a, `?sessionId=${first}`)).statusCode).toBe(204);
    expect((await state(a)).mine).toMatchObject({ sessionId: second });
    expect((await stop(a)).statusCode).toBe(204);
    expect((await state(a)).mine).toBeNull();
  });

  it('until-off sessions are purged 12 h after the last check-in; a check-in with the same fix refreshes it', async () => {
    const id = oid();
    expect((await start(b, { sessionId: id, mode: 'until_off', fix: fixAt() })).statusCode).toBe(201);
    const doc = await t.db.liveLocations.findOne({ _id: new ObjectId(id) });
    expect(doc!.expiresAt).toBeNull();
    expect(doc!.purgeAt.getTime()).toBe(doc!.lastSeenAt.getTime() + LIVE_LOCATION_IDLE_PURGE_MS);
    t.clock.advance(60_000);
    // the same fix re-sent a minute later (its age grew by the minute; 1 s margin for test time)
    const res = await putFix(b, id, fixAt(undefined, undefined, { ageMs: 63_000 }));
    expect(res.json()).toEqual({ accepted: false });
    const after = await t.db.liveLocations.findOne({ _id: new ObjectId(id) });
    for (const [now, then] of [
      [after!.purgeAt, doc!.purgeAt],
      [after!.lastSeenAt, doc!.lastSeenAt],
    ] as const) {
      expect(now.getTime() - then.getTime()).toBeGreaterThanOrEqual(60_000);
      expect(now.getTime() - then.getTime()).toBeLessThan(61_000);
    }
    expect((await stop(b)).statusCode).toBe(204);
  });

  it('a timed session ends on its own: both GETs show nothing and fixes are refused', async () => {
    const id = oid();
    expect((await start(a, { sessionId: id, mode: 'timed', durationMinutes: 15 })).statusCode).toBe(201);
    t.clock.advance(16 * 60_000);
    expect((await state(a)).mine).toBeNull();
    expect((await state(b)).partner).toBeNull();
    expect((await putFix(a, id, fixAt())).statusCode).toBe(409);
    // an expired session does not block a new one
    expect((await start(a, { sessionId: oid(), mode: 'timed', durationMinutes: 60 })).statusCode).toBe(201);
    expect((await stop(a)).statusCode).toBe(204);
  });
});

describe('live location: validation, limits, indexes, privacy', () => {
  it('validates input; coarse accuracy is clamped; a partner’s SOS cannot be linked', async () => {
    const bad = [
      { sessionId: oid(), mode: 'timed' },
      { sessionId: oid(), mode: 'until_off', durationMinutes: 60 },
      { sessionId: oid(), mode: 'timed', durationMinutes: 5 },
      { sessionId: oid(), mode: 'timed', durationMinutes: 60, sosId: oid() },
      { sessionId: oid(), mode: 'timed', durationMinutes: 60, extra: true },
      { sessionId: 'nope', mode: 'until_off' },
      { sessionId: oid(), mode: 'until_off', fix: fixAt(91, 0) },
      { sessionId: oid(), mode: 'until_off', fix: fixAt(0, 0, { ageMs: 600_001 }) },
      { sessionId: oid(), mode: 'until_off', fix: { ...fixAt(), at: '2026-01-01T00:00:00Z' } },
    ];
    for (const body of bad) expect((await start(c, body)).statusCode, JSON.stringify(body)).toBe(400);

    const sos = await t.app.inject({ method: 'POST', url: `${API}/sos`, headers: bearer(d), payload: {} });
    expect(sos.statusCode).toBe(201);
    const foreign = await start(c, {
      sessionId: oid(),
      mode: 'timed',
      durationMinutes: 120,
      reason: 'panic',
      sosId: sos.json().sos.id,
    });
    expect(foreign.statusCode).toBe(404);

    const id = oid();
    const coarse = await start(c, {
      sessionId: id,
      mode: 'until_off',
      fix: fixAt(1, 1, { accuracyM: 200_000 }),
    });
    expect(coarse.statusCode).toBe(201);
    expect(coarse.json().sharing.fix.accuracyM).toBe(50_000);
    expect((await stop(c)).statusCode).toBe(204);
  });

  it('limits fixes to 12 per user per minute (429 with Retry-After)', async () => {
    const id = oid();
    expect((await start(d, { sessionId: id, mode: 'until_off' })).statusCode).toBe(201);
    for (let i = 0; i < 12; i++) expect((await putFix(d, id, fixAt())).statusCode).toBe(200);
    const limited = await putFix(d, id, fixAt());
    expect(limited.statusCode).toBe(429);
    expect(limited.json().error.code).toBe('rate_limited');
    expect(Number(limited.headers['retry-after'])).toBeGreaterThan(0);
    expect((await stop(d)).statusCode).toBe(204);
  });

  it('limits manual starts to 20 per hour, but never a panic start', async () => {
    const p = await register(t.app, 'Priya');
    await pair(t.app, p, await register(t.app, 'Quinn'));
    const id = oid();
    for (let i = 0; i < 20; i++) {
      expect((await start(p, { sessionId: id, mode: 'until_off' })).statusCode).toBeLessThan(300);
    }
    expect((await start(p, { sessionId: id, mode: 'until_off' })).statusCode).toBe(429);
    const panic = await start(p, { sessionId: id, mode: 'timed', durationMinutes: 120, reason: 'panic' });
    expect(panic.statusCode).toBe(200);
    expect(panic.json().sharing.reason).toBe('panic');
    expect((await stop(p)).statusCode).toBe(204);
  });

  it('has the TTL index and the live-only unique index', async () => {
    const indexes = await t.db.liveLocations.indexes();
    const ttl = indexes.find((i) => i.name === 'purgeAt_ttl');
    expect(ttl?.expireAfterSeconds).toBe(0);
    const unique = indexes.find((i) => i.name === 'coupleId_userId_live_unique');
    expect(unique?.unique).toBe(true);
    expect(unique?.partialFilterExpression).toEqual({ live: true });
  });

  it('never writes coordinates into the event log, and logs redact them', async () => {
    const all = JSON.stringify(await t.db.events.find().toArray());
    expect(all).not.toMatch(/"lat"/);
    expect(all).not.toMatch(/"lng"/);
    for (const p of ['fix', '*.fix', 'location', '*.location', 'lat', '*.lat', 'lng', '*.lng']) {
      expect(REDACT_PATHS).toContain(p);
    }
  });

  it('documents the routes in OpenAPI', async () => {
    const res = await t.app.inject({ method: 'GET', url: '/docs/json' });
    const paths = Object.keys(res.json().paths);
    for (const p of [
      '/api/v1/location/sharing',
      '/api/v1/location/sharing/fix',
      '/api/v1/sos/{id}/delivered',
      '/api/v1/sos/{id}/resolve',
      '/api/v1/sos/test',
    ]) {
      expect(paths, p).toContain(p);
    }
  });

  it('the data export holds only my own current session, and nothing after a stop', async () => {
    expect((await stop(a)).statusCode).toBe(204);
    const mine = oid();
    // a panic start skips the start budget used up by the rate-limit test above
    const res = await start(a, {
      sessionId: mine,
      mode: 'timed',
      durationMinutes: 120,
      reason: 'panic',
      fix: fixAt(),
    });
    expect(res.statusCode).toBeLessThan(300);
    const exportOf = async (s: Session) => {
      const r = await t.app.inject({ method: 'GET', url: `${API}/me/export`, headers: bearer(s) });
      expect(r.statusCode).toBe(200);
      return r.json<{ liveLocation: { sessionId: string } | null }>();
    };
    expect((await exportOf(a)).liveLocation?.sessionId).toBe(mine);
    expect((await exportOf(b)).liveLocation).toBeNull(); // never the partner's position
    expect((await stop(a)).statusCode).toBe(204);
    expect((await exportOf(a)).liveLocation).toBeNull();
  });
});

describe('live location: sign-out, unlink and account deletion', () => {
  it('signing out deletes the sharer’s session and tells the partner', async () => {
    const x = await register(t.app, 'Xavi');
    const y = await register(t.app, 'Yara');
    const xyCouple = await pair(t.app, x, y);
    const id = oid();
    expect((await start(x, { sessionId: id, mode: 'until_off', fix: fixAt() })).statusCode).toBe(201);
    const out = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/logout`,
      headers: bearer(x),
      payload: { refreshToken: x.tokens.refreshToken },
    });
    expect(out.statusCode).toBe(204);
    expect(await t.db.liveLocations.countDocuments({ userId: new ObjectId(x.user.id) })).toBe(0);
    const stopped = await t.db.events
      .find({ coupleId: new ObjectId(xyCouple), type: 'location.stopped' })
      .toArray();
    expect(stopped.map((e) => e.payload.reason)).toEqual(['signed_out']);
    expect((await state(y)).partner).toBeNull();
  });

  it('unlinking purges both members’ sessions; stop still answers 204 without a couple', async () => {
    const x = await register(t.app, 'Xena');
    const y = await register(t.app, 'Yuki');
    const xy = new ObjectId(await pair(t.app, x, y));
    expect((await start(x, { sessionId: oid(), mode: 'until_off', fix: fixAt() })).statusCode).toBe(201);
    expect((await start(y, { sessionId: oid(), mode: 'timed', durationMinutes: 60 })).statusCode).toBe(201);
    const unlink = await t.app.inject({
      method: 'POST',
      url: `${API}/couple/unlink`,
      headers: bearer(x),
      payload: { confirm: true },
    });
    expect(unlink.statusCode).toBe(204);
    expect(await t.db.liveLocations.countDocuments({ coupleId: xy })).toBe(0);
    expect((await stop(x)).statusCode).toBe(204);
    expect((await stop(y, `?sessionId=${oid()}`)).statusCode).toBe(204);
    expect(await t.db.liveLocations.countDocuments({ userId: new ObjectId(y.user.id) })).toBe(0);
  });

  it('deleting the account removes the session', async () => {
    const x = await register(t.app, 'Xiu');
    const y = await register(t.app, 'Yves');
    await pair(t.app, x, y);
    expect((await start(x, { sessionId: oid(), mode: 'until_off', fix: fixAt() })).statusCode).toBe(201);
    const del = await t.app.inject({
      method: 'DELETE',
      url: `${API}/me`,
      headers: bearer(x),
      payload: { password: 'correct-horse-battery' },
    });
    expect(del.statusCode).toBe(204);
    expect(await t.db.liveLocations.countDocuments({ userId: new ObjectId(x.user.id) })).toBe(0);
  });
});
