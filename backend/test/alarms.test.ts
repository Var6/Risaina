import { randomUUID } from 'node:crypto';
import { MongoServerError, ObjectId } from 'mongodb';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import type { AlarmDoc } from '../src/db/types.js';
import { VALIDATORS } from '../src/db/validators.js';
import { nextSkips, notifyFor, scheduleIssues } from '../src/modules/alarms/service.js';
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
  t = await createTestApp({
    push,
    // tests move the clock forward by days (rings that passed, old skips): keep the sessions valid
    envOverrides: {
      RATE_LIMIT_ALARM_WRITES_PER_MIN: '10000',
      ACCESS_TOKEN_TTL_SECONDS: String(90 * 24 * 3600),
    },
  });
  a = await register(t.app, 'Asha');
  b = await register(t.app, 'Ravi');
  c = await register(t.app, 'Cyra');
  const d = await register(t.app, 'Dev');
  coupleId = await pair(t.app, a, b);
  await pair(t.app, c, d);
});
afterAll(async () => {
  await t.close();
});
// Each test makes its own alarms; clearing them keeps every couple under the 10-alarm cap.
afterEach(async () => {
  await t.db.alarms.deleteMany({});
});

type Member = { userId: string; ring: boolean; skips: string[]; ready: boolean | null } & Record<
  string,
  unknown
>;
type Alarm = { id: string; version: number; members: Member[] } & Record<string, unknown>;

const req = (
  s: Session,
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  url: string,
  payload?: object,
) => t.app.inject({ method, url: `${API}${url}`, headers: bearer(s), ...(payload ? { payload } : {}) });
const create = (s: Session, payload: object) => req(s, 'POST', '/alarms', payload);
const patch = (s: Session, id: string, payload: object) => req(s, 'PATCH', `/alarms/${id}`, payload);
const mine = (s: Session, id: string, payload: object) => req(s, 'PUT', `/alarms/${id}/me`, payload);
const report = (s: Session, items: object[]) => req(s, 'POST', '/alarms/report', { items });
const wake = (s: Session, id: string, payload: object) => req(s, 'POST', `/alarms/${id}/wake`, payload);
const member = (alarm: Alarm, s: Session) => alarm.members.find((m) => m.userId === s.user.id)!;
const lastEvent = (type: string, alarmId: string) =>
  t.db.events.findOne(
    { type: type as 'alarm.updated', $or: [{ 'payload.alarm.id': alarmId }, { 'payload.alarmId': alarmId }] },
    { sort: { seq: -1 } },
  );
const countEvents = (alarmId: string) =>
  t.db.events.countDocuments({ $or: [{ 'payload.alarm.id': alarmId }, { 'payload.alarmId': alarmId }] });

const weekdays = { label: 'Gym', time: '06:30', timeZone: 'Asia/Kolkata', days: [5, 1, 3] };

/** A one-off ring `ms` from now, on a whole minute, with the matching HH:mm in UTC. */
function onceIn(ms: number): { onceAt: string; time: string } {
  const at = new Date(Math.floor((t.clock.now().getTime() + ms) / 60_000) * 60_000);
  return { onceAt: at.toISOString(), time: at.toISOString().slice(11, 16) };
}
const HOUR = 3600_000;
const DAY = 24 * HOUR;

