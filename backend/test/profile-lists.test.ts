import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { EVENT_TYPES } from '../src/db/types.js';
import { detectStaleEventTypes, staleEventTypes, VALIDATORS } from '../src/db/validators.js';
import { REDACT_PATHS } from '../src/lib/logger.js';
import { isValidBirthday } from '../src/lib/time.js';
import { API, bearer, createTestApp, pair, register, type Session, type TestApp } from './helpers/app.js';

let t: TestApp;
let a: Session;
let b: Session;
let c: Session;
let d: Session;
let coupleId: string;

beforeAll(async () => {
  t = await createTestApp({ envOverrides: { RATE_LIMIT_LIST_WRITES_PER_MIN: '1000' } });
  a = await register(t.app, 'Ira');
  b = await register(t.app, 'Jun');
  c = await register(t.app, 'Kai');
  d = await register(t.app, 'Lev');
  coupleId = await pair(t.app, a, b);
  await pair(t.app, c, d);
});
afterAll(async () => {
  await t.close();
});

const patchMe = (s: Session, payload: Record<string, unknown>) =>
  t.app.inject({ method: 'PATCH', url: `${API}/me`, headers: bearer(s), payload });
const getMe = async (s: Session) =>
  (await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(s) })).json();
const partnerEvents = async () =>
  t.db.events
    .find({ coupleId: new ObjectId(coupleId), type: 'partner.updated' })
    .sort({ seq: 1 })
    .toArray();

describe('profile: birthday / timeZone / shareLocalTime', () => {
  it('defaults: new users have birthday/timeZone null and shareLocalTime true', async () => {
    const me = await getMe(a);
    expect(me.user).toMatchObject({ birthday: null, timeZone: null, shareLocalTime: true });
    expect(me.partner).toEqual({
      id: b.user.id,
      displayName: 'Jun',
      avatarMediaId: null,
      birthday: null,
      timeZone: null,
      city: null,
    });
  });

  it('validates birthday: full date, private year, Feb 29, impossible dates', async () => {
    expect(isValidBirthday('1996-04-12')).toBe(true);
    expect(isValidBirthday('--04-12')).toBe(true);
    expect(isValidBirthday('--02-29')).toBe(true);
    expect(isValidBirthday('2024-02-29')).toBe(true);
    for (const bad of [
      '2026-02-30',
      '2023-02-29',
      '--02-30',
      '--13-01',
      '1996-00-10',
      '1996-4-12',
      '12-04',
      '',
    ]) {
      expect(isValidBirthday(bad), bad).toBe(false);
    }
    expect(isValidBirthday('2999-01-01')).toBe(false); // future

    const good = await patchMe(a, { birthday: '1996-04-12' });
    expect(good.statusCode).toBe(200);
    expect(good.json().user.birthday).toBe('1996-04-12');

    const priv = await patchMe(a, { birthday: '--02-29' });
    expect(priv.statusCode).toBe(200);
    expect(priv.json().user.birthday).toBe('--02-29');

    const bad = await patchMe(a, { birthday: '2026-02-30' });
    expect(bad.statusCode).toBe(400);
    expect(bad.json().error.code).toBe('validation_failed');

    const cleared = await patchMe(a, { birthday: null });
    expect(cleared.json().user.birthday).toBeNull();
  });

  it('validates timeZone (IANA only) and stores the canonical spelling', async () => {
    for (const bad of ['Mars/Olympus', '+05:30', 'not a zone', '']) {
      const res = await patchMe(a, { timeZone: bad });
      expect(res.statusCode, bad).toBe(400);
      expect(res.json().error.code).toBe('validation_failed');
    }
    const ok = await patchMe(a, { timeZone: 'europe/berlin' });
    expect(ok.statusCode).toBe(200);
    expect(ok.json().user.timeZone).toBe('Europe/Berlin');
    const kolkata = await patchMe(a, { timeZone: 'Asia/Kolkata' }); // kept, not rewritten to Asia/Calcutta
    expect(kolkata.json().user.timeZone).toBe('Asia/Kolkata');
    expect((await patchMe(a, { shareLocalTime: 'yes' })).statusCode).toBe(400);
  });

  it('partner sees birthday + timeZone (in /me, /couple and partner.updated); shareLocalTime=false hides timeZone', async () => {
    const before = (await partnerEvents()).length;
    const res = await patchMe(a, { birthday: '--07-01', timeZone: 'Europe/Berlin', shareLocalTime: true });
    expect(res.statusCode).toBe(200);
    expect(res.json().user).toMatchObject({
      birthday: '--07-01',
      timeZone: 'Europe/Berlin',
      shareLocalTime: true,
    });

    const expected = {
      id: a.user.id,
      displayName: 'Ira',
      avatarMediaId: null,
      birthday: '--07-01',
      timeZone: 'Europe/Berlin',
      city: null,
    };
    expect((await getMe(b)).partner).toEqual(expected);
    const couple = await t.app.inject({ method: 'GET', url: `${API}/couple`, headers: bearer(b) });
    expect(couple.json().partner).toEqual(expected);
    let events = await partnerEvents();
    expect(events.length).toBe(before + 1);
    expect(events.at(-1)!.payload).toEqual({ partner: expected });
    expect(events.at(-1)!.actorId.toHexString()).toBe(a.user.id);

    // hide local time: partner view loses timeZone, the user keeps seeing their own
    const hide = await patchMe(a, { shareLocalTime: false });
    expect(hide.json().user).toMatchObject({ timeZone: 'Europe/Berlin', shareLocalTime: false });
    expect((await getMe(b)).partner).toEqual({ ...expected, timeZone: null });
    events = await partnerEvents();
    expect(events.length).toBe(before + 2);
    expect(events.at(-1)!.payload).toEqual({ partner: { ...expected, timeZone: null } });

    // a no-op PATCH emits nothing
    await patchMe(a, { shareLocalTime: false });
    expect((await partnerEvents()).length).toBe(before + 2);

    // displayName change still emits, timeZone still hidden in the payload
    await patchMe(a, { displayName: 'Ira K' });
    events = await partnerEvents();
    expect(events.length).toBe(before + 3);
    expect(events.at(-1)!.payload).toEqual({
      partner: { ...expected, displayName: 'Ira K', timeZone: null },
    });

    await patchMe(a, { shareLocalTime: true });
    expect((await getMe(b)).partner.timeZone).toBe('Europe/Berlin');
  });

  it('legacy user documents without the new fields read as null / true', async () => {
    await t.db.users.updateOne(
      { _id: new ObjectId(b.user.id) },
      { $unset: { birthday: '', timeZone: '', shareLocalTime: '' } },
    );
    expect((await getMe(b)).user).toMatchObject({ birthday: null, timeZone: null, shareLocalTime: true });
    expect((await getMe(a)).partner).toMatchObject({ birthday: null, timeZone: null });
  });
});

