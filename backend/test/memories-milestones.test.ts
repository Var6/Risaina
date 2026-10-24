import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  API,
  bearer,
  createTestApp,
  pair,
  register,
  type Session,
  type TestApp,
  uploadPhoto,
} from './helpers/app.js';

let t: TestApp;
let a: Session;
let b: Session;
let coupleId: string;

beforeAll(async () => {
  t = await createTestApp();
  a = await register(t.app, 'Mia');
  b = await register(t.app, 'Noa');
  coupleId = await pair(t.app, a, b);
});
afterAll(async () => {
  await t.close();
});

describe('memories', () => {
  it('create (photo only, ready only) → list/pinned filter → patch → soft delete, with events', async () => {
    const photo = await uploadPhoto(t, a);
    const notPhoto = await uploadPhoto(t, a, undefined, 'wallpaper');
    expect(
      (
        await t.app.inject({
          method: 'POST',
          url: `${API}/memories`,
          headers: bearer(b),
          payload: { mediaId: notPhoto },
        })
      ).statusCode,
    ).toBe(400);
    expect(
      (
        await t.app.inject({
          method: 'POST',
          url: `${API}/memories`,
          headers: bearer(b),
          payload: { mediaId: new ObjectId().toHexString() },
        })
      ).statusCode,
    ).toBe(404);

    // partner can create a memory from a photo either member uploaded
    const created = await t.app.inject({
      method: 'POST',
      url: `${API}/memories`,
      headers: bearer(b),
      payload: { mediaId: photo, caption: 'beach', takenAt: '2025-07-01T10:00:00.000Z' },
    });
    expect(created.statusCode).toBe(201);
    const memory = created.json().memory;
    expect(memory).toMatchObject({
      coupleId,
      createdBy: b.user.id,
      mediaId: photo,
      caption: 'beach',
      pinned: false,
      deletedAt: null,
    });

    const pinned = await t.app.inject({
      method: 'PATCH',
      url: `${API}/memories/${memory.id}`,
      headers: bearer(a),
      payload: { pinned: true, caption: null },
    });
    expect(pinned.statusCode).toBe(200);
    expect(pinned.json().memory).toMatchObject({ pinned: true, caption: null });

    const onlyPinned = await t.app.inject({
      method: 'GET',
      url: `${API}/memories?pinned=true`,
      headers: bearer(a),
    });
    expect(onlyPinned.json().items.map((m: { id: string }) => m.id)).toEqual([memory.id]);
    const notPinned = await t.app.inject({
      method: 'GET',
      url: `${API}/memories?pinned=false`,
      headers: bearer(a),
    });
    expect(notPinned.json().items).toEqual([]);

    const del = await t.app.inject({
      method: 'DELETE',
      url: `${API}/memories/${memory.id}`,
      headers: bearer(a),
    });
    expect(del.statusCode).toBe(204);
    expect(
      (await t.app.inject({ method: 'GET', url: `${API}/memories`, headers: bearer(a) })).json().items,
    ).toEqual([]);
    expect(
      (await t.app.inject({ method: 'DELETE', url: `${API}/memories/${memory.id}`, headers: bearer(a) }))
        .statusCode,
    ).toBe(404);

    const events = await t.db.events
      .find({ coupleId: new ObjectId(coupleId), type: /^memory\./ })
      .sort({ seq: 1 })
      .toArray();
    expect(events.map((e) => e.type)).toEqual(['memory.created', 'memory.updated', 'memory.deleted']);
    expect(events[0]!.payload).toEqual({ memory });
    expect(events[1]!.payload).toEqual({ memory: pinned.json().memory });
    expect(events[2]!.payload).toEqual({ memoryId: memory.id, deletedAt: expect.any(String) });
  });
});

describe('milestones', () => {
  it('CRUD with validation and events', async () => {
    const bad = await t.app.inject({
      method: 'POST',
      url: `${API}/milestones`,
      headers: bearer(a),
      payload: { title: 'x', date: '2024-13-45', kind: 'custom' },
    });
    expect(bad.statusCode).toBe(400);
    const created = await t.app.inject({
      method: 'POST',
      url: `${API}/milestones`,
      headers: bearer(a),
      payload: { title: 'Anniversary', date: '2024-02-14', kind: 'anniversary' },
    });
    expect(created.statusCode).toBe(201);
    const m = created.json().milestone;
    expect(m).toMatchObject({
      coupleId,
      createdBy: a.user.id,
      title: 'Anniversary',
      date: '2024-02-14',
      kind: 'anniversary',
      visibility: 'shared',
    });

    const second = await t.app.inject({
      method: 'POST',
      url: `${API}/milestones`,
      headers: bearer(b),
      payload: { title: 'Trip', date: '2023-09-01', kind: 'meeting' },
    });
    const list = await t.app.inject({ method: 'GET', url: `${API}/milestones`, headers: bearer(b) });
    expect(list.json().items.map((x: { date: string }) => x.date)).toEqual(['2023-09-01', '2024-02-14']);

    const patched = await t.app.inject({
      method: 'PATCH',
      url: `${API}/milestones/${m.id}`,
      headers: bearer(b),
      payload: { title: 'First kiss', kind: 'custom' },
    });
    expect(patched.statusCode).toBe(200);
    expect(patched.json().milestone).toMatchObject({ title: 'First kiss', kind: 'custom' });

    const del = await t.app.inject({
      method: 'DELETE',
      url: `${API}/milestones/${second.json().milestone.id}`,
      headers: bearer(a),
    });
    expect(del.statusCode).toBe(204);
    expect(
      (await t.app.inject({ method: 'GET', url: `${API}/milestones`, headers: bearer(b) })).json().items,
    ).toHaveLength(1);

    const events = await t.db.events
      .find({ coupleId: new ObjectId(coupleId), type: /^milestone\./ })
      .sort({ seq: 1 })
      .toArray();
    expect(events.map((e) => e.type)).toEqual([
      'milestone.created',
      'milestone.created',
      'milestone.updated',
      'milestone.deleted',
    ]);
    expect(events[0]!.payload).toEqual({ milestone: m });
    expect(events[3]!.payload).toEqual({ milestoneId: second.json().milestone.id });
  });
});
