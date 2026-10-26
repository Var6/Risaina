import { randomUUID } from 'node:crypto';
import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { toSosDto } from '../src/modules/shared/dto.js';
import { SOS_RAISE_LEASE_MS } from '../src/modules/sos/service.js';
import {
  SOS_REPUSH_MAX,
  SOS_REPUSH_MIN_GAP_MS,
  SOS_REPUSH_WINDOW_MS,
  SosRepushWorker,
} from '../src/workers/sos-repush.js';
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
let repush: SosRepushWorker;

beforeAll(async () => {
  push = new FakePushService();
  t = await createTestApp({ push, envOverrides: { ACCESS_TOKEN_TTL_SECONDS: '172800' } });
  repush = new SosRepushWorker(t.db, push, t.clock.now, t.ctx.logger, 1_000_000);
});
afterAll(async () => {
  await t.close();
});

const post = (s: Session, path: string, payload?: Record<string, unknown>) =>
  t.app.inject({ method: 'POST', url: `${API}${path}`, headers: bearer(s), ...(payload ? { payload } : {}) });
const oid = () => new ObjectId().toHexString();

/** A fresh paired couple whose recipient has a device token (every test gets its own budget). */
async function couple(tag: string): Promise<{ sender: Session; recipient: Session; coupleId: string }> {
  const sender = await register(t.app, `S${tag}`);
  const recipient = await register(t.app, `R${tag}`);
  const coupleId = await pair(t.app, sender, recipient);
  for (const [s, token] of [
    [sender, `tok-s-${tag}`],
    [recipient, `tok-r-${tag}`],
  ] as const) {
    const res = await t.app.inject({
      method: 'PUT',
      url: `${API}/devices`,
      headers: bearer(s),
      payload: { deviceId: `dev-${token}`, platform: 'android', token, appVersion: '1.0.0', model: 'realme' },
    });
    expect(res.statusCode).toBe(204);
  }
  await t.outbox.runOnce();
  push.calls.length = 0;
  return { sender, recipient, coupleId };
}

describe('SOS: panic flag and retries', () => {
  it('stores panic only when true; old documents read as not panic', async () => {
    const { sender } = await couple('p1');
    const panic = await post(sender, '/sos', { panic: true, message: "I'm in trouble" });
    expect(panic.statusCode).toBe(201);
    expect(panic.json().sos).toMatchObject({
      panic: true,
      deliveredAt: null,
      resolvedAt: null,
      delivery: null,
    });
    const plain = await post(sender, '/sos', {});
    expect(plain.json().sos.panic).toBe(false);
    const stored = await t.db.sos.findOne({ _id: new ObjectId(plain.json().sos.id) });
    expect(stored).not.toHaveProperty('panic');
    expect(stored).toMatchObject({
      raisedSeq: expect.any(Number),
      raisedEventId: expect.any(ObjectId),
      pushCount: 0,
    });
    const legacy = toSosDto({
      _id: new ObjectId(),
      coupleId: new ObjectId(),
      senderId: new ObjectId(),
      recipientId: new ObjectId(),
      message: null,
      createdAt: new Date(),
      acknowledgedAt: null,
    });
    expect(legacy).toMatchObject({ panic: false, deliveredAt: null, resolvedAt: null, delivery: null });
  });

  it('a retry with the same idempotencyKey returns the first SOS, emits nothing and is not counted', async () => {
    const { sender } = await couple('p2');
    const key = randomUUID();
    const first = await post(sender, '/sos', { message: 'help', idempotencyKey: key });
    expect(first.statusCode).toBe(201);
    for (let i = 0; i < 4; i++) {
      const again = await post(sender, '/sos', { message: 'help', idempotencyKey: key });
      expect(again.statusCode).toBe(201);
      expect(again.json().sos.id).toBe(first.json().sos.id);
    }
    const raised = await t.db.events.countDocuments({
      type: 'sos.raised',
      'payload.sos.id': first.json().sos.id,
    });
    expect(raised).toBe(1);
    // two more distinct alerts still fit in the 3-per-10-minutes budget
    expect((await post(sender, '/sos', {})).statusCode).toBe(201);
    expect((await post(sender, '/sos', {})).statusCode).toBe(201);
    expect((await post(sender, '/sos', {})).statusCode).toBe(429);
    expect((await post(sender, '/sos', { idempotencyKey: 'x'.repeat(65) })).statusCode).toBe(400);
  });
});