// --- lists ------------------------------------------------------------------------------------

const createList = (s: Session, payload: Record<string, unknown>) =>
  t.app.inject({ method: 'POST', url: `${API}/lists`, headers: bearer(s), payload });
const patchList = (s: Session, id: string, payload: Record<string, unknown>) =>
  t.app.inject({ method: 'PATCH', url: `${API}/lists/${id}`, headers: bearer(s), payload });
const getLists = async (s: Session) =>
  (await t.app.inject({ method: 'GET', url: `${API}/lists`, headers: bearer(s) })).json().items as {
    id: string;
    title: string;
    pinned: boolean;
  }[];

describe('lists', () => {
  it('CRUD by both partners with list.* events; items doneBy/doneAt semantics; soft delete', async () => {
    const created = await createList(a, {
      title: '  Groceries  ',
      kind: 'checklist',
      items: [{ text: 'milk' }, { text: 'eggs', done: true }],
    });
    expect(created.statusCode).toBe(201);
    const list = created.json().list;
    expect(list).toEqual({
      id: expect.stringMatching(/^[a-f0-9]{24}$/),
      coupleId,
      createdBy: a.user.id,
      updatedBy: a.user.id,
      title: 'Groceries',
      kind: 'checklist',
      body: '',
      items: [
        {
          id: expect.stringMatching(/^[A-Za-z0-9_-]{12}$/),
          text: 'milk',
          done: false,
          doneBy: null,
          doneAt: null,
        },
        {
          id: expect.stringMatching(/^[A-Za-z0-9_-]{12}$/),
          text: 'eggs',
          done: true,
          doneBy: a.user.id,
          doneAt: expect.any(String),
        },
      ],
      pinned: false,
      version: 1,
      createdAt: expect.any(String),
      updatedAt: expect.any(String),
    });
    const [milk, eggs] = list.items;
    const createdEv = await t.db.events.findOne({ type: 'list.created', 'payload.list.id': list.id });
    expect(createdEv!.payload).toEqual({ list });
    expect(createdEv!.coupleId.toHexString()).toBe(coupleId);

    // partner B ticks milk, keeps eggs unchanged, adds a new item, edits the title
    t.clock.advance(1000);
    const upd = await patchList(b, list.id, {
      title: 'Groceries (Sat)',
      items: [
        { id: milk.id, text: 'milk', done: true },
        { id: eggs.id, text: 'eggs (free range)', done: true },
        { text: 'bread', done: false },
      ],
    });
    expect(upd.statusCode).toBe(200);
    const v2 = upd.json().list;
    expect(v2).toMatchObject({
      version: 2,
      updatedBy: b.user.id,
      createdBy: a.user.id,
      title: 'Groceries (Sat)',
    });
    expect(v2.items[0]).toMatchObject({ id: milk.id, done: true, doneBy: b.user.id });
    expect(v2.items[0].doneAt).not.toBe(eggs.doneAt);
    expect(v2.items[1]).toEqual({
      id: eggs.id,
      text: 'eggs (free range)',
      done: true,
      doneBy: a.user.id,
      doneAt: eggs.doneAt,
    });
    expect(v2.items[2]).toMatchObject({ text: 'bread', done: false, doneBy: null, doneAt: null });
    expect(v2.items[2].id).toMatch(/^[A-Za-z0-9_-]{12}$/);
    const updatedEv = await t.db.events.findOne({ type: 'list.updated', 'payload.list.version': 2 });
    expect(updatedEv!.payload).toEqual({ list: v2 });
    expect(updatedEv!.actorId.toHexString()).toBe(b.user.id);

    // clients may echo server-owned doneBy/doneAt back: ignored, not trusted
    const echoed = await patchList(a, list.id, {
      items: v2.items.map((i: Record<string, unknown>) => ({
        ...i,
        doneBy: a.user.id,
        doneAt: '2000-01-01T00:00:00.000Z',
      })),
    });
    expect(echoed.statusCode).toBe(200);
    expect(echoed.json().list.items).toEqual(v2.items);

    // A unticks eggs → doneBy/doneAt cleared; items that were removed are gone
    const v3 = (await patchList(a, list.id, { items: [{ id: eggs.id, text: 'eggs', done: false }] })).json()
      .list;
    expect(v3.version).toBe(4);
    expect(v3.items).toEqual([{ id: eggs.id, text: 'eggs', done: false, doneBy: null, doneAt: null }]);

    // both partners can read it
    expect((await getLists(a)).map((l) => l.id)).toContain(list.id);
    expect((await getLists(b)).map((l) => l.id)).toContain(list.id);

    // B deletes → 204, event, gone from GET, second delete 404
    const del = await t.app.inject({ method: 'DELETE', url: `${API}/lists/${list.id}`, headers: bearer(b) });
    expect(del.statusCode).toBe(204);
    const delEv = await t.db.events.findOne({ type: 'list.deleted', 'payload.listId': list.id });
    expect(delEv!.payload).toEqual({ listId: list.id });
    expect((await getLists(a)).map((l) => l.id)).not.toContain(list.id);
    const stored = await t.db.lists.findOne({ _id: new ObjectId(list.id) });
    expect(stored!.deletedAt).toBeInstanceOf(Date); // soft delete
    expect(
      (await t.app.inject({ method: 'DELETE', url: `${API}/lists/${list.id}`, headers: bearer(a) }))
        .statusCode,
    ).toBe(404);
    expect((await patchList(a, list.id, { title: 'x' })).statusCode).toBe(404);
  });

  it('notes keep a body; ordering is pinned first then updatedAt desc', async () => {
    const n1 = (await createList(a, { title: 'Trip ideas', kind: 'note', body: 'Lisbon\nPorto' })).json()
      .list;
    t.clock.advance(1000);
    const n2 = (await createList(b, { title: 'Gift ideas', kind: 'note' })).json().list;
    t.clock.advance(1000);
    const n3 = (await createList(a, { title: 'Pinned', kind: 'note', pinned: true })).json().list;
    expect(n1.body).toBe('Lisbon\nPorto');
    expect(n2.body).toBe('');
    let order = (await getLists(b)).map((l) => l.id);
    expect(order.slice(0, 3)).toEqual([n3.id, n2.id, n1.id]);
    t.clock.advance(1000);
    await patchList(b, n1.id, { body: '' });
    order = (await getLists(a)).map((l) => l.id);
    expect(order.slice(0, 3)).toEqual([n3.id, n1.id, n2.id]);
  });

  it('expectedVersion mismatch → 409 version_conflict with the current list', async () => {
    const list = (await createList(a, { title: 'Chores', kind: 'checklist' })).json().list;
    const ok = await patchList(a, list.id, { pinned: true, expectedVersion: 1 });
    expect(ok.statusCode).toBe(200);
    expect(ok.json().list.version).toBe(2);
    const stale = await patchList(b, list.id, { title: 'Stale edit', expectedVersion: 1 });
    expect(stale.statusCode).toBe(409);
    const body = stale.json();
    expect(body.error.code).toBe('version_conflict');
    expect(body.error.details.list).toEqual(ok.json().list);
    // without expectedVersion the write wins (last write wins)
    const lww = await patchList(b, list.id, { title: 'Chores!' });
    expect(lww.json().list).toMatchObject({ title: 'Chores!', version: 3 });
  });

  it('another couple cannot see or touch the lists (404), and sees only its own', async () => {
    const list = (await createList(a, { title: 'Private', kind: 'note', body: 'secret' })).json().list;
    expect((await patchList(c, list.id, { title: 'hijack' })).statusCode).toBe(404);
    expect(
      (await t.app.inject({ method: 'DELETE', url: `${API}/lists/${list.id}`, headers: bearer(c) }))
        .statusCode,
    ).toBe(404);
    expect((await getLists(c)).map((l) => l.id)).not.toContain(list.id);
    const own = (await createList(d, { title: 'CD list', kind: 'note' })).json().list;
    expect(own.coupleId).not.toBe(coupleId);
    expect((await getLists(c)).map((l) => l.id)).toEqual([own.id]);
    expect((await patchList(a, own.id, { title: 'x' })).statusCode).toBe(404);
    expect((await patchList(a, 'not-an-id', { title: 'x' })).statusCode).toBe(400);
  });

  it('unpaired users get 409 not_paired', async () => {
    const solo = await register(t.app, 'Solo');
    const res = await t.app.inject({ method: 'GET', url: `${API}/lists`, headers: bearer(solo) });
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('not_paired');
  });

  it('enforces limits: items ≤ 200, text ≤ 300, title 1–120, body ≤ 10000, unique item ids', async () => {
    const items = (n: number) => Array.from({ length: n }, (_, i) => ({ text: `item ${i}` }));
    expect((await createList(a, { title: 'Big', kind: 'checklist', items: items(201) })).statusCode).toBe(
      400,
    );
    const max = await createList(a, { title: 'Big', kind: 'checklist', items: items(200) });
    expect(max.statusCode).toBe(201);
    const id = max.json().list.id;
    const tooMany = Array.from({ length: 201 }, (_, i) => ({ text: `x${i}`, done: false }));
    expect((await patchList(a, id, { items: tooMany })).statusCode).toBe(400);
    expect((await createList(a, { title: '   ', kind: 'note' })).statusCode).toBe(400);
    expect((await createList(a, { title: 'x'.repeat(121), kind: 'note' })).statusCode).toBe(400);
    expect((await createList(a, { title: 'ok', kind: 'todo' })).statusCode).toBe(400);
    expect((await createList(a, { title: 'ok', kind: 'note', body: 'x'.repeat(10_001) })).statusCode).toBe(
      400,
    );
    expect(
      (await createList(a, { title: 'ok', kind: 'checklist', items: [{ text: 'x'.repeat(301) }] }))
        .statusCode,
    ).toBe(400);
    expect(
      (
        await patchList(a, id, {
          items: [
            { id: 'dup', text: 'a', done: false },
            { id: 'dup', text: 'b', done: false },
          ],
        })
      ).statusCode,
    ).toBe(400);
    expect((await patchList(a, id, { kind: 'note' })).statusCode).toBe(400); // kind is immutable
    const res = await createList(a, { title: 'ok', kind: 'note', body: 'x'.repeat(10_000) });
    expect(res.statusCode).toBe(201);
  });

  it('GET /me/export includes the couple lists (not deleted ones)', async () => {
    const keep = (await createList(b, { title: 'Export me', kind: 'note' })).json().list;
    const gone = (await createList(b, { title: 'Delete me', kind: 'note' })).json().list;
    await t.app.inject({ method: 'DELETE', url: `${API}/lists/${gone.id}`, headers: bearer(b) });
    const exp = await t.app.inject({ method: 'GET', url: `${API}/me/export`, headers: bearer(a) });
    expect(exp.statusCode).toBe(200);
    const ids = exp.json().lists.map((l: { id: string }) => l.id);
    expect(ids).toContain(keep.id);
    expect(ids).not.toContain(gone.id);
    expect(exp.json().user).toHaveProperty('shareLocalTime');
  });

  it('log redaction covers list content', () => {
    for (const p of ['title', '*.title', 'body', '*.body', 'items', '*.items', 'list', '*.list']) {
      expect(REDACT_PATHS).toContain(p);
    }
  });
});