describe('alarms: create and list', () => {
  it('creates a repeating alarm that rings on both phones; GET lists it; alarm.created with notify false', async () => {
    const res = await create(a, weekdays);
    expect(res.statusCode).toBe(201);
    const alarm = res.json().alarm as Alarm;
    const fresh = {
      skips: [],
      ready: null,
      reason: null,
      armedFor: null,
      reportedAt: null,
      lastWake: null,
      ring: true,
    };
    expect(alarm).toEqual({
      id: expect.stringMatching(/^[a-f0-9]{24}$/),
      coupleId,
      createdBy: a.user.id,
      updatedBy: a.user.id,
      mode: 'together',
      label: 'Gym',
      time: '06:30',
      timeZone: 'Asia/Kolkata',
      days: [1, 3, 5],
      onceAt: null,
      enabled: true,
      snoozeMinutes: 10,
      version: 1,
      createdAt: expect.any(String),
      updatedAt: expect.any(String),
      members: expect.arrayContaining([
        { userId: a.user.id, ...fresh },
        { userId: b.user.id, ...fresh },
      ]),
    });
    expect(alarm.members).toHaveLength(2);
    const ev = await lastEvent('alarm.created', alarm.id);
    // Ravi's phone has never reported for this alarm (it may run an older app): no high push.
    expect(ev!.payload).toEqual({ alarm, notify: false });
    const list = await req(b, 'GET', '/alarms');
    expect(list.statusCode).toBe(200);
    expect(list.json().items.map((x: Alarm) => x.id)).toContain(alarm.id);
  });

  it('ringOnMyPhone false turns only the creator off; label blank is null; snooze and zone as sent', async () => {
    const res = await create(a, {
      label: '   ',
      time: '07:00',
      timeZone: 'europe/london',
      days: [6, 7],
      ringOnMyPhone: false,
      snoozeMinutes: 5,
    });
    const alarm = res.json().alarm as Alarm;
    expect(alarm).toMatchObject({ label: null, timeZone: 'Europe/London', snoozeMinutes: 5 });
    expect(member(alarm, a).ring).toBe(false);
    expect(member(alarm, b).ring).toBe(true);
  });

  it('creates a one-off alarm at onceAt (the time in its zone)', async () => {
    const once = onceIn(2 * DAY);
    const res = await create(a, { time: once.time, timeZone: 'UTC', days: [], onceAt: once.onceAt });
    expect(res.statusCode).toBe(201);
    expect(res.json().alarm).toMatchObject({ days: [], onceAt: once.onceAt, time: once.time });
    // anchored in Kolkata (UTC+05:30, no DST), written with its offset: stored as the same instant
    const at = new Date(once.onceAt);
    const local = new Date(at.getTime() + 330 * 60_000).toISOString();
    const kolkata = await create(a, {
      time: local.slice(11, 16),
      timeZone: 'Asia/Kolkata',
      days: [],
      onceAt: `${local.slice(0, 19)}+05:30`,
    });
    expect(kolkata.statusCode).toBe(201);
    expect(kolkata.json().alarm.onceAt).toBe(once.onceAt);
  });
});

describe('alarms: validation', () => {
  it.each([
    ['a bad time', { ...weekdays, time: '6:30' }],
    ['a fixed-offset zone', { ...weekdays, timeZone: '+05:30' }],
    ['an unknown zone', { ...weekdays, timeZone: 'Mars/Olympus' }],
    ['duplicate days', { ...weekdays, days: [1, 1] }],
    ['day 0', { ...weekdays, days: [0] }],
    ['day 8', { ...weekdays, days: [8] }],
    ['missing days', { label: 'x', time: '06:30', timeZone: 'UTC' }],
    ['no days and no onceAt', { ...weekdays, days: [] }],
    ['a label over 40', { ...weekdays, label: 'x'.repeat(41) }],
    ['snooze 0', { ...weekdays, snoozeMinutes: 0 }],
    ['snooze 31', { ...weekdays, snoozeMinutes: 31 }],
    ['an unknown mode', { ...weekdays, mode: 'local' }],
    ['an unknown key', { ...weekdays, members: [] }],
  ])('POST rejects %s (400)', async (_, payload) => {
    const res = await create(a, payload);
    expect(res.statusCode, res.body).toBe(400);
    expect(res.json().error.code).toBe('validation_failed');
  });

  it('checks onceAt: only for one-off alarms, within 366 days, at `time` in the zone, with a zone', async () => {
    const once = onceIn(DAY);
    const base = { time: once.time, timeZone: 'UTC', days: [] };
    expect((await create(a, { ...base, days: [1], onceAt: once.onceAt })).statusCode).toBe(400);
    const past = onceIn(-5 * 60_000);
    expect((await create(a, { ...base, time: past.time, onceAt: past.onceAt })).statusCode).toBe(400);
    const far = onceIn(367 * DAY);
    expect((await create(a, { ...base, time: far.time, onceAt: far.onceAt })).statusCode).toBe(400);
    const wrong = await create(a, {
      ...base,
      time: once.time === '00:00' ? '00:01' : '00:00',
      onceAt: once.onceAt,
    });
    expect(wrong.statusCode).toBe(400);
    expect(wrong.json().error.details[0]).toMatchObject({ path: '/onceAt' });
    const noZone = once.onceAt.replace('.000Z', '');
    expect((await create(a, { ...base, onceAt: noZone })).statusCode).toBe(400);
    expect((await create(a, { ...base, onceAt: 'tomorrow' })).statusCode).toBe(400);
  });

  it('scheduleIssues (pure): anchor-zone match, DST and the window', () => {
    const now = new Date('2026-10-29T00:00:00Z');
    const s = { time: '06:30', timeZone: 'Asia/Kolkata', days: [], onceAt: new Date('2026-11-02T01:00:00Z') };
    expect(scheduleIssues(s, now, true)).toEqual([]);
    expect(scheduleIssues({ ...s, timeZone: 'Europe/London' }, now, true)).toHaveLength(1);
    // London: 06:30 is GMT after the clocks went back on 25 Oct 2026
    expect(
      scheduleIssues(
        { ...s, timeZone: 'Europe/London', onceAt: new Date('2026-11-02T06:30:00Z') },
        now,
        true,
      ),
    ).toEqual([]);
    // a past one-off alarm stays valid when the window is not being checked (e.g. a rename)
    expect(scheduleIssues(s, new Date('2027-01-01T00:00:00Z'), false)).toEqual([]);
    expect(scheduleIssues(s, new Date('2027-01-01T00:00:00Z'), true)).toHaveLength(1);
    expect(scheduleIssues({ ...s, days: [1, 2], onceAt: null }, now, true)).toEqual([]);
  });

  it('caps a couple at 10 alarms (409 conflict)', async () => {
    const x = await register(t.app, 'Cap1');
    const y = await register(t.app, 'Cap2');
    await pair(t.app, x, y);
    for (let i = 0; i < 10; i++) expect((await create(x, weekdays)).statusCode).toBe(201);
    const over = await create(y, weekdays);
    expect(over.statusCode).toBe(409);
    expect(over.json().error.code).toBe('conflict');
    expect((await req(y, 'GET', '/alarms')).json().items).toHaveLength(10);
  });
});