describe('SOS: an alarm event that never went out', () => {
  const raisedCount = (sosId: string) =>
    t.db.events.countDocuments({ type: 'sos.raised', 'payload.sos.id': sosId });

  it('a failed emit is raised by the retry with the same key, exactly once', async () => {
    const { sender } = await couple('e1');
    const key = randomUUID();
    const emit = t.ctx.events.emit.bind(t.ctx.events);
    let failNext = true;
    const spy = vi.spyOn(t.ctx.events, 'emit').mockImplementation(async (input) => {
      if (failNext && input.type === 'sos.raised') {
        failNext = false;
        throw new Error('primary stepped down');
      }
      return emit(input);
    });
    try {
      const first = await post(sender, '/sos', { message: 'help', idempotencyKey: key });
      expect(first.statusCode).toBe(500);
      const stored = await t.db.sos.findOne({ senderId: new ObjectId(sender.user.id), idempotencyKey: key });
      expect(stored).not.toBeNull();
      expect(stored).not.toHaveProperty('raisedSeq');
      expect(await raisedCount(stored!._id.toHexString())).toBe(0);

      // The client retries with the same key: same SOS, and now the event (and the push) go out.
      const again = await post(sender, '/sos', { message: 'help', idempotencyKey: key });
      expect(again.statusCode).toBe(201);
      expect(again.json().sos.id).toBe(stored!._id.toHexString());
      expect(await raisedCount(stored!._id.toHexString())).toBe(1);
      const raised = await t.db.sos.findOne({ _id: stored!._id });
      expect(raised).toMatchObject({ raisedSeq: expect.any(Number) });
      expect(raised).not.toHaveProperty('raiseLeaseUntil');

      // Further retries change nothing.
      expect((await post(sender, '/sos', { message: 'help', idempotencyKey: key })).statusCode).toBe(201);
      expect(await raisedCount(stored!._id.toHexString())).toBe(1);
    } finally {
      spy.mockRestore();
    }
  });

  it('a retry while the first request may still be emitting is told to retry; after the lease it raises it', async () => {
    const { sender, recipient, coupleId } = await couple('e2');
    const key = randomUUID();
    const id = new ObjectId();
    // What a request that died (a redeploy) between its insert and its event leaves behind.
    await t.db.sos.insertOne({
      _id: id,
      coupleId: new ObjectId(coupleId),
      senderId: new ObjectId(sender.user.id),
      recipientId: new ObjectId(recipient.user.id),
      message: null,
      createdAt: t.clock.now(),
      acknowledgedAt: null,
      idempotencyKey: key,
      raiseLeaseUntil: new Date(t.clock.now().getTime() + SOS_RAISE_LEASE_MS),
    });
    const busy = await post(sender, '/sos', { idempotencyKey: key });
    expect(busy.statusCode).toBe(503);
    expect(busy.headers['retry-after']).toBe('5');
    expect(await raisedCount(id.toHexString())).toBe(0);

    t.clock.advance(SOS_RAISE_LEASE_MS + 1);
    const later = await post(sender, '/sos', { idempotencyKey: key });
    expect(later.statusCode).toBe(201);
    expect(later.json().sos.id).toBe(id.toHexString());
    expect(await raisedCount(id.toHexString())).toBe(1);
  });
});

describe('SOS: delivered receipt', () => {
  it('only the recipient confirms; once; stores device state; tells the sender', async () => {
    const { sender, recipient } = await couple('d1');
    const sos = (await post(sender, '/sos', {})).json().sos;
    expect((await post(sender, `/sos/${sos.id}/delivered`, {})).statusCode).toBe(403);
    expect((await post(recipient, `/sos/${oid()}/delivered`, {})).statusCode).toBe(404);
    const res = await post(recipient, `/sos/${sos.id}/delivered`, {
      priority: 'high',
      originalPriority: 'high',
      latencyMs: 850,
      alarmMuted: true,
      alarmVolume: 0,
      ringerMode: 'silent',
      interruptionFilter: 'all',
      notificationsEnabled: false,
      canUseFullScreenIntent: true,
      fgsStarted: true,
      futureField: 'ignored',
    });
    expect(res.statusCode).toBe(200);
    expect(res.json().sos).toMatchObject({
      deliveredAt: expect.any(String),
      delivery: {
        alarmMuted: true,
        fgsStarted: true,
        notificationsEnabled: false,
        interruptionFilter: 'all',
      },
    });
    const stored = await t.db.sos.findOne({ _id: new ObjectId(sos.id) });
    expect(stored!.delivery).toMatchObject({ ringerMode: 'silent', latencyMs: 850 });
    expect(stored!.delivery).not.toHaveProperty('futureField');
    t.clock.advance(1000);
    const again = await post(recipient, `/sos/${sos.id}/delivered`, {});
    expect(again.json().sos.deliveredAt).toBe(res.json().sos.deliveredAt);
    const events = await t.db.events.find({ type: 'sos.delivered', 'payload.sosId': sos.id }).toArray();
    expect(events).toHaveLength(1);
    expect(events[0]!.payload).toEqual({ sosId: sos.id, deliveredAt: res.json().sos.deliveredAt });
    expect(events[0]!.actorId.toHexString()).toBe(recipient.user.id);
    // an empty receipt (no body) works too
    const second = (await post(sender, '/sos', {})).json().sos;
    expect((await post(recipient, `/sos/${second.id}/delivered`)).statusCode).toBe(200);
  });
});