describe('events validator compatibility (Atlas without collMod)', () => {
  it('new validator accepts any dotted event type; old strict enum is detected as stale', async () => {
    const pattern = VALIDATORS.events!.$jsonSchema.properties.type;
    expect(pattern).toEqual({ bsonType: 'string', pattern: expect.any(String) });
    for (const type of EVENT_TYPES) expect(new RegExp(pattern.pattern).test(type)).toBe(true);

    // simulate a database created by an older version: strict enum without list.*
    const oldTypes = EVENT_TYPES.filter((x) => !x.startsWith('list.'));
    const oldValidator = structuredClone(VALIDATORS.events!);
    oldValidator.$jsonSchema.properties.type = { enum: oldTypes };
    await t.ctx.mongoDb.command({ collMod: 'events', validator: oldValidator });
    try {
      await detectStaleEventTypes(t.ctx.mongoDb);
      expect(staleEventTypes).toEqual(['list.created', 'list.updated', 'list.deleted']);

      // a list write still succeeds; the event is delivered live but is not durable
      const publish = vi.spyOn(t.ctx.hub, 'publish');
      const res = await createList(a, { title: 'Under old validator', kind: 'note' });
      expect(res.statusCode).toBe(201);
      const id = res.json().list.id;
      expect(await t.db.events.findOne({ 'payload.list.id': id })).toBeNull();
      const live = publish.mock.calls.find(([ev]) => ev.type === 'list.created');
      expect(live?.[0].payload).toEqual({ list: res.json().list });
      publish.mockRestore();

      // existing event types keep working under the old validator
      const p = await patchMe(a, { displayName: 'Ira old-validator' });
      expect(p.statusCode).toBe(200);
      expect(
        await t.db.events.findOne({ 'payload.partner.displayName': 'Ira old-validator' }),
      ).not.toBeNull();
    } finally {
      await t.ctx.mongoDb.command({ collMod: 'events', validator: VALIDATORS.events });
      await detectStaleEventTypes(t.ctx.mongoDb);
    }
    expect(staleEventTypes).toEqual([]);
  });
});