describe('alarms: PATCH', () => {
  it('needs expectedVersion; a stale one is 409 with the current alarm; changes say what changed', async () => {
    const alarm = (await create(a, weekdays)).json().alarm as Alarm;
    expect((await patch(b, alarm.id, { label: 'No version' })).statusCode).toBe(400);
    const edited = await patch(b, alarm.id, { expectedVersion: 1, time: '06:00', days: [1, 2, 3, 4, 5] });
    expect(edited.statusCode).toBe(200);
    const v2 = edited.json().alarm;
    expect(v2).toMatchObject({ version: 2, time: '06:00', days: [1, 2, 3, 4, 5], updatedBy: b.user.id });
    expect((await lastEvent('alarm.updated', alarm.id))!.payload).toEqual({
      alarm: v2,
      change: 'edited',
      notify: false,
    });
    const stale = await patch(a, alarm.id, { expectedVersion: 1, label: 'Stale' });
    expect(stale.statusCode).toBe(409);
    expect(stale.json().error.code).toBe('version_conflict');
    expect(stale.json().error.details.alarm).toEqual(v2);

    const off = await patch(a, alarm.id, { expectedVersion: 2, enabled: false });
    expect(off.json().alarm).toMatchObject({ enabled: false, version: 3 });
    expect((await lastEvent('alarm.updated', alarm.id))!.payload).toMatchObject({ change: 'enabled' });
    const both = await patch(a, alarm.id, { expectedVersion: 3, enabled: true, label: 'Run' });
    expect((await lastEvent('alarm.updated', alarm.id))!.payload).toMatchObject({ change: 'edited' });
    expect(both.json().alarm.version).toBe(4);
  });

  it('switching between repeating and one-off keeps the schedule rules', async () => {
    const alarm = (await create(a, weekdays)).json().alarm as Alarm;
    expect((await patch(a, alarm.id, { expectedVersion: 1, days: [] })).statusCode).toBe(400);
    const once = onceIn(3 * DAY);
    const toOnce = await patch(a, alarm.id, {
      expectedVersion: 1,
      days: [],
      onceAt: once.onceAt,
      time: once.time,
      timeZone: 'UTC',
    });
    expect(toOnce.statusCode).toBe(200);
    expect(toOnce.json().alarm).toMatchObject({ days: [], onceAt: once.onceAt });
    // changing only the time would move it away from onceAt
    const otherTime = once.time === '00:00' ? '00:01' : '00:00';
    expect((await patch(a, alarm.id, { expectedVersion: 2, time: otherTime })).statusCode).toBe(400);
    expect((await patch(a, alarm.id, { expectedVersion: 2, days: [1] })).statusCode).toBe(400);
    const back = await patch(a, alarm.id, { expectedVersion: 2, days: [1], onceAt: null });
    expect(back.json().alarm).toMatchObject({ days: [1], onceAt: null, version: 3 });
  });

  it('a one-off alarm that already rang can still be renamed', async () => {
    const once = onceIn(HOUR);
    const alarm = (
      await create(a, { time: once.time, timeZone: 'UTC', days: [], onceAt: once.onceAt })
    ).json().alarm as Alarm;
    t.clock.advance(2 * HOUR);
    const res = await patch(a, alarm.id, { expectedVersion: 1, label: 'Done' });
    expect(res.statusCode).toBe(200);
    expect((await patch(a, alarm.id, { expectedVersion: 2, onceAt: once.onceAt })).statusCode).toBe(400);
  });
});