describe('SOS: "I\'m safe now"', () => {
  it('only the sender resolves; once; it ends the panic sharing linked to it', async () => {
    const { sender, recipient } = await couple('r1');
    const sos = (await post(sender, '/sos', { panic: true })).json().sos;
    const sessionId = oid();
    const share = await post(sender, '/location/sharing', {
      sessionId,
      mode: 'timed',
      durationMinutes: 120,
      reason: 'panic',
      sosId: sos.id,
      fix: { lat: 1, lng: 2, accuracyM: 10, ageMs: 0, batteryPct: 40 },
    });
    expect(share.statusCode).toBe(201);
    expect(share.json().sharing).toMatchObject({ reason: 'panic', sosId: sos.id });

    expect((await post(recipient, `/sos/${sos.id}/resolve`)).statusCode).toBe(403);
    const res = await post(sender, `/sos/${sos.id}/resolve`);
    expect(res.statusCode).toBe(200);
    const resolvedAt = res.json().sos.resolvedAt;
    expect(resolvedAt).toEqual(expect.any(String));
    t.clock.advance(1000);
    expect((await post(sender, `/sos/${sos.id}/resolve`)).json().sos.resolvedAt).toBe(resolvedAt);
    expect(await t.db.events.countDocuments({ type: 'sos.resolved', 'payload.sosId': sos.id })).toBe(1);
    const doc = await t.db.liveLocations.findOne({ _id: new ObjectId(sessionId) });
    expect(doc).toMatchObject({ live: false, fix: null });
    const stopped = await t.db.events
      .find({ type: 'location.stopped', 'payload.sessionId': sessionId })
      .toArray();
    expect(stopped.map((e) => e.payload.reason)).toEqual(['resolved']);
  });

  it('panic merges into a manual session (never shortening it), so "I\'m safe now" ends that sharing too', async () => {
    const { sender } = await couple('r2');
    const manual = oid();
    expect(
      (await post(sender, '/location/sharing', { sessionId: manual, mode: 'until_off' })).statusCode,
    ).toBe(201);
    const sos = (await post(sender, '/sos', { panic: true })).json().sos;
    const merged = await post(sender, '/location/sharing', {
      sessionId: oid(),
      mode: 'timed',
      durationMinutes: 120,
      reason: 'panic',
      sosId: sos.id,
    });
    expect(merged.statusCode).toBe(200);
    expect(merged.json().sharing).toMatchObject({ sessionId: manual, reason: 'panic', expiresAt: null });
    // a later manual start never downgrades the panic
    const manualAgain = await post(sender, '/location/sharing', {
      sessionId: manual,
      mode: 'timed',
      durationMinutes: 60,
    });
    expect(manualAgain.json().sharing).toMatchObject({ reason: 'panic', expiresAt: null });
    expect((await post(sender, `/sos/${sos.id}/resolve`)).statusCode).toBe(200);
    expect(await t.db.liveLocations.countDocuments({ _id: new ObjectId(manual), live: true })).toBe(0);
  });

  it('panic sharing can start before the SOS exists and be linked to it later', async () => {
    const { sender } = await couple('r3');
    const sessionId = oid();
    const early = await post(sender, '/location/sharing', {
      sessionId,
      mode: 'timed',
      durationMinutes: 120,
      reason: 'panic',
    });
    expect(early.statusCode).toBe(201);
    expect(early.json().sharing.sosId).toBeNull();
    const sos = (await post(sender, '/sos', { panic: true })).json().sos;
    const linked = await post(sender, '/location/sharing', {
      sessionId,
      mode: 'timed',
      durationMinutes: 120,
      reason: 'panic',
      sosId: sos.id,
    });
    expect(linked.statusCode).toBe(200);
    expect(linked.json().sharing.sosId).toBe(sos.id);
  });
});

