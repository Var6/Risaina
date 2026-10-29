import { randomUUID } from 'node:crypto';
import { MongoServerError, ObjectId } from 'mongodb';
import { pino } from 'pino';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { CalendarEntryDoc } from '../src/db/types.js';
import { VALIDATORS } from '../src/db/validators.js';
import { entryIssues } from '../src/modules/calendar/service.js';
import { CleanupWorker } from '../src/workers/cleanup.js';
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
let coupleId: string;

beforeAll(async () => {
  push = new FakePushService();
  t = await createTestApp({ push, envOverrides: { RATE_LIMIT_CALENDAR_WRITES_PER_MIN: '10000' } });
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

const post = (s: Session, payload: Record<string, unknown>) =>
  t.app.inject({ method: 'POST', url: `${API}/calendar`, headers: bearer(s), payload });
const patch = (s: Session, id: string, payload: Record<string, unknown>) =>
  t.app.inject({ method: 'PATCH', url: `${API}/calendar/${id}`, headers: bearer(s), payload });
const del = (s: Session, id: string) =>
  t.app.inject({ method: 'DELETE', url: `${API}/calendar/${id}`, headers: bearer(s) });
const list = async (s: Session) =>
  (await t.app.inject({ method: 'GET', url: `${API}/calendar`, headers: bearer(s) })).json().items as {
    id: string;
  }[];
const event = (type: string, filter: Record<string, unknown>) =>
  t.db.events.findOne({ type: type as 'calendar.created', ...filter }, { sort: { seq: -1 } });

const dinner = { kind: 'event', title: 'Dinner with her parents', date: '2026-11-14' };

describe('calendar CRUD', () => {
  it('creates an all-day event with defaults, lists it, edits it, deletes it, with calendar.* events', async () => {
    const created = await post(a, dinner);
    expect(created.statusCode).toBe(201);
    const entry = created.json().entry;
    expect(entry).toEqual({
      id: expect.stringMatching(/^[a-f0-9]{24}$/),
      coupleId,
      createdBy: a.user.id,
      updatedBy: a.user.id,
      kind: 'event',
      title: 'Dinner with her parents',
      notes: null,
      emoji: null,
      date: '2026-11-14',
      endDate: null,
      time: null,
      durationMinutes: null,
      timeZone: null,
      repeat: 'none',
      repeatUntil: null,
      reminders: [],
      remindUserIds: [a.user.id, b.user.id],
      pinned: false,
      done: false,
      doneAt: null,
      doneBy: null,
      source: null,
      clientKey: null,
      version: 1,
      createdAt: expect.any(String),
      updatedAt: expect.any(String),
    });
    const createdEv = await event('calendar.created', { 'payload.entry.id': entry.id });
    expect(createdEv!.payload).toEqual({ entry, notify: false });
    expect(createdEv!.actorId.toHexString()).toBe(a.user.id);
    expect((await list(b)).map((e) => e.id)).toContain(entry.id);

    t.clock.advance(1000);
    const edited = await patch(b, entry.id, {
      title: 'Dinner at 8',
      time: '20:00',
      timeZone: 'europe/berlin',
      durationMinutes: 120,
      reminders: [60, 0, 1440],
      notifyPartner: true,
    });
    expect(edited.statusCode).toBe(200);
    const v2 = edited.json().entry;
    expect(v2).toMatchObject({
      version: 2,
      updatedBy: b.user.id,
      createdBy: a.user.id,
      title: 'Dinner at 8',
      time: '20:00',
      timeZone: 'Europe/Berlin', // letter case fixed, like profile time zones
      durationMinutes: 120,
      reminders: [0, 60, 1440],
    });
    expect(v2.updatedAt).not.toBe(entry.updatedAt);
    const updatedEv = await event('calendar.updated', { 'payload.entry.id': entry.id });
    expect(updatedEv!.payload).toEqual({ entry: v2, notify: true });

    expect((await del(a, entry.id)).statusCode).toBe(204);
    const deletedEv = await event('calendar.deleted', { 'payload.entryId': entry.id });
    expect(deletedEv!.payload).toEqual({ entryId: entry.id });
    expect(await t.db.calendarEntries.findOne({ _id: new ObjectId(entry.id) })).toBeNull(); // hard delete
    expect((await list(a)).map((e) => e.id)).not.toContain(entry.id);
    expect((await del(a, entry.id)).statusCode).toBe(404);
    expect((await patch(a, entry.id, { title: 'x' })).statusCode).toBe(404);
  });

  it('stores every field of a timed, repeating entry', async () => {
    const res = await post(a, {
      kind: 'event',
      title: 'Sunday call',
      notes: 'Bring the photos',
      emoji: '📞',
      date: '2026-11-01',
      time: '21:00',
      durationMinutes: 45,
      timeZone: 'Europe/London',
      repeat: 'weekly',
      repeatUntil: '2027-03-28',
      reminders: [10],
      remindUserIds: [b.user.id],
      pinned: true,
    });
    expect(res.statusCode).toBe(201);
    expect(res.json().entry).toMatchObject({
      notes: 'Bring the photos',
      emoji: '📞',
      time: '21:00',
      timeZone: 'Europe/London',
      repeat: 'weekly',
      repeatUntil: '2027-03-28',
      reminders: [10],
      remindUserIds: [b.user.id],
      pinned: true,
    });
    // a multi-day all-day entry, and blank notes stored as null
    const trip = await post(a, {
      kind: 'event',
      title: 'Goa',
      date: '2026-12-20',
      endDate: '2026-12-27',
      notes: '  ',
    });
    expect(trip.json().entry).toMatchObject({ endDate: '2026-12-27', notes: null });
  });

  it('lists entries by date, then time (all-day first)', async () => {
    const t2 = await createTestApp();
    try {
      const x = await register(t2.app, 'Xia');
      const y = await register(t2.app, 'Yan');
      await pair(t2.app, x, y);
      const add = async (date: string, time?: string) =>
        (
          await t2.app.inject({
            method: 'POST',
            url: `${API}/calendar`,
            headers: bearer(x),
            payload: {
              kind: 'event',
              title: `${date} ${time ?? 'all day'}`,
              date,
              ...(time ? { time } : {}),
            },
          })
        ).json().entry.title;
      await add('2026-12-01', '09:00');
      await add('2026-11-30');
      await add('2026-12-01');
      await add('2026-12-01', '08:00');
      const res = await t2.app.inject({ method: 'GET', url: `${API}/calendar`, headers: bearer(y) });
      expect(res.json().items.map((e: { title: string }) => e.title)).toEqual([
        '2026-11-30 all day',
        '2026-12-01 all day',
        '2026-12-01 08:00',
        '2026-12-01 09:00',
      ]);
    } finally {
      await t2.close();
    }
  });
});

describe('calendar validation', () => {
  it.each([
    ['timeZone without a time', { ...dinner, timeZone: 'Asia/Kolkata' }],
    ['a fixed-offset zone', { ...dinner, time: '20:00', timeZone: '+05:30' }],
    ['an unknown zone', { ...dinner, time: '20:00', timeZone: 'Mars/Olympus' }],
    ['durationMinutes without a time', { ...dinner, durationMinutes: 30 }],
    ['durationMinutes over a week', { ...dinner, time: '20:00', durationMinutes: 10_081 }],
    ['endDate on a timed entry', { ...dinner, time: '20:00', endDate: '2026-11-15' }],
    ['endDate before date', { ...dinner, endDate: '2026-11-13' }],
    ['endDate more than 366 days later', { ...dinner, endDate: '2027-11-16' }],
    ['a repeating to-do', { kind: 'todo', title: 'Pay rent', date: '2026-11-01', repeat: 'monthly' }],
    ['repeatUntil before date', { ...dinner, repeat: 'yearly', repeatUntil: '2026-11-13' }],
    ['5 reminders', { ...dinner, reminders: [0, 10, 60, 1440, 10_080] }],
    ['duplicate reminders', { ...dinner, reminders: [10, 10] }],
    ['a reminder over 4 weeks', { ...dinner, reminders: [40_321] }],
    ['a negative reminder', { ...dinner, reminders: [-1] }],
    ['a blank title', { ...dinner, title: '   ' }],
    ['a title over 120', { ...dinner, title: 'x'.repeat(121) }],
    ['notes over 2000', { ...dinner, notes: 'x'.repeat(2001) }],
    ['an emoji of 3 graphemes', { ...dinner, emoji: '🎂🎂🎂' }],
    ['an impossible date', { ...dinner, date: '2026-02-30' }],
    ['a bad time', { ...dinner, time: '24:00' }],
    ['an unknown kind', { ...dinner, kind: 'birthday' }],
    ['an unknown repeat', { ...dinner, repeat: 'daily' }],
    ['an unknown key', { ...dinner, colour: 'red' }],
    ['a clientKey over 64', { ...dinner, clientKey: 'k'.repeat(65) }],
    ['done on create', { ...dinner, done: true }],
    [
      'an unknown source kind',
      { ...dinner, source: { kind: 'photo', messageId: new ObjectId().toHexString() } },
    ],
    [
      'a source with extra ids',
      {
        ...dinner,
        source: {
          kind: 'list',
          listId: new ObjectId().toHexString(),
          messageId: new ObjectId().toHexString(),
        },
      },
    ],
    [
      'a list_item source without itemId',
      { ...dinner, source: { kind: 'list_item', listId: new ObjectId().toHexString() } },
    ],
  ])('POST rejects %s (400)', async (_, payload) => {
    const res = await post(a, payload);
    expect(res.statusCode, res.body).toBe(400);
    expect(res.json().error.code).toBe('validation_failed');
  });

  it('remindUserIds must be members of the couple, without repeats', async () => {
    const res = await post(a, { ...dinner, remindUserIds: [a.user.id, c.user.id] });
    expect(res.statusCode).toBe(400);
    expect(res.json().error.details).toEqual([
      { path: '/remindUserIds/1', message: 'not a member of this couple' },
    ]);
    expect((await post(a, { ...dinner, remindUserIds: [a.user.id, a.user.id] })).statusCode).toBe(400);
    const none = await post(a, { ...dinner, remindUserIds: [] });
    expect(none.json().entry.remindUserIds).toEqual([]);
  });

  it('checks the rules on the entry as it will be stored after a PATCH', async () => {
    const timed = (
      await post(a, { ...dinner, time: '20:00', timeZone: 'Asia/Kolkata', durationMinutes: 60 })
    ).json().entry;
    const allDay = await patch(a, timed.id, { time: null });
    expect(allDay.statusCode).toBe(400);
    expect(allDay.json().error.details.map((i: { path: string }) => i.path)).toEqual([
      '/timeZone',
      '/durationMinutes',
    ]);
    const ok = await patch(a, timed.id, { time: null, timeZone: null, durationMinutes: null });
    expect(ok.statusCode).toBe(200);
    expect(ok.json().entry).toMatchObject({ time: null, timeZone: null, durationMinutes: null, version: 2 });

    const todo = (await post(a, { kind: 'todo', title: 'Book tickets', date: '2026-11-20' })).json().entry;
    expect((await patch(a, todo.id, { repeat: 'weekly' })).statusCode).toBe(400);
    expect((await patch(a, todo.id, { date: '2026-11-21', repeatUntil: '2026-11-20' })).statusCode).toBe(400);
    const series = (await post(a, { ...dinner, repeat: 'weekly' })).json().entry;
    expect((await patch(a, series.id, { kind: 'todo' })).statusCode).toBe(400);
    expect((await patch(a, series.id, { kind: 'todo', repeat: 'none' })).statusCode).toBe(200);
    expect((await patch(a, series.id, { clientKey: 'x' })).statusCode).toBe(400); // POST only
  });

  it('entryIssues (pure)', () => {
    const base = {
      kind: 'event' as const,
      date: '2024-02-29',
      endDate: null,
      time: null,
      durationMinutes: null,
      timeZone: null,
      repeat: 'none' as const,
      repeatUntil: null,
    };
    expect(entryIssues(base)).toEqual([]);
    expect(entryIssues({ ...base, endDate: '2025-02-28' })).toEqual([]); // 365 days after a leap day
    expect(entryIssues({ ...base, endDate: '2025-03-01' })).toEqual([]); // 366
    expect(entryIssues({ ...base, endDate: '2025-03-02' })).toHaveLength(1); // 367
    expect(entryIssues({ ...base, endDate: '2024-02-29' })).toEqual([]); // a one-day span is fine
    expect(entryIssues({ ...base, repeatUntil: '2024-02-29' })).toEqual([]);
  });
});

describe('to-dos: done', () => {
  it('ticks, keeps who ticked first, unticks; only for to-dos', async () => {
    const todo = (await post(a, { kind: 'todo', title: 'Buy a gift', date: '2026-11-10' })).json().entry;
    t.clock.advance(1000);
    const ticked = (await patch(b, todo.id, { done: true })).json().entry;
    expect(ticked).toMatchObject({ done: true, doneBy: b.user.id, doneAt: ticked.updatedAt });
    t.clock.advance(1000);
    const again = (await patch(a, todo.id, { done: true, title: 'Buy a gift 🎁' })).json().entry;
    expect(again).toMatchObject({ done: true, doneBy: b.user.id, doneAt: ticked.doneAt });
    const unticked = (await patch(a, todo.id, { done: false })).json().entry;
    expect(unticked).toMatchObject({ done: false, doneBy: null, doneAt: null });

    const ev = (await post(a, dinner)).json().entry;
    const bad = await patch(a, ev.id, { done: true });
    expect(bad.statusCode).toBe(400);
    expect(bad.json().error.message).toBe('done is only for to-dos');
    // an event may become a to-do in the same request that ticks it, and back (which clears done)
    const nowTodo = (await patch(a, ev.id, { kind: 'todo', done: true })).json().entry;
    expect(nowTodo).toMatchObject({ kind: 'todo', done: true, doneBy: a.user.id });
    const backToEvent = (await patch(b, ev.id, { kind: 'event' })).json().entry;
    expect(backToEvent).toMatchObject({ kind: 'event', done: false, doneBy: null, doneAt: null });
  });
});

describe('calendar concurrency', () => {
  it('a stale expectedVersion is 409 version_conflict with the current entry; without it the last write wins', async () => {
    const entry = (await post(a, dinner)).json().entry;
    const ok = await patch(a, entry.id, { pinned: true, expectedVersion: 1 });
    expect(ok.json().entry.version).toBe(2);
    const stale = await patch(b, entry.id, { title: 'Stale', expectedVersion: 1 });
    expect(stale.statusCode).toBe(409);
    expect(stale.json().error.code).toBe('version_conflict');
    expect(stale.json().error.details.entry).toEqual(ok.json().entry);
    const lww = await patch(b, entry.id, { title: 'Latest' });
    expect(lww.json().entry).toMatchObject({ title: 'Latest', version: 3, pinned: true });
  });

  it('concurrent PATCHes without expectedVersion all apply (compare-and-set retries)', async () => {
    const entry = (await post(a, dinner)).json().entry;
    const results = await Promise.all(
      [10, 20, 30, 40].map((m) =>
        patch(m % 20 === 0 ? a : b, entry.id, { durationMinutes: m, time: '19:00' }),
      ),
    );
    expect(results.every((r) => r.statusCode === 200)).toBe(true);
    const stored = await t.db.calendarEntries.findOne({ _id: new ObjectId(entry.id) });
    expect(stored!.version).toBe(5);
  });
});

describe('calendar clientKey (idempotent create)', () => {
  it('a repeat answers 200 with the first entry and emits nothing; the key is per couple', async () => {
    const key = randomUUID();
    const first = await post(a, { ...dinner, clientKey: key });
    expect(first.statusCode).toBe(201);
    expect(first.json().entry.clientKey).toBe(key);
    const before = await t.db.events.countDocuments({ type: 'calendar.created' });
    const again = await post(b, { ...dinner, title: 'Other', clientKey: key });
    expect(again.statusCode).toBe(200);
    expect(again.json().entry).toEqual(first.json().entry);
    expect(await t.db.events.countDocuments({ type: 'calendar.created' })).toBe(before);
    const other = await post(c, { ...dinner, clientKey: key });
    expect(other.statusCode).toBe(201);

    // deleted entries are gone for good, so the key can create again
    expect((await del(a, first.json().entry.id)).statusCode).toBe(204);
    expect((await post(a, { ...dinner, clientKey: key })).statusCode).toBe(201);
  });

  it('concurrent creates with one key make one entry', async () => {
    const key = randomUUID();
    const results = await Promise.all(
      Array.from({ length: 4 }, () => post(a, { ...dinner, clientKey: key })),
    );
    expect(results.map((r) => r.statusCode).sort()).toEqual([200, 200, 200, 201]);
    expect(await t.db.calendarEntries.countDocuments({ clientKey: key })).toBe(1);
  });
});

describe('calendar sources', () => {
  const sendText = async (s: Session, text = 'Dinner on Fri 14 Nov at 8?') =>
    (
      await t.app.inject({
        method: 'POST',
        url: `${API}/messages`,
        headers: bearer(s),
        payload: { type: 'text', text, idempotencyKey: randomUUID() },
      })
    ).json().message.id as string;

  it('links a message of the couple (by id only)', async () => {
    const messageId = await sendText(b);
    const res = await post(a, { ...dinner, source: { kind: 'message', messageId } });
    expect(res.statusCode).toBe(201);
    expect(res.json().entry.source).toEqual({ kind: 'message', messageId });
    const stored = await t.db.calendarEntries.findOne({ _id: new ObjectId(res.json().entry.id) });
    expect(stored!.source).toEqual({ kind: 'message', messageId: new ObjectId(messageId) });
    // the partner's entry list shows the link too; nothing of the text is copied
    expect(JSON.stringify(res.json().entry)).not.toContain('Fri 14 Nov');
  });

  it('refuses a message of another couple or a deleted one (404) and a view-once photo (400)', async () => {
    const theirs = await sendText(c);
    expect((await post(a, { ...dinner, source: { kind: 'message', messageId: theirs } })).statusCode).toBe(
      404,
    );
    const gone = await sendText(a);
    await t.app.inject({ method: 'DELETE', url: `${API}/messages/${gone}`, headers: bearer(a) });
    expect((await post(a, { ...dinner, source: { kind: 'message', messageId: gone } })).statusCode).toBe(404);
    const missing = new ObjectId().toHexString();
    expect((await post(a, { ...dinner, source: { kind: 'message', messageId: missing } })).statusCode).toBe(
      404,
    );

    const mediaId = await uploadPhoto(t, a);
    const vo = await t.app.inject({
      method: 'POST',
      url: `${API}/messages`,
      headers: bearer(a),
      payload: { type: 'photo', mediaId, viewOnce: true, idempotencyKey: randomUUID() },
    });
    expect(vo.statusCode).toBe(201);
    const res = await post(b, { ...dinner, source: { kind: 'message', messageId: vo.json().message.id } });
    expect(res.statusCode).toBe(400);
  });

  it('links a list or a list item; a deleted list or another couple’s is 404', async () => {
    const created = await t.app.inject({
      method: 'POST',
      url: `${API}/lists`,
      headers: bearer(a),
      payload: { title: 'Trip', kind: 'checklist', items: [{ text: 'Book hotel by 1 Dec' }] },
    });
    const l = created.json().list;
    const fromList = await post(a, { ...dinner, source: { kind: 'list', listId: l.id } });
    expect(fromList.json().entry.source).toEqual({ kind: 'list', listId: l.id });
    const fromItem = await post(b, {
      kind: 'todo',
      title: 'Book hotel',
      date: '2026-12-01',
      source: { kind: 'list_item', listId: l.id, itemId: l.items[0].id },
    });
    expect(fromItem.statusCode).toBe(201);
    expect(fromItem.json().entry.source).toEqual({ kind: 'list_item', listId: l.id, itemId: l.items[0].id });

    // PATCH can change or clear the source
    const id = fromList.json().entry.id;
    const cleared = await patch(a, id, { source: null });
    expect(cleared.json().entry.source).toBeNull();
    const theirList = await t.app.inject({
      method: 'POST',
      url: `${API}/lists`,
      headers: bearer(c),
      payload: { title: 'Theirs', kind: 'note' },
    });
    expect(
      (await patch(a, id, { source: { kind: 'list', listId: theirList.json().list.id } })).statusCode,
    ).toBe(404);
    await t.app.inject({ method: 'DELETE', url: `${API}/lists/${l.id}`, headers: bearer(b) });
    expect((await post(a, { ...dinner, source: { kind: 'list', listId: l.id } })).statusCode).toBe(404);
    // an entry linked to a list deleted later keeps its link (the app hides the chip)
    expect(
      (await t.app.inject({ method: 'GET', url: `${API}/calendar`, headers: bearer(a) })).statusCode,
    ).toBe(200);
  });
});

describe('calendar isolation and access', () => {
  it('another couple cannot see, change or delete an entry (404); malformed ids are 400', async () => {
    const entry = (await post(a, { ...dinner, title: 'Ours' })).json().entry;
    expect((await list(c)).map((e) => e.id)).not.toContain(entry.id);
    expect((await patch(c, entry.id, { title: 'hijack' })).statusCode).toBe(404);
    expect((await del(c, entry.id)).statusCode).toBe(404);
    expect((await patch(a, 'nope', { title: 'x' })).statusCode).toBe(400);
    expect((await t.db.calendarEntries.findOne({ _id: new ObjectId(entry.id) }))!.title).toBe('Ours');
  });

  it('unpaired users get 409 not_paired; no token is 401', async () => {
    const solo = await register(t.app, 'Solo');
    const res = await t.app.inject({ method: 'GET', url: `${API}/calendar`, headers: bearer(solo) });
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('not_paired');
    expect((await post(solo, dinner)).statusCode).toBe(409);
    expect((await t.app.inject({ method: 'GET', url: `${API}/calendar` })).statusCode).toBe(401);
  });

  it('caps a couple at 1000 entries (409 conflict)', async () => {
    const x = await register(t.app, 'Cap1');
    const y = await register(t.app, 'Cap2');
    const capCouple = new ObjectId(await pair(t.app, x, y));
    const uid = new ObjectId(x.user.id);
    const now = new Date();
    const docs: CalendarEntryDoc[] = Array.from({ length: 1000 }, () => ({
      _id: new ObjectId(),
      coupleId: capCouple,
      createdBy: uid,
      updatedBy: uid,
      kind: 'event',
      title: 'x',
      notes: null,
      emoji: null,
      date: '2026-11-01',
      endDate: null,
      time: null,
      durationMinutes: null,
      timeZone: null,
      repeat: 'none',
      repeatUntil: null,
      reminders: [],
      remindUserIds: [uid],
      pinned: false,
      doneAt: null,
      doneBy: null,
      source: null,
      clientKey: null,
      version: 1,
      createdAt: now,
      updatedAt: now,
    }));
    await t.db.calendarEntries.insertMany(docs);
    const res = await post(x, dinner);
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('conflict');
    // a retried create (same clientKey) of an entry that exists is still answered
    await t.db.calendarEntries.updateOne({ _id: docs[0]!._id }, { $set: { clientKey: 'kept' } });
    expect((await post(x, { ...dinner, clientKey: 'kept' })).statusCode).toBe(200);
  });
});

describe('calendar push, export, purge and the validator', () => {
  it('pushes calendar.* to the partner at normal priority, with no content, even with notify', async () => {
    const token = `fcm-${randomUUID()}`;
    await t.app.inject({
      method: 'PUT',
      url: `${API}/devices`,
      headers: bearer(b),
      payload: { deviceId: 'b-phone', platform: 'android', token, appVersion: '1.0.0' },
    });
    await t.outbox.runOnce();
    push.calls.length = 0;
    const entry = (await post(a, { ...dinner, title: 'Secret plan', notifyPartner: true })).json().entry;
    await patch(a, entry.id, { notifyPartner: true, time: '19:30' });
    await del(a, entry.id);
    await t.outbox.runOnce();
    const calls = push.calls.filter((call) => call.data.type.startsWith('calendar.'));
    expect(calls.map((call) => call.data.type)).toEqual([
      'calendar.created',
      'calendar.updated',
      'calendar.deleted',
    ]);
    for (const call of calls) {
      expect(call.tokens).toEqual([token]);
      expect(call.opts).toEqual({ priority: 'normal', collapseKey: 'sync', ttlMs: 24 * 60 * 60 * 1000 });
      expect(call.data.pr).toBe('n');
      expect(Object.keys(call.data).sort()).toEqual(['coupleId', 'eventId', 'pr', 'seq', 'type']);
      expect(JSON.stringify(call.data)).not.toContain('Secret');
    }
  });

  it('GET /me/export includes the calendar', async () => {
    const entry = (await post(b, { ...dinner, title: 'Export me' })).json().entry;
    const exp = await t.app.inject({ method: 'GET', url: `${API}/me/export`, headers: bearer(a) });
    expect(exp.statusCode).toBe(200);
    expect(exp.json().calendar).toContainEqual(entry);
    expect(exp.json().truncated).toBe(false);
    const theirs = await t.app.inject({ method: 'GET', url: `${API}/me/export`, headers: bearer(c) });
    expect(theirs.json().calendar.map((e: { id: string }) => e.id)).not.toContain(entry.id);
  });

  it('GET /me/export at the 1000-entry cap has every entry and is not truncated; one more is', async () => {
    const x = await register(t.app, 'Cap1');
    const y = await register(t.app, 'Cap2');
    const capCouple = new ObjectId(await pair(t.app, x, y));
    const now = t.clock.now();
    const doc = (i: number): CalendarEntryDoc => ({
      _id: new ObjectId(),
      coupleId: capCouple,
      createdBy: new ObjectId(x.user.id),
      updatedBy: new ObjectId(x.user.id),
      kind: 'event',
      title: `Entry ${i}`,
      notes: null,
      emoji: null,
      date: '2026-12-01',
      endDate: null,
      time: null,
      durationMinutes: null,
      timeZone: null,
      repeat: 'none',
      repeatUntil: null,
      reminders: [],
      remindUserIds: [new ObjectId(x.user.id), new ObjectId(y.user.id)],
      pinned: false,
      doneAt: null,
      doneBy: null,
      source: null,
      clientKey: null,
      version: 1,
      createdAt: now,
      updatedAt: now,
    });
    await t.db.calendarEntries.insertMany(Array.from({ length: 1000 }, (_, i) => doc(i)));
    const exportOf = async () =>
      (await t.app.inject({ method: 'GET', url: `${API}/me/export`, headers: bearer(x) })).json();
    const full = await exportOf();
    expect(full.calendar).toHaveLength(1000);
    expect(full.truncated).toBe(false);

    await t.db.calendarEntries.insertOne(doc(1000)); // past the cap: only a direct write gets here
    const over = await exportOf();
    expect(over.calendar).toHaveLength(1000);
    expect(over.truncated).toBe(true);
  });

  it('unlink deletes the entries; the cleanup worker sweeps one written in the race', async () => {
    const x = await register(t.app, 'Un1');
    const y = await register(t.app, 'Un2');
    const unlinkCouple = new ObjectId(await pair(t.app, x, y));
    expect((await post(x, dinner)).statusCode).toBe(201);
    const late = (await post(y, { ...dinner, title: 'late' })).json().entry;
    const lateDoc = await t.db.calendarEntries.findOne({ _id: new ObjectId(late.id) });
    const res = await t.app.inject({
      method: 'POST',
      url: `${API}/couple/unlink`,
      headers: bearer(x),
      payload: { confirm: true },
    });
    expect(res.statusCode).toBe(204);
    expect(await t.db.calendarEntries.countDocuments({ coupleId: unlinkCouple })).toBe(0);
    await t.db.calendarEntries.insertOne(lateDoc!); // a write that raced the unlink
    const cleanup = new CleanupWorker(t.db, t.storage, t.clock.now, pino({ level: 'silent' }), {
      intervalMs: 60_000,
      abandonedUploadHours: 24,
    });
    await cleanup.runOnce();
    expect(await t.db.calendarEntries.countDocuments({ coupleId: unlinkCouple })).toBe(0);
  });

  it('the validator has no enums and no additionalProperties; it accepts kinds Zod does not know yet', async () => {
    const json = JSON.stringify(VALIDATORS.calendarEntries);
    expect(json).not.toContain('"enum"');
    expect(json).not.toContain('additionalProperties');

    const uid = new ObjectId(a.user.id);
    const now = new Date();
    const future = {
      _id: new ObjectId(),
      coupleId: new ObjectId(coupleId),
      createdBy: uid,
      updatedBy: uid,
      kind: 'anniversary_v2',
      title: 'From a newer build',
      notes: null,
      emoji: null,
      date: '2026-12-31',
      endDate: null,
      time: null,
      durationMinutes: null,
      timeZone: null,
      repeat: 'daily',
      repeatUntil: null,
      reminders: [],
      remindUserIds: [uid],
      pinned: false,
      doneAt: null,
      doneBy: null,
      source: { kind: 'photo', messageId: new ObjectId() },
      clientKey: null,
      version: 1,
      createdAt: now,
      updatedAt: now,
      someNewField: true,
    } as unknown as CalendarEntryDoc;
    await t.db.calendarEntries.insertOne(future);
    // ... and GET answers 200 with the unknown values as plain strings (tolerant reader)
    const res = await t.app.inject({ method: 'GET', url: `${API}/calendar`, headers: bearer(b) });
    expect(res.statusCode).toBe(200);
    const got = res.json().items.find((e: { id: string }) => e.id === future._id.toHexString());
    expect(got).toMatchObject({ kind: 'anniversary_v2', repeat: 'daily', source: { kind: 'photo' } });
    expect(got).not.toHaveProperty('someNewField');

    const { title: _, ...missingTitle } = future as unknown as Record<string, unknown>;
    await expect(
      t.db.calendarEntries.insertOne({ ...missingTitle, _id: new ObjectId() } as unknown as CalendarEntryDoc),
    ).rejects.toSatisfy((e) => e instanceof MongoServerError && e.code === 121);
  });
});

describe('calendar write rate limit', () => {
  it('is per user, shared by POST/PATCH/DELETE, and answers 429 with Retry-After', async () => {
    const t2 = await createTestApp({ envOverrides: { RATE_LIMIT_CALENDAR_WRITES_PER_MIN: '3' } });
    try {
      const x = await register(t2.app, 'Xan');
      const y = await register(t2.app, 'Yul');
      await pair(t2.app, x, y);
      const call = (s: Session, method: 'POST' | 'PATCH' | 'DELETE', url: string, payload?: object) =>
        t2.app.inject({ method, url: `${API}${url}`, headers: bearer(s), ...(payload ? { payload } : {}) });
      const first = await call(x, 'POST', '/calendar', dinner);
      const id = first.json().entry.id;
      const second = await call(x, 'PATCH', `/calendar/${id}`, { title: 'b' });
      const third = await call(x, 'DELETE', `/calendar/${id}`);
      const fourth = await call(x, 'POST', '/calendar', dinner);
      expect([first.statusCode, second.statusCode, third.statusCode, fourth.statusCode]).toEqual([
        201, 200, 204, 429,
      ]);
      expect(fourth.json().error.code).toBe('rate_limited');
      expect(fourth.headers['retry-after']).toBeDefined();
      expect(
        (await t2.app.inject({ method: 'GET', url: `${API}/calendar`, headers: bearer(x) })).statusCode,
      ).toBe(200);
      expect((await call(y, 'POST', '/calendar', dinner)).statusCode).toBe(201);
    } finally {
      await t2.close();
    }
  });
});