describe('alarms: notify (D3)', () => {
  it('is true only for a ringing, enabled recipient whose phone has reported, on creates and edits', async () => {
    const alarm = (await create(a, weekdays)).json().alarm as Alarm;
    expect((await lastEvent('alarm.created', alarm.id))!.payload.notify).toBe(false); // Ravi never reported

    // Ravi's phone (a new build) reports: a member change, never notifying
    expect(
      (await report(b, [{ alarmId: alarm.id, ready: true, reason: null, armedFor: null }])).statusCode,
    ).toBe(204);
    let ev = await lastEvent('alarm.updated', alarm.id);
    expect(ev!.payload).toMatchObject({ change: 'member', notify: false });
    expect(ev!.actorId.toHexString()).toBe(b.user.id);
    let version = (ev!.payload.alarm as Alarm).version;

    // Asha edits: Ravi rings, it is enabled, his phone reported → notify
    version = (await patch(a, alarm.id, { expectedVersion: version, time: '06:45' })).json().alarm.version;
    expect((await lastEvent('alarm.updated', alarm.id))!.payload).toMatchObject({
      change: 'edited',
      notify: true,
    });
    // turned off → no notice; turned on → notice
    version = (await patch(a, alarm.id, { expectedVersion: version, enabled: false })).json().alarm.version;
    expect((await lastEvent('alarm.updated', alarm.id))!.payload).toMatchObject({
      change: 'enabled',
      notify: false,
    });
    version = (await patch(a, alarm.id, { expectedVersion: version, enabled: true })).json().alarm.version;
    expect((await lastEvent('alarm.updated', alarm.id))!.payload).toMatchObject({
      change: 'enabled',
      notify: true,
    });

    // Ravi says "not on my phone": his own change does not notify, and edits stop notifying him
    version = (await mine(b, alarm.id, { ring: false })).json().alarm.version;
    expect((await lastEvent('alarm.updated', alarm.id))!.payload).toMatchObject({
      change: 'member',
      notify: false,
    });
    version = (await patch(a, alarm.id, { expectedVersion: version, label: 'Quiet' })).json().alarm.version;
    expect((await lastEvent('alarm.updated', alarm.id))!.payload).toMatchObject({ notify: false });

    // back on, and a not-ready report still counts as "runs a build that shows the notice"
    await mine(b, alarm.id, { ring: true });
    await report(b, [{ alarmId: alarm.id, ready: false, reason: 'exact_alarms_off', armedFor: null }]);
    version = (await req(a, 'GET', '/alarms')).json().items.find((x: Alarm) => x.id === alarm.id).version;
    await patch(a, alarm.id, { expectedVersion: version, label: 'Loud' });
    ev = await lastEvent('alarm.updated', alarm.id);
    expect(ev!.payload).toMatchObject({ change: 'edited', notify: true });

    // Ravi edits: the recipient is Asha, whose phone never reported → no notice
    version = (ev!.payload.alarm as Alarm).version;
    await patch(b, alarm.id, { expectedVersion: version, label: 'By Ravi' });
    expect((await lastEvent('alarm.updated', alarm.id))!.payload).toMatchObject({ notify: false });
  });

  it('notifyFor (pure)', () => {
    const actor = new ObjectId();
    const other = new ObjectId();
    const doc = (ring: boolean, ready: boolean | null, enabled = true) =>
      ({
        enabled,
        members: [
          { userId: actor, ring: true, ready: true },
          { userId: other, ring, ready },
        ],
      }) as unknown as AlarmDoc;
    expect(notifyFor(doc(true, true), actor, 'created')).toBe(true);
    expect(notifyFor(doc(true, false), actor, 'edited')).toBe(true);
    expect(notifyFor(doc(true, true), actor, 'enabled')).toBe(true);
    expect(notifyFor(doc(true, null), actor, 'created')).toBe(false);
    expect(notifyFor(doc(false, true), actor, 'edited')).toBe(false);
    expect(notifyFor(doc(true, true, false), actor, 'edited')).toBe(false);
    expect(notifyFor(doc(true, true), actor, 'member')).toBe(false);
    // a document without the field (an older shape) reads as never reported
    const legacy = { enabled: true, members: [{ userId: other, ring: true }] } as unknown as AlarmDoc;
    expect(notifyFor(legacy, actor, 'edited')).toBe(false);
  });

  it('pushes notifying changes high, others as a silent sync, and never content', async () => {
    const token = `fcm-${randomUUID()}`;
    await req(b, 'PUT', '/devices', {
      deviceId: 'ravi-phone',
      platform: 'android',
      token,
      appVersion: '1.0.0',
    });
    await t.outbox.runOnce();
    push.calls.length = 0;
    const alarm = (await create(a, { ...weekdays, label: 'Secret label' })).json().alarm as Alarm;
    await report(b, [{ alarmId: alarm.id, ready: true }]);
    await patch(a, alarm.id, { expectedVersion: 2, time: '05:30' });
    const occurrence = new Date(t.clock.now().getTime() - HOUR).toISOString();
    await wake(a, alarm.id, { occurrence, outcome: 'up', at: t.clock.now().toISOString(), snoozes: 0 });
    await req(a, 'DELETE', `/alarms/${alarm.id}`);
    await t.outbox.runOnce();
    const calls = push.calls.filter((call) => call.data.type.startsWith('alarm.'));
    // Ravi's own report goes to Asha, who has no phone registered: not pushed to him
    expect(calls.map((call) => [call.data.type, call.opts.priority])).toEqual([
      ['alarm.created', 'normal'],
      ['alarm.updated', 'high'],
      ['alarm.woke', 'normal'],
      ['alarm.deleted', 'normal'],
    ]);
    const high = calls[1]!;
    expect(high.opts).toEqual({ priority: 'high', collapseKey: undefined, ttlMs: undefined });
    expect(high.data.pr).toBe('h');
    for (const call of calls) {
      expect(call.tokens).toEqual([token]);
      expect(Object.keys(call.data).sort()).toEqual(['coupleId', 'eventId', 'pr', 'seq', 'type']);
      expect(JSON.stringify(call.data)).not.toContain('Secret');
    }

    // Ravi turned notifications off: a notifying change goes out as a silent normal push
    await req(b, 'PUT', '/me/preferences', { notificationsEnabled: false });
    const second = (await create(a, weekdays)).json().alarm as Alarm;
    await report(b, [{ alarmId: second.id, ready: true }]);
    await patch(a, second.id, { expectedVersion: 2, label: 'quiet' });
    push.calls.length = 0;
    await t.outbox.runOnce();
    const quiet = push.calls.find((call) => call.data.type === 'alarm.updated')!;
    expect(quiet.opts.priority).toBe('normal');
    expect(quiet.data.pr).toBe('n');
    await req(b, 'PUT', '/me/preferences', { notificationsEnabled: true });
    await req(b, 'DELETE', '/devices/ravi-phone');
  });
});