describe('SOS: server test alarm', () => {
  it('pushes sos.test to my own phones only, writes nothing, and is limited to 3 per 10 minutes', async () => {
    const { sender } = await couple('t1');
    const events = await t.db.events.countDocuments();
    const sosDocs = await t.db.sos.countDocuments();
    const res = await post(sender, '/sos/test', { delaySeconds: 0 });
    expect(res.statusCode).toBe(202);
    expect(res.json()).toEqual({ queued: true, devices: 1 });
    expect(push.calls).toHaveLength(1);
    expect(push.calls[0]!.tokens).toEqual(['tok-s-t1']);
    expect(push.calls[0]!.data.type).toBe('sos.test');
    expect(await t.db.events.countDocuments()).toBe(events);
    expect(await t.db.sos.countDocuments()).toBe(sosDocs);
    expect((await post(sender, '/sos/test', { delaySeconds: 61 })).statusCode).toBe(400);
    expect((await post(sender, '/sos/test', { delaySeconds: 0 })).statusCode).toBe(202);
    expect((await post(sender, '/sos/test', { delaySeconds: 0 })).statusCode).toBe(202);
    expect((await post(sender, '/sos/test', { delaySeconds: 0 })).statusCode).toBe(429);
  });
});

describe('SosRepushWorker', () => {
  // Alerts from the earlier tests share this database: mark them delivered so each test only sees its own.
  beforeEach(async () => {
    await t.db.sos.updateMany({ deliveredAt: null }, { $set: { deliveredAt: t.clock.now() } });
  });

  it('pushes an undelivered SOS again about once a minute, at most 6 times, with sosId and the original seq', async () => {
    const { sender, recipient } = await couple('w1');
    const sos = (await post(sender, '/sos', {})).json().sos;
    await t.outbox.runOnce();
    push.calls.length = 0;
    expect(await repush.runOnce()).toBe(0); // the outbox just pushed it
    const stored = await t.db.sos.findOne({ _id: new ObjectId(sos.id) });
    for (let i = 1; i <= SOS_REPUSH_MAX; i++) {
      t.clock.advance(SOS_REPUSH_MIN_GAP_MS + 1000);
      expect(await repush.runOnce(), `round ${i}`).toBe(1);
      expect(await repush.runOnce(), `round ${i} again`).toBe(0); // not before the gap
    }
    expect(push.calls).toHaveLength(SOS_REPUSH_MAX);
    expect(push.calls[0]!.tokens).toEqual([`tok-r-w1`]);
    expect(push.calls[0]!.data).toEqual({
      type: 'sos.raised',
      eventId: stored!.raisedEventId!.toHexString(),
      coupleId: sos.coupleId,
      seq: String(stored!.raisedSeq),
      sosId: sos.id,
      at: String(new Date(sos.createdAt).getTime()),
    });
    t.clock.advance(SOS_REPUSH_MIN_GAP_MS + 1000);
    expect(await repush.runOnce()).toBe(0); // maximum reached
    expect(recipient.user.id).toBeTruthy();
  });

  it('stops once delivered, acknowledged, resolved, too old, or the couple is gone', async () => {
    const delivered = await couple('w2');
    const acked = await couple('w3');
    const resolved = await couple('w4');
    const unlinked = await couple('w5');
    const ids = {
      delivered: (await post(delivered.sender, '/sos', {})).json().sos.id,
      acked: (await post(acked.sender, '/sos', {})).json().sos.id,
      resolved: (await post(resolved.sender, '/sos', {})).json().sos.id,
      unlinked: (await post(unlinked.sender, '/sos', {})).json().sos.id,
    };
    expect((await post(delivered.recipient, `/sos/${ids.delivered}/delivered`, {})).statusCode).toBe(200);
    expect((await post(acked.recipient, `/sos/${ids.acked}/ack`, {})).statusCode).toBe(200);
    expect((await post(resolved.sender, `/sos/${ids.resolved}/resolve`)).statusCode).toBe(200);
    expect((await post(unlinked.sender, '/couple/unlink', { confirm: true })).statusCode).toBe(204);
    await t.outbox.runOnce();
    push.calls.length = 0;
    t.clock.advance(SOS_REPUSH_MIN_GAP_MS + 1000);
    expect(await repush.runOnce()).toBe(0);
    expect(push.calls).toHaveLength(0);

    const old = await couple('w6');
    await post(old.sender, '/sos', {});
    t.clock.advance(SOS_REPUSH_WINDOW_MS + 1000);
    expect(await repush.runOnce()).toBe(0);
  });

  it('does nothing when push is disabled', async () => {
    const off = new SosRepushWorker(
      t.db,
      {
        enabled: false,
        provider: 'disabled',
        send: async () => {
          throw new Error('never');
        },
      },
      t.clock.now,
      t.ctx.logger,
    );
    expect(await off.runOnce()).toBe(0);
  });
});