describe('list write rate limit', () => {
  it('is per user, shared by POST/PATCH/DELETE, and answers 429 rate_limited with Retry-After', async () => {
    const t2 = await createTestApp({ envOverrides: { RATE_LIMIT_LIST_WRITES_PER_MIN: '3' } });
    try {
      const x = await register(t2.app, 'Xan');
      const y = await register(t2.app, 'Yul');
      await pair(t2.app, x, y);
      const post = () =>
        t2.app.inject({
          method: 'POST',
          url: `${API}/lists`,
          headers: bearer(x),
          payload: { title: 'a', kind: 'note' },
        });
      const first = await post();
      const id = first.json().list.id;
      const second = await t2.app.inject({
        method: 'PATCH',
        url: `${API}/lists/${id}`,
        headers: bearer(x),
        payload: { title: 'b' },
      });
      const third = await post();
      const fourth = await post();
      expect([first.statusCode, second.statusCode, third.statusCode, fourth.statusCode]).toEqual([
        201, 200, 201, 429,
      ]);
      expect(fourth.json().error.code).toBe('rate_limited');
      expect(fourth.headers['retry-after']).toBeDefined();
      // reads are not counted; the partner has an own budget
      expect(
        (await t2.app.inject({ method: 'GET', url: `${API}/lists`, headers: bearer(x) })).statusCode,
      ).toBe(200);
      expect(
        (
          await t2.app.inject({
            method: 'POST',
            url: `${API}/lists`,
            headers: bearer(y),
            payload: { title: 'c', kind: 'note' },
          })
        ).statusCode,
      ).toBe(201);
    } finally {
      await t2.close();
    }
  });
});