describe('alarms: PUT /alarms/:id/me', () => {
  it("changes only the caller's entry; a repeat changes nothing", async () => {
    const alarm = (await create(a, weekdays)).json().alarm as Alarm;
    const res = await mine(b, alarm.id, { ring: false });
    expect(res.statusCode).toBe(200);
    const after = res.json().alarm as Alarm;
    expect(member(after, b).ring).toBe(false);
    expect(member(after, a)).toEqual(member(alarm, a));
    expect(after).toMatchObject({ version: 2, updatedBy: b.user.id, label: 'Gym', time: '06:30' });
    const events = await countEvents(alarm.id);
    const again = await mine(b, alarm.id, { ring: false });
    expect(again.json().alarm.version).toBe(2);
    expect(await countEvents(alarm.id)).toBe(events);
    // there is no way to set the partner's flag: PATCH has no members, PUT /me is the caller's
    expect((await patch(a, alarm.id, { expectedVersion: 2, members: [] })).statusCode).toBe(400);
    expect((await mine(b, alarm.id, {})).statusCode).toBe(400);
    expect((await mine(b, alarm.id, { ring: true, userId: a.user.id })).statusCode).toBe(400);
  });

  it('skips and un-skips one ring; at most 7; old skips are dropped', async () => {
    const alarm = (await create(a, weekdays)).json().alarm as Alarm;
    const occ = (days: number) => new Date(t.clock.now().getTime() + days * DAY).toISOString();
    const first = occ(1);
    const skipped = (await mine(a, alarm.id, { skip: { occurrence: first, skip: true } })).json()
      .alarm as Alarm;
    expect(member(skipped, a).skips).toEqual([first]);
    expect(member(skipped, b).skips).toEqual([]);
    expect((await lastEvent('alarm.updated', alarm.id))!.payload).toMatchObject({
      change: 'member',
      notify: false,
    });
    // the same skip again: nothing changes
    const same = (await mine(a, alarm.id, { skip: { occurrence: first, skip: true } })).json().alarm;
    expect(same.version).toBe(skipped.version);
    const unskipped = (await mine(a, alarm.id, { skip: { occurrence: first, skip: false } })).json().alarm;
    expect(member(unskipped, a).skips).toEqual([]);

    for (let i = 1; i <= 7; i++) {
      expect((await mine(a, alarm.id, { skip: { occurrence: occ(i), skip: true } })).statusCode).toBe(200);
    }
    const eighth = await mine(a, alarm.id, { skip: { occurrence: occ(8), skip: true } });
    expect(eighth.statusCode).toBe(400);
    // three days later the first two have rung and are over a day old: they make room
    t.clock.advance(3 * DAY + HOUR);
    const fifth = occ(5);
    const later = (await mine(a, alarm.id, { skip: { occurrence: fifth, skip: true } })).json()
      .alarm as Alarm;
    expect(member(later, a).skips).toHaveLength(6);
    expect(member(later, a).skips.at(-1)).toBe(fifth);

    expect((await mine(a, alarm.id, { skip: { occurrence: occ(-2), skip: true } })).statusCode).toBe(400);
    expect((await mine(a, alarm.id, { skip: { occurrence: occ(400), skip: true } })).statusCode).toBe(400);
  });

  it('nextSkips (pure)', () => {
    const now = new Date('2026-11-10T00:00:00Z');
    const d = (s: string) => new Date(s);
    expect(
      nextSkips([d('2026-11-08T00:00:00Z'), d('2026-11-12T00:00:00Z')], d('2026-11-11T00:00:00Z'), true, now),
    ).toEqual([d('2026-11-11T00:00:00Z'), d('2026-11-12T00:00:00Z')]);
    expect(nextSkips([d('2026-11-12T00:00:00Z')], d('2026-11-12T00:00:00Z'), false, now)).toEqual([]);
  });
});

