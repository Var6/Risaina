import { MongoServerError, ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { VALIDATORS } from '../src/db/validators.js';
import { bodyToItems, itemsToBody, linePrefix, parseLine, splitText } from '../src/modules/lists/convert.js';
import { API, bearer, createTestApp, pair, register, type Session, type TestApp } from './helpers/app.js';

let t: TestApp;
let a: Session;
let b: Session;
let c: Session;
let coupleId: string;

beforeAll(async () => {
  t = await createTestApp({ envOverrides: { RATE_LIMIT_LIST_WRITES_PER_MIN: '10000' } });
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

type Item = {
  id: string;
  text: string;
  done: boolean;
  doneBy: string | null;
  doneAt: string | null;
} & Record<string, unknown>;

const createList = (s: Session, payload: Record<string, unknown>) =>
  t.app.inject({ method: 'POST', url: `${API}/lists`, headers: bearer(s), payload });
const patchList = (s: Session, id: string, payload: Record<string, unknown>) =>
  t.app.inject({ method: 'PATCH', url: `${API}/lists/${id}`, headers: bearer(s), payload });
const convert = (s: Session, id: string, payload: Record<string, unknown>) =>
  t.app.inject({ method: 'POST', url: `${API}/lists/${id}/convert`, headers: bearer(s), payload });
const getList = (s: Session, id: string) =>
  t.app.inject({ method: 'GET', url: `${API}/lists/${id}`, headers: bearer(s) });
/** A v75 save: only id/text/done per item, never marker/checkboxes. */
const v75Items = (items: Item[]) => items.map((i) => ({ id: i.id, text: i.text, done: i.done }));

describe('list format fields (marker, checkboxes)', () => {
  it('are stored on create, echoed by POST/GET, and default to none/true', async () => {
    const res = await createList(a, {
      title: 'Steps',
      kind: 'checklist',
      marker: 'number',
      checkboxes: false,
    });
    expect(res.statusCode).toBe(201);
    expect(res.json().list).toMatchObject({ marker: 'number', checkboxes: false });
    const got = await getList(b, res.json().list.id);
    expect(got.statusCode).toBe(200);
    expect(got.json().list).toEqual(res.json().list);

    const plain = await createList(a, { title: 'Plain', kind: 'checklist' });
    expect(plain.json().list).toMatchObject({ marker: 'none', checkboxes: true });
  });

  it('read a legacy list without the fields as none/true', async () => {
    const id = (await createList(a, { title: 'Old', kind: 'note' })).json().list.id;
    await t.db.lists.updateOne({ _id: new ObjectId(id) }, { $unset: { marker: '', checkboxes: '' } });
    expect((await getList(a, id)).json().list).toMatchObject({ marker: 'none', checkboxes: true });
  });

  it('reject an unknown marker or a non-boolean checkboxes (400)', async () => {
    expect((await createList(a, { title: 'x', kind: 'checklist', marker: 'roman' })).statusCode).toBe(400);
    expect((await createList(a, { title: 'x', kind: 'checklist', checkboxes: 'yes' })).statusCode).toBe(400);
    const id = (await createList(a, { title: 'x', kind: 'checklist' })).json().list.id;
    expect((await patchList(a, id, { marker: 'roman' })).statusCode).toBe(400);
  });

  it('PATCH changes them (version + 1, list.updated); a v75-shaped PATCH keeps them', async () => {
    const list = (
      await createList(a, { title: 'Trip', kind: 'checklist', items: [{ text: 'tickets' }] })
    ).json().list;
    const fmt = await patchList(b, list.id, { marker: 'bullet', checkboxes: false, expectedVersion: 1 });
    expect(fmt.statusCode).toBe(200);
    expect(fmt.json().list).toMatchObject({ marker: 'bullet', checkboxes: false, version: 2 });
    const ev = await t.db.events.findOne({ type: 'list.updated', 'payload.list.id': list.id });
    expect(ev!.payload).toEqual({ list: fmt.json().list });

    // what v75 sends: title and items, nothing else
    const old = await patchList(a, list.id, { title: 'Trip!', items: v75Items(fmt.json().list.items) });
    expect(old.statusCode).toBe(200);
    expect(old.json().list).toMatchObject({
      title: 'Trip!',
      marker: 'bullet',
      checkboxes: false,
      version: 3,
    });
  });

  it('are still part of the strict bodies: unknown keys and kind stay 400', async () => {
    const id = (await createList(a, { title: 'x', kind: 'note' })).json().list.id;
    expect((await patchList(a, id, { kind: 'checklist' })).statusCode).toBe(400);
    expect((await patchList(a, id, { format: 'number' })).statusCode).toBe(400);
    expect((await createList(a, { title: 'x', kind: 'note', colour: 'red' })).statusCode).toBe(400);
  });
});

describe('item attribution (addedBy, addedAt)', () => {
  it('stamps items on POST and new items on PATCH; keeps them through v75 saves', async () => {
    const created = (
      await createList(a, { title: 'Weekend', kind: 'checklist', items: [{ text: 'hike' }] })
    ).json().list;
    const [hike] = created.items as Item[];
    expect(hike).toMatchObject({ addedBy: a.user.id, addedAt: created.createdAt });

    t.clock.advance(60_000);
    const v2 = (
      await patchList(b, created.id, { items: [...v75Items(created.items), { text: 'picnic', done: false }] })
    ).json().list;
    expect(v2.items[0]).toMatchObject({ id: hike!.id, addedBy: a.user.id, addedAt: hike!.addedAt });
    expect(v2.items[1]).toMatchObject({ text: 'picnic', addedBy: b.user.id, addedAt: v2.updatedAt });

    // A (on v75) ticks picnic and edits hike: attribution stays with whoever added each item
    t.clock.advance(60_000);
    const v3 = (
      await patchList(a, created.id, {
        items: [
          { id: hike!.id, text: 'hike up the hill', done: false },
          { id: v2.items[1].id, text: 'picnic', done: true },
        ],
      })
    ).json().list;
    expect(v3.items[0]).toMatchObject({
      text: 'hike up the hill',
      addedBy: a.user.id,
      addedAt: hike!.addedAt,
    });
    expect(v3.items[1]).toMatchObject({
      done: true,
      doneBy: a.user.id,
      addedBy: b.user.id,
      addedAt: v2.updatedAt,
    });

    // ... and in the event payload and the GET /events replay
    const ev = await t.db.events.findOne({
      type: 'list.updated',
      'payload.list.version': 3,
      'payload.list.id': created.id,
    });
    expect(ev!.payload).toEqual({ list: v3 });
    const feed = await t.app.inject({
      method: 'GET',
      url: `${API}/events?since=0&limit=500`,
      headers: bearer(b),
    });
    const replayed = feed
      .json()
      .items.find(
        (e: { type: string; payload: { list?: { id: string; version: number } } }) =>
          e.type === 'list.updated' && e.payload.list?.id === created.id && e.payload.list?.version === 3,
      );
    expect(replayed.payload.list.items[1]).toMatchObject({ addedBy: b.user.id });
  });

  it('ignores addedBy/addedAt sent by a client (server-owned)', async () => {
    const list = (await createList(a, { title: 'Echo', kind: 'checklist', items: [{ text: 'one' }] })).json()
      .list;
    const forged = await patchList(b, list.id, {
      items: [
        { ...list.items[0], addedBy: b.user.id, addedAt: '2000-01-01T00:00:00.000Z' },
        { text: 'two', done: false, addedBy: a.user.id, addedAt: '2000-01-01T00:00:00.000Z' },
      ],
    });
    expect(forged.statusCode).toBe(200);
    const [one, two] = forged.json().list.items;
    expect(one).toMatchObject({ addedBy: a.user.id, addedAt: list.items[0].addedAt });
    expect(two).toMatchObject({ addedBy: b.user.id });
    expect(two.addedAt).not.toBe('2000-01-01T00:00:00.000Z');
  });

  it('reads legacy items as null and keeps them null on later saves', async () => {
    const list = (
      await createList(a, { title: 'Legacy', kind: 'checklist', items: [{ text: 'old' }] })
    ).json().list;
    await t.db.lists.updateOne(
      { _id: new ObjectId(list.id) },
      { $unset: { 'items.0.addedBy': '', 'items.0.addedAt': '' } },
    );
    expect((await getList(a, list.id)).json().list.items[0]).toMatchObject({ addedBy: null, addedAt: null });
    const saved = (
      await patchList(b, list.id, { items: [{ id: list.items[0].id, text: 'old', done: true }] })
    ).json().list;
    expect(saved.items[0]).toMatchObject({ addedBy: null, addedAt: null, doneBy: b.user.id });
  });

  it('keeps an item field it does not know through a PATCH (a field a later app adds)', async () => {
    const list = (await createList(a, { title: 'Future', kind: 'checklist', items: [{ text: 'x' }] })).json()
      .list;
    await t.db.lists.updateOne(
      { _id: new ObjectId(list.id) },
      { $set: { 'items.0.indent': 2, futureListField: 'kept' } },
    );
    const res = await patchList(b, list.id, {
      items: [{ id: list.items[0].id, text: 'x edited', done: false }],
    });
    expect(res.statusCode).toBe(200);
    // the DTO strips it, the document keeps it
    expect(res.json().list.items[0]).not.toHaveProperty('indent');
    const stored = await t.db.lists.findOne({ _id: new ObjectId(list.id) });
    expect((stored!.items[0] as unknown as Record<string, unknown>).indent).toBe(2);
    expect(stored!.items[0]!.text).toBe('x edited');
    expect((stored as unknown as Record<string, unknown>).futureListField).toBe('kept');
  });
});

describe('a 409 can be merged and sent again (no lost edits)', () => {
  it('returns the current list in details.list; resending on top of it keeps both changes', async () => {
    const list = (
      await createList(a, {
        title: 'Ideas',
        kind: 'checklist',
        items: [{ text: 'Picnic' }, { text: 'Museum' }],
      })
    ).json().list;
    // partner ticks Picnic → v2
    const theirs = (
      await patchList(b, list.id, {
        items: [
          { id: list.items[0].id, text: 'Picnic', done: true },
          { id: list.items[1].id, text: 'Museum', done: false },
        ],
        expectedVersion: 1,
      })
    ).json().list;
    // this phone still on v1 adds Stargazing
    const mine = [...v75Items(list.items), { text: 'Stargazing', done: false }];
    const conflict = await patchList(a, list.id, { items: mine, expectedVersion: 1 });
    expect(conflict.statusCode).toBe(409);
    expect(conflict.json().error.code).toBe('version_conflict');
    expect(conflict.json().error.details.list).toEqual(theirs);
    // the client merges: their items + my new one, against their version
    const merged = await patchList(a, list.id, {
      items: [...v75Items(theirs.items), { text: 'Stargazing', done: false }],
      expectedVersion: theirs.version,
    });
    expect(merged.statusCode).toBe(200);
    const items = merged.json().list.items as Item[];
    expect(items.map((i) => i.text)).toEqual(['Picnic', 'Museum', 'Stargazing']);
    expect(items[0]).toMatchObject({ done: true, doneBy: b.user.id, doneAt: theirs.items[0].doneAt });
  });
});

describe('GET /lists/:id', () => {
  it('returns one list, 404 across couples or once deleted, 400 for a malformed id', async () => {
    const list = (await createList(a, { title: 'One', kind: 'note', body: 'hi' })).json().list;
    expect((await getList(b, list.id)).json()).toEqual({ list });
    expect((await getList(c, list.id)).statusCode).toBe(404);
    expect((await getList(a, new ObjectId().toHexString())).statusCode).toBe(404);
    expect((await getList(a, 'nope')).statusCode).toBe(400);
    await t.app.inject({ method: 'DELETE', url: `${API}/lists/${list.id}`, headers: bearer(a) });
    expect((await getList(a, list.id)).statusCode).toBe(404);
  });
});

describe('POST /lists clientKey (idempotent create)', () => {
  it('a repeat answers 200 with the first list and emits nothing; the key is per couple', async () => {
    const key = `draft-${new ObjectId().toHexString()}`;
    const first = await createList(a, { title: 'Offline list', kind: 'checklist', clientKey: key });
    expect(first.statusCode).toBe(201);
    expect(first.json().list).not.toHaveProperty('clientKey');
    const before = await t.db.events.countDocuments({ type: 'list.created' });
    // a retry (even from the partner's account, or with a different body) is the same create
    const again = await createList(b, { title: 'Different title', kind: 'note', clientKey: key });
    expect(again.statusCode).toBe(200);
    expect(again.json().list).toEqual(first.json().list);
    expect(await t.db.events.countDocuments({ type: 'list.created' })).toBe(before);
    expect(await t.db.lists.countDocuments({ coupleId: new ObjectId(coupleId), clientKey: key })).toBe(1);
    // another couple may use the same key
    const other = await createList(c, { title: 'Theirs', kind: 'note', clientKey: key });
    expect(other.statusCode).toBe(201);
    expect(other.json().list.id).not.toBe(first.json().list.id);
  });

  it('concurrent creates with one key make one list', async () => {
    const key = `race-${new ObjectId().toHexString()}`;
    const results = await Promise.all(
      Array.from({ length: 4 }, () => createList(a, { title: 'Race', kind: 'note', clientKey: key })),
    );
    expect(results.map((r) => r.statusCode).sort()).toEqual([200, 200, 200, 201]);
    expect(new Set(results.map((r) => r.json().list.id)).size).toBe(1);
    expect(await t.db.lists.countDocuments({ clientKey: key })).toBe(1);
  });

  it('a repeat after the list was deleted is 404; a key over 64 characters is 400', async () => {
    const key = `gone-${new ObjectId().toHexString()}`;
    const id = (await createList(a, { title: 'Gone', kind: 'note', clientKey: key })).json().list.id;
    await t.app.inject({ method: 'DELETE', url: `${API}/lists/${id}`, headers: bearer(b) });
    expect((await createList(a, { title: 'Gone', kind: 'note', clientKey: key })).statusCode).toBe(404);
    expect((await createList(a, { title: 'x', kind: 'note', clientKey: 'k'.repeat(65) })).statusCode).toBe(
      400,
    );
    expect((await createList(a, { title: 'x', kind: 'note', clientKey: '' })).statusCode).toBe(400);
    expect((await createList(a, { title: 'x', kind: 'note', clientKey: 'k'.repeat(64) })).statusCode).toBe(
      201,
    );
  });
});

describe('POST /lists/:id/convert', () => {
  const long = `${'word '.repeat(130)}end`; // 654 characters

  it('note → checklist: lines become items without markers; [x] ticks; long lines are cut', async () => {
    const body = [
      '- milk',
      '* eggs',
      '• bread',
      '1. butter',
      '2) jam',
      '[ ] tea',
      '[x] coffee',
      '- [X] sugar',
      '☑ 3. honey',
      '',
      '   plain line   ',
      '-5 °C outside',
      long,
    ].join('\n');
    const note = (await createList(a, { title: 'Shop', kind: 'note', body })).json().list;
    t.clock.advance(1000);
    const res = await convert(b, note.id, { to: 'checklist', expectedVersion: 1 });
    expect(res.statusCode).toBe(200);
    const list = res.json().list;
    expect(list).toMatchObject({ kind: 'checklist', body: '', version: 2, updatedBy: b.user.id });
    const items = list.items as Item[];
    expect(items.slice(0, 11).map((i) => [i.text, i.done])).toEqual([
      ['milk', false],
      ['eggs', false],
      ['bread', false],
      ['butter', false],
      ['jam', false],
      ['tea', false],
      ['coffee', true],
      ['sugar', true],
      ['honey', true],
      ['plain line', false],
      ['-5 °C outside', false],
    ]);
    const pieces = items.slice(11).map((i) => i.text);
    expect(pieces.length).toBe(3);
    expect(pieces.every((p) => p.length <= 300)).toBe(true);
    expect(pieces.join(' ')).toBe(long);
    for (const i of items) {
      expect(i.id).toMatch(/^[A-Za-z0-9_-]{12}$/);
      expect(i).toMatchObject({ addedBy: b.user.id, addedAt: list.updatedAt });
      expect(i.doneBy).toBe(i.done ? b.user.id : null);
    }
    const ev = await t.db.events.findOne({ type: 'list.updated', 'payload.list.id': note.id });
    expect(ev!.payload).toEqual({ list });
  });

  it('applies marker/checkboxes from the request', async () => {
    const note = (await createList(a, { title: 'Steps', kind: 'note', body: '1. one\n2. two' })).json().list;
    const res = await convert(a, note.id, { to: 'checklist', marker: 'number', checkboxes: false });
    expect(res.json().list).toMatchObject({ marker: 'number', checkboxes: false });
    expect(res.json().list.items.map((i: Item) => i.text)).toEqual(['one', 'two']);
  });

  it('keeps at most 200 items; the remaining lines stay in the body', async () => {
    const lines = Array.from({ length: 205 }, (_, i) => `- task ${i + 1}`);
    const note = (await createList(a, { title: 'Many', kind: 'note', body: lines.join('\n') })).json().list;
    const res = (await convert(a, note.id, { to: 'checklist' })).json().list;
    expect(res.items).toHaveLength(200);
    expect(res.items[199].text).toBe('task 200');
    expect(res.body).toBe(lines.slice(200).join('\n'));

    // items an older app saved onto a note come first and count towards the 200
    const withItems = (await createList(a, { title: 'Mixed', kind: 'note', body: lines.join('\n') })).json()
      .list;
    await t.db.lists.updateOne(
      { _id: new ObjectId(withItems.id) },
      { $set: { items: [{ id: 'olditem', text: 'kept', done: false, doneBy: null, doneAt: null }] } },
    );
    const mixed = (await convert(a, withItems.id, { to: 'checklist' })).json().list;
    expect(mixed.items).toHaveLength(200);
    expect(mixed.items[0]).toMatchObject({ id: 'olditem', text: 'kept', addedBy: null });
    expect(mixed.body).toBe(lines.slice(199).join('\n'));
  });

  it('checklist → note: items become lines drawn as the list was shown; items are emptied', async () => {
    const list = (
      await createList(a, {
        title: 'Todo',
        kind: 'checklist',
        marker: 'number',
        body: 'Before we go:',
        items: [{ text: 'pack' }, { text: 'book cab', done: true }],
      })
    ).json().list;
    const res = await convert(b, list.id, { to: 'note' });
    expect(res.statusCode).toBe(200);
    expect(res.json().list).toMatchObject({
      kind: 'note',
      items: [],
      body: 'Before we go:\n1. ☐ pack\n2. ☑ book cab',
      marker: 'number',
      checkboxes: true,
      version: 2,
    });

    const bullets = (
      await createList(a, {
        title: 'B',
        kind: 'checklist',
        marker: 'bullet',
        checkboxes: false,
        items: [{ text: 'x' }, { text: 'y' }],
      })
    ).json().list;
    expect((await convert(a, bullets.id, { to: 'note' })).json().list.body).toBe('• x\n• y');
  });

  it('round-trips: checklist → note → checklist keeps every text and tick', async () => {
    const items = [{ text: 'alpha' }, { text: 'beta', done: true }, { text: '3 apples' }];
    const list = (await createList(a, { title: 'RT', kind: 'checklist', marker: 'number', items })).json()
      .list;
    await convert(a, list.id, { to: 'note' });
    const back = (await convert(a, list.id, { to: 'checklist' })).json().list;
    expect(back.items.map((i: Item) => [i.text, i.done])).toEqual([
      ['alpha', false],
      ['beta', true],
      ['3 apples', false],
    ]);
    expect(back.body).toBe('');
  });

  it('checklist → note: items that would take the body over 10,000 characters stay items', async () => {
    const items = Array.from({ length: 200 }, (_, i) => ({ text: `${i}`.padEnd(290, 'x') }));
    const list = (await createList(a, { title: 'Huge', kind: 'checklist', items })).json().list;
    const res = (await convert(a, list.id, { to: 'note' })).json().list;
    expect(res.kind).toBe('note');
    expect(res.body.length).toBeLessThanOrEqual(10_000);
    const lines = res.body.split('\n').length;
    expect(lines).toBeGreaterThan(0);
    expect(res.items).toHaveLength(200 - lines);
    expect(res.items[0].text).toBe(`${lines}`.padEnd(290, 'x'));
  });

  it('to the current kind only applies the format; other fields are untouched', async () => {
    const list = (await createList(a, { title: 'Same', kind: 'checklist', items: [{ text: 'a' }] })).json()
      .list;
    const res = (await convert(a, list.id, { to: 'checklist', checkboxes: false })).json().list;
    expect(res).toMatchObject({ kind: 'checklist', checkboxes: false, body: '', version: 2 });
    expect(res.items).toEqual(list.items);
  });

  it('409 on a stale expectedVersion, 404 across couples or deleted, 400 for bad bodies', async () => {
    const note = (await createList(a, { title: 'Conv', kind: 'note', body: 'a' })).json().list;
    await patchList(b, note.id, { body: 'a\nb' });
    const stale = await convert(a, note.id, { to: 'checklist', expectedVersion: 1 });
    expect(stale.statusCode).toBe(409);
    expect(stale.json().error.code).toBe('version_conflict');
    expect(stale.json().error.details.list).toMatchObject({ version: 2, kind: 'note', body: 'a\nb' });
    expect((await convert(c, note.id, { to: 'checklist' })).statusCode).toBe(404);
    expect((await convert(a, note.id, { to: 'todo' })).statusCode).toBe(400);
    expect((await convert(a, note.id, {})).statusCode).toBe(400);
    expect((await convert(a, note.id, { to: 'checklist', title: 'x' })).statusCode).toBe(400);
    await t.app.inject({ method: 'DELETE', url: `${API}/lists/${note.id}`, headers: bearer(a) });
    expect((await convert(a, note.id, { to: 'checklist' })).statusCode).toBe(404);
  });
});

describe('under the live (pre-change) lists validator', () => {
  it('create, patch and convert with the new fields succeed; a new kind is still refused (121)', async () => {
    // The validator Atlas keeps (no collMod there): the current one without the optional fields.
    const old = structuredClone(VALIDATORS.lists!);
    const props = old.$jsonSchema.properties;
    for (const k of ['marker', 'checkboxes', 'clientKey']) delete props[k];
    delete props.items.items.properties.addedBy;
    delete props.items.items.properties.addedAt;
    await t.ctx.mongoDb.command({ collMod: 'lists', validator: old });
    try {
      const created = await createList(a, {
        title: 'Old DB',
        kind: 'note',
        body: '- a\n- b',
        marker: 'bullet',
        clientKey: `old-${new ObjectId().toHexString()}`,
      });
      expect(created.statusCode).toBe(201);
      const id = created.json().list.id;
      expect((await convert(b, id, { to: 'checklist', checkboxes: false })).statusCode).toBe(200);
      const patched = await patchList(a, id, { marker: 'number', items: [{ text: 'c', done: true }] });
      expect(patched.statusCode).toBe(200);
      expect(patched.json().list).toMatchObject({ marker: 'number', checkboxes: false });

      const bad = t.db.lists.insertOne({
        _id: new ObjectId(),
        coupleId: new ObjectId(coupleId),
        createdBy: new ObjectId(a.user.id),
        updatedBy: new ObjectId(a.user.id),
        title: 'x',
        kind: 'todo' as 'note',
        body: '',
        items: [],
        pinned: false,
        version: 1,
        createdAt: new Date(),
        updatedAt: new Date(),
        deletedAt: null,
      });
      await expect(bad).rejects.toSatisfy((e) => e instanceof MongoServerError && e.code === 121);
    } finally {
      await t.ctx.mongoDb.command({ collMod: 'lists', validator: VALIDATORS.lists });
    }
  });
});

describe('list writes share one rate limit (convert included)', () => {
  it('counts POST, PATCH and convert against the same per-user budget', async () => {
    const t2 = await createTestApp({ envOverrides: { RATE_LIMIT_LIST_WRITES_PER_MIN: '3' } });
    try {
      const x = await register(t2.app, 'Xia');
      const y = await register(t2.app, 'Yan');
      await pair(t2.app, x, y);
      const inject = (method: 'POST' | 'PATCH', url: string, payload: object) =>
        t2.app.inject({ method, url: `${API}${url}`, headers: bearer(x), payload });
      const created = await inject('POST', '/lists', { title: 'a', kind: 'note', body: 'x' });
      const id = created.json().list.id;
      const converted = await inject('POST', `/lists/${id}/convert`, { to: 'checklist' });
      const patched = await inject('PATCH', `/lists/${id}`, { title: 'b' });
      const over = await inject('POST', `/lists/${id}/convert`, { to: 'note' });
      expect([created.statusCode, converted.statusCode, patched.statusCode, over.statusCode]).toEqual([
        201, 200, 200, 429,
      ]);
      expect(over.json().error.code).toBe('rate_limited');
      // GET /lists/:id is a read: not counted
      expect(
        (await t2.app.inject({ method: 'GET', url: `${API}/lists/${id}`, headers: bearer(x) })).statusCode,
      ).toBe(200);
    } finally {
      await t2.close();
    }
  });
});

describe('convert helpers (pure)', () => {
  it('parseLine strips one marker and one box, in either order', () => {
    expect(parseLine('  - milk ')).toEqual({ text: 'milk', done: false });
    expect(parseLine('12) twelve')).toEqual({ text: 'twelve', done: false });
    expect(parseLine('[x] done')).toEqual({ text: 'done', done: true });
    expect(parseLine('[] empty box')).toEqual({ text: 'empty box', done: false });
    expect(parseLine('☐ open')).toEqual({ text: 'open', done: false });
    expect(parseLine('✅ booked')).toEqual({ text: 'booked', done: true });
    expect(parseLine('☑ 2. both')).toEqual({ text: 'both', done: true });
    expect(parseLine('- - twice')).toEqual({ text: '- twice', done: false });
    expect(parseLine('1.5 kg rice')).toEqual({ text: '1.5 kg rice', done: false });
    expect(parseLine('2026. a year')).toEqual({ text: '2026. a year', done: false });
    expect(parseLine('-')).toBeNull();
    expect(parseLine('[ ]')).toBeNull();
    expect(parseLine('   ')).toBeNull();
  });

  it('splitText cuts at spaces, hard-cuts a long word, never splits a surrogate pair', () => {
    expect(splitText('a b c', 300)).toEqual(['a b c']);
    expect(splitText('aaaa bbbb cccc', 9)).toEqual(['aaaa bbbb', 'cccc']);
    expect(splitText('x'.repeat(25), 10)).toEqual(['x'.repeat(10), 'x'.repeat(10), 'x'.repeat(5)]);
    const emoji = `${'a'.repeat(9)}😀b`; // the emoji spans units 9 and 10
    const pieces = splitText(emoji, 10);
    expect(pieces).toEqual(['a'.repeat(9), '😀b']);
    for (const p of pieces) expect(p.length).toBeLessThanOrEqual(10);
  });

  it('bodyToItems keeps the whole line that does not fit, and everything after it', () => {
    const { items, rest } = bodyToItems('one\ntwo\nthree four\nfive', 3, 5);
    expect(items.map((i) => i.text)).toEqual(['one', 'two']);
    expect(rest).toBe('three four\nfive');
    expect(bodyToItems('\r\n- a\r\n\r\n', 10, 300)).toEqual({
      items: [{ text: 'a', done: false }],
      rest: '',
    });
  });

  it('itemsToBody draws items by format and stops before the limit', () => {
    const item = (text: string, done = false) => ({ id: text, text, done, doneBy: null, doneAt: null });
    expect(linePrefix(0, false, 'none', true)).toBe('☐ ');
    expect(linePrefix(4, true, 'number', true)).toBe('5. ☑ ');
    expect(linePrefix(0, true, 'bullet', false)).toBe('• ');
    expect(linePrefix(0, true, 'none', false)).toBe('');
    expect(
      itemsToBody([item('a'), item('b', true)], { marker: 'none', checkboxes: true }, 'Top\n\n', 100),
    ).toEqual({
      body: 'Top\n☐ a\n☑ b',
      rest: [],
    });
    const out = itemsToBody([item('aaaa'), item('bbbb')], { marker: 'none', checkboxes: false }, '', 6);
    expect(out).toEqual({ body: 'aaaa', rest: [item('bbbb')] });
  });
});