describe('alarms: POST /alarms/report', () => {
  it('emits only when ready or reason changes; armedFor alone is stored silently', async () => {
    const alarm = (await create(a, weekdays)).json().alarm as Alarm;
    const armedFor = new Date(t.clock.now().getTime() + DAY).toISOString();
    expect((await report(b, [{ alarmId: alarm.id, ready: true, reason: null, armedFor }])).statusCode).toBe(
      204,
    );
    const ev = await lastEvent('alarm.updated', alarm.id);
    const reported = ev!.payload.alarm as Alarm;
    expect(reported.version).toBe(2);
    expect(member(reported, b)).toMatchObject({
      ready: true,
      reason: null,
      armedFor,
      reportedAt: expect.any(String),
    });
    expect(member(reported, a).ready).toBeNull();

    const events = await countEvents(alarm.id);
    t.clock.advance(60_000);
    const next = new Date(t.clock.now().getTime() + 2 * DAY).toISOString();
    expect((await report(b, [{ alarmId: alarm.id, ready: true, armedFor: next }])).statusCode).toBe(204);
    expect(await countEvents(alarm.id)).toBe(events);
    const stored = (await req(a, 'GET', '/alarms'))
      .json()
      .items.find((x: Alarm) => x.id === alarm.id) as Alarm;
    expect(stored.version).toBe(2);
    expect(member(stored, b).armedFor).toBe(next);
    expect(member(stored, b).reportedAt).not.toBe(member(reported, b).reportedAt);

    // a reason change is news
    await report(b, [{ alarmId: alarm.id, ready: false, reason: 'notifications_off', armedFor: null }]);
    expect(await countEvents(alarm.id)).toBe(events + 1);
  });

  it('skips unknown, deleted and other couples’ alarms; validates the body', async () => {
    const theirs = (await create(c, weekdays)).json().alarm as Alarm;
    const gone = (await create(a, weekdays)).json().alarm as Alarm;
    await req(a, 'DELETE', `/alarms/${gone.id}`);
    const res = await report(b, [
      { alarmId: theirs.id, ready: true },
      { alarmId: gone.id, ready: true },
      { alarmId: new ObjectId().toHexString(), ready: false, reason: 'channel_blocked' },
    ]);
    expect(res.statusCode).toBe(204);
    const untouched = await t.db.alarms.findOne({ _id: new ObjectId(theirs.id) });
    expect(untouched!.version).toBe(1);
    expect(untouched!.members.every((m) => m.reportedAt === null)).toBe(true);

    const id = () => new ObjectId().toHexString();
    const eleven = Array.from({ length: 11 }, () => ({ alarmId: id(), ready: true }));
    expect((await report(b, eleven)).statusCode).toBe(400);
    const dup = id();
    expect(
      (
        await report(b, [
          { alarmId: dup, ready: true },
          { alarmId: dup, ready: false },
        ])
      ).statusCode,
    ).toBe(400);
    expect((await report(b, [{ alarmId: id(), ready: false, reason: 'Not Snake' }])).statusCode).toBe(400);
    expect((await report(b, [{ alarmId: id() }])).statusCode).toBe(400);
    expect((await report(b, [])).statusCode).toBe(204);
  });
});

describe('alarms: POST /alarms/:id/wake', () => {
  it('stores lastWake and emits alarm.woke once per member and occurrence; the version stays', async () => {
    const alarm = (await create(a, weekdays)).json().alarm as Alarm;
    const occurrence = new Date(t.clock.now().getTime() - 10 * 60_000).toISOString();
    const at = t.clock.now().toISOString();
    const res = await wake(b, alarm.id, { occurrence, outcome: 'up', at, snoozes: 1 });
    expect(res.statusCode).toBe(200);
    const after = res.json().alarm as Alarm;
    expect(after.version).toBe(1);
    expect(member(after, b).lastWake).toEqual({ occurrence, outcome: 'up', at, snoozes: 1 });
    expect(member(after, a).lastWake).toBeNull();
    const ev = await lastEvent('alarm.woke', alarm.id);
    expect(ev!.payload).toEqual({
      alarmId: alarm.id,
      userId: b.user.id,
      occurrence,
      outcome: 'up',
      at,
      snoozes: 1,
    });
    expect(ev!.actorId.toHexString()).toBe(b.user.id);

    const events = await countEvents(alarm.id);
    // a retry, even with another outcome, is a no-op
    const again = await wake(b, alarm.id, { occurrence, outcome: 'missed', at, snoozes: 2 });
    expect(again.statusCode).toBe(200);
    expect(member(again.json().alarm, b).lastWake).toMatchObject({ outcome: 'up', snoozes: 1 });
    // so is a late report about an earlier ring
    const earlier = new Date(Date.parse(occurrence) - DAY).toISOString();
    await wake(b, alarm.id, { occurrence: earlier, outcome: 'missed', at, snoozes: 0 });
    expect(await countEvents(alarm.id)).toBe(events);
    // the partner's own report is separate
    expect((await wake(a, alarm.id, { occurrence, outcome: 'missed', at, snoozes: 2 })).statusCode).toBe(200);
    expect(await countEvents(alarm.id)).toBe(events + 1);
    // the next morning is new
    const tomorrow = new Date(Date.parse(occurrence) + DAY - HOUR).toISOString();
    await wake(b, alarm.id, { occurrence: tomorrow, outcome: 'up', at: tomorrow, snoozes: 0 });
    expect(await countEvents(alarm.id)).toBe(events + 2);
  });

  it('validates the report; 404 across couples or once deleted', async () => {
    const alarm = (await create(a, weekdays)).json().alarm as Alarm;
    const now = t.clock.now().getTime();
    const ok = {
      occurrence: new Date(now - HOUR).toISOString(),
      outcome: 'up',
      at: new Date(now).toISOString(),
      snoozes: 0,
    };
    expect((await wake(b, alarm.id, { ...ok, outcome: 'snoozed' })).statusCode).toBe(400);
    expect((await wake(b, alarm.id, { ...ok, snoozes: 21 })).statusCode).toBe(400);
    expect(
      (await wake(b, alarm.id, { ...ok, occurrence: new Date(now - 8 * DAY).toISOString() })).statusCode,
    ).toBe(400);
    expect(
      (await wake(b, alarm.id, { ...ok, occurrence: new Date(now + 2 * DAY).toISOString() })).statusCode,
    ).toBe(400);
    expect((await wake(c, alarm.id, ok)).statusCode).toBe(404);
    await req(a, 'DELETE', `/alarms/${alarm.id}`);
    expect((await wake(b, alarm.id, ok)).statusCode).toBe(404);
  });
});

describe('alarms: delete, isolation, export, validator', () => {
  it('DELETE removes it for both with alarm.deleted; another couple gets 404 everywhere', async () => {
    const alarm = (await create(a, weekdays)).json().alarm as Alarm;
    expect((await req(c, 'GET', '/alarms')).json().items.map((x: Alarm) => x.id)).not.toContain(alarm.id);
    expect((await patch(c, alarm.id, { expectedVersion: 1, label: 'x' })).statusCode).toBe(404);
    expect((await mine(c, alarm.id, { ring: false })).statusCode).toBe(404);
    expect((await req(c, 'DELETE', `/alarms/${alarm.id}`)).statusCode).toBe(404);
    expect((await patch(a, 'nope', { expectedVersion: 1 })).statusCode).toBe(400);

    expect((await req(b, 'DELETE', `/alarms/${alarm.id}`)).statusCode).toBe(204);
    expect((await lastEvent('alarm.deleted', alarm.id))!.payload).toEqual({ alarmId: alarm.id });
    expect(await t.db.alarms.findOne({ _id: new ObjectId(alarm.id) })).toBeNull();
    expect((await req(a, 'DELETE', `/alarms/${alarm.id}`)).statusCode).toBe(404);
    expect((await patch(a, alarm.id, { expectedVersion: 1, label: 'x' })).statusCode).toBe(404);
    expect((await mine(a, alarm.id, { ring: false })).statusCode).toBe(404);
  });

  it('unpaired users get 409 not_paired', async () => {
    const solo = await register(t.app, 'Solo');
    for (const [method, url] of [
      ['GET', '/alarms'],
      ['POST', '/alarms/report'],
    ] as const) {
      const res = await req(solo, method, url, method === 'GET' ? undefined : { items: [] });
      expect(res.statusCode, url).toBe(409);
      expect(res.json().error.code).toBe('not_paired');
    }
  });

  it('GET /me/export includes the alarms', async () => {
    const alarm = (await create(b, { ...weekdays, label: 'Export me' })).json().alarm as Alarm;
    const exp = await req(a, 'GET', '/me/export');
    expect(exp.statusCode).toBe(200);
    expect(exp.json().alarms.map((x: Alarm) => x.id)).toContain(alarm.id);
    expect((await req(c, 'GET', '/me/export')).json().alarms.map((x: Alarm) => x.id)).not.toContain(alarm.id);
  });

  it('the validator has no enums and no additionalProperties; a newer mode passes and reads as a string', async () => {
    const json = JSON.stringify(VALIDATORS.alarms);
    expect(json).not.toContain('"enum"');
    expect(json).not.toContain('additionalProperties');
    const uid = new ObjectId(a.user.id);
    const now = new Date();
    const doc = {
      _id: new ObjectId(),
      coupleId: new ObjectId(coupleId),
      createdBy: uid,
      updatedBy: uid,
      mode: 'local_v2',
      label: null,
      time: '07:00',
      timeZone: 'UTC',
      days: [1],
      onceAt: null,
      enabled: true,
      snoozeMinutes: 10,
      version: 1,
      createdAt: now,
      updatedAt: now,
      members: [{ userId: uid, ring: true, futureField: 1 }],
      futureTopField: 'x',
    } as unknown as AlarmDoc;
    await t.db.alarms.insertOne(doc);
    const got = (await req(b, 'GET', '/alarms'))
      .json()
      .items.find((x: Alarm) => x.id === doc._id.toHexString());
    expect(got).toMatchObject({ mode: 'local_v2' });
    // older or newer member shapes read with defaults
    expect(got.members[0]).toEqual({
      userId: a.user.id,
      ring: true,
      skips: [],
      ready: null,
      reason: null,
      armedFor: null,
      reportedAt: null,
      lastWake: null,
    });
    await t.db.alarms.deleteOne({ _id: doc._id });
    const { time: _, ...noTime } = doc as unknown as Record<string, unknown>;
    await expect(
      t.db.alarms.insertOne({ ...noTime, _id: new ObjectId() } as unknown as AlarmDoc),
    ).rejects.toSatisfy((e) => e instanceof MongoServerError && e.code === 121);
  });
});

describe('alarm write rate limit', () => {
  it('is per user and shared by every write, reports included', async () => {
    const t2 = await createTestApp({ envOverrides: { RATE_LIMIT_ALARM_WRITES_PER_MIN: '3' } });
    try {
      const x = await register(t2.app, 'Xan');
      const y = await register(t2.app, 'Yul');
      await pair(t2.app, x, y);
      const call = (s: Session, method: 'POST' | 'PUT', url: string, payload: object) =>
        t2.app.inject({ method, url: `${API}${url}`, headers: bearer(s), payload });
      const first = await call(x, 'POST', '/alarms', weekdays);
      const id = first.json().alarm.id;
      const second = await call(x, 'POST', '/alarms/report', { items: [{ alarmId: id, ready: true }] });
      const third = await call(x, 'PUT', `/alarms/${id}/me`, { ring: false });
      const fourth = await call(x, 'POST', '/alarms', weekdays);
      expect([first.statusCode, second.statusCode, third.statusCode, fourth.statusCode]).toEqual([
        201, 204, 200, 429,
      ]);
      expect(fourth.json().error.code).toBe('rate_limited');
      expect(
        (await t2.app.inject({ method: 'GET', url: `${API}/alarms`, headers: bearer(x) })).statusCode,
      ).toBe(200);
      expect((await call(y, 'POST', '/alarms', weekdays)).statusCode).toBe(201);
    } finally {
      await t2.close();
    }
  });
});
