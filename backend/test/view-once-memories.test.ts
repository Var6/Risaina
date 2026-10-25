import { randomUUID } from 'node:crypto';
import { ObjectId } from 'mongodb';
import { pino } from 'pino';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { EVENT_TYPES } from '../src/db/types.js';
import { detectStaleEventTypes, staleEventTypes, VALIDATORS } from '../src/db/validators.js';
import { CleanupWorker } from '../src/workers/cleanup.js';
import {
  API,
  bearer,
  createTestApp,
  pair,
  register,
  type Session,
  type TestApp,
  TINY_PNG,
  uploadPhoto,
} from './helpers/app.js';

let t: TestApp;
let a: Session;
let b: Session;
let c: Session;

beforeAll(async () => {
  t = await createTestApp({ envOverrides: { VIEW_ONCE_URL_TTL_SECONDS: '120' } });
  a = await register(t.app, 'Ana');
  b = await register(t.app, 'Ben');
  c = await register(t.app, 'Cal');
  await pair(t.app, a, b);
  await pair(t.app, c, await register(t.app, 'Dot'));
});
afterAll(async () => {
  await t.close();
});

async function sendPhoto(sender: Session, mediaId: string, extra: Record<string, unknown> = {}) {
  return t.app.inject({
    method: 'POST',
    url: `${API}/messages`,
    headers: bearer(sender),
    payload: { type: 'photo', mediaId, idempotencyKey: randomUUID(), ...extra },
  });
}

const open = (s: Session, id: string) =>
  t.app.inject({ method: 'POST', url: `${API}/messages/${id}/open`, headers: bearer(s) });
const mediaUrl = (s: Session, id: string) =>
  t.app.inject({ method: 'GET', url: `${API}/media/${id}/url`, headers: bearer(s) });

function cleanupWorker() {
  return new CleanupWorker(t.db, t.storage, t.clock.now, pino({ level: 'silent' }), {
    intervalMs: 60_000,
    abandonedUploadHours: 24,
    viewOnceUrlTtlSeconds: t.env.viewOnce.urlTtlSeconds,
    viewOnceUnopenedDays: t.env.viewOnce.unopenedDays,
  });
}

describe('auto-memories from chat photos', () => {
  it('a photo message creates a memory with sourceMessageId; deleting the message deletes it', async () => {
    const mediaId = await uploadPhoto(t, a);
    const res = await sendPhoto(a, mediaId);
    expect(res.statusCode).toBe(201);
    const message = res.json().message;
    expect(message).toMatchObject({ viewOnce: false, openedAt: null, viewOnceExpired: false });

    const list = await t.app.inject({ method: 'GET', url: `${API}/memories`, headers: bearer(b) });
    const memory = list
      .json()
      .items.find((m: { sourceMessageId: string }) => m.sourceMessageId === message.id);
    expect(memory).toMatchObject({
      mediaId,
      caption: null,
      takenAt: message.createdAt,
      createdBy: a.user.id,
      sourceMessageId: message.id,
      deletedAt: null,
    });
    const created = await t.db.events.findOne({ type: 'memory.created', 'payload.memory.id': memory.id });
    expect(created!.actorId.toHexString()).toBe(a.user.id);
    expect(created!.payload).toEqual({ memory });

    // idempotent replay creates nothing new
    const replay = await t.app.inject({
      method: 'POST',
      url: `${API}/messages`,
      headers: bearer(a),
      payload: { type: 'photo', mediaId, idempotencyKey: message.idempotencyKey },
    });
    expect(replay.statusCode).toBe(200);
    expect(await t.db.memories.countDocuments({ sourceMessageId: new ObjectId(message.id) })).toBe(1);

    const del = await t.app.inject({
      method: 'DELETE',
      url: `${API}/messages/${message.id}`,
      headers: bearer(a),
    });
    expect(del.statusCode).toBe(200);
    const after = await t.db.memories.findOne({ _id: new ObjectId(memory.id) });
    expect(after!.deletedAt).toBeInstanceOf(Date);
    const deleted = await t.db.events.findOne({ type: 'memory.deleted', 'payload.memoryId': memory.id });
    expect(deleted!.payload).toEqual({ memoryId: memory.id, deletedAt: after!.deletedAt!.toISOString() });
  });

  it('deleting the memory alone keeps the media while the message references it', async () => {
    const mediaId = await uploadPhoto(t, a);
    const message = (await sendPhoto(a, mediaId)).json().message;
    const memory = await t.db.memories.findOne({ sourceMessageId: new ObjectId(message.id) });
    const del = await t.app.inject({
      method: 'DELETE',
      url: `${API}/memories/${memory!._id.toHexString()}`,
      headers: bearer(b),
    });
    expect(del.statusCode).toBe(204);
    const media = await t.db.media.findOne({ _id: new ObjectId(mediaId) });
    expect(media).toMatchObject({ status: 'ready', objectDeleted: false });
    expect(await t.storage.head(media!.storageKey)).not.toBeNull();
    expect((await mediaUrl(b, mediaId)).statusCode).toBe(200);
    // deleting the message later does not trip over the already-deleted memory
    expect(
      (await t.app.inject({ method: 'DELETE', url: `${API}/messages/${message.id}`, headers: bearer(a) }))
        .statusCode,
    ).toBe(200);
  });

  it('voice and text messages create no memory', async () => {
    const before = await t.db.memories.countDocuments({});
    await t.app.inject({
      method: 'POST',
      url: `${API}/messages`,
      headers: bearer(a),
      payload: { type: 'text', text: 'hi', idempotencyKey: randomUUID() },
    });
    expect(await t.db.memories.countDocuments({})).toBe(before);
  });
});

describe('view-once photos', () => {
  it('viewOnce is rejected for non-photo types', async () => {
    const res = await t.app.inject({
      method: 'POST',
      url: `${API}/messages`,
      headers: bearer(a),
      payload: { type: 'text', text: 'secret', viewOnce: true, idempotencyKey: randomUUID() },
    });
    expect(res.statusCode).toBe(400);
    expect(res.json().error.code).toBe('validation_failed');
  });

  it('creates no memory; media url is 403 view_once for both; recipient opens once; sender 403', async () => {
    const memoriesBefore = await t.db.memories.countDocuments({});
    const mediaId = await uploadPhoto(t, a);
    const res = await sendPhoto(a, mediaId, { viewOnce: true });
    expect(res.statusCode).toBe(201);
    const message = res.json().message;
    expect(message).toMatchObject({ viewOnce: true, openedAt: null, viewOnceExpired: false, mediaId });
    expect(await t.db.memories.countDocuments({})).toBe(memoriesBefore);

    for (const s of [a, b]) {
      const u = await mediaUrl(s, mediaId);
      expect(u.statusCode).toBe(403);
      expect(u.json().error.code).toBe('view_once');
    }
    // the media cannot be reused in another message, a memory, or by C's couple
    expect((await sendPhoto(a, mediaId)).statusCode).toBe(400);
    const mem = await t.app.inject({
      method: 'POST',
      url: `${API}/memories`,
      headers: bearer(a),
      payload: { mediaId },
    });
    expect(mem.statusCode).toBe(400);
    expect((await open(c, message.id)).statusCode).toBe(404);

    const bySender = await open(a, message.id);
    expect(bySender.statusCode).toBe(403);
    expect(bySender.json().error.code).toBe('forbidden');

    const first = await open(b, message.id);
    expect(first.statusCode).toBe(200);
    const body = first.json();
    expect(body.message.openedAt).not.toBeNull();
    expect(body.message.id).toBe(message.id);
    const ttl = new Date(body.expiresAt).getTime() - t.clock.now().getTime();
    expect(ttl).toBeGreaterThan(100_000);
    expect(ttl).toBeLessThanOrEqual(120_000);
    const dl = await t.app.inject({ method: 'GET', url: body.url });
    expect(dl.statusCode).toBe(200);
    expect(dl.rawPayload.equals(TINY_PNG)).toBe(true);

    const ev = await t.db.events.findOne({ type: 'message.opened', 'payload.messageId': message.id });
    expect(ev!.payload).toEqual({ messageId: message.id, openedAt: body.message.openedAt });
    expect(ev!.actorId.toHexString()).toBe(b.user.id);

    const second = await open(b, message.id);
    expect(second.statusCode).toBe(410);
    expect(second.json().error.code).toBe('view_once_consumed');
    expect((await mediaUrl(b, mediaId)).json().error.code).toBe('view_once');

    // the history reflects the opened state
    const hist = await t.app.inject({ method: 'GET', url: `${API}/messages`, headers: bearer(a) });
    const row = hist.json().items.find((m: { id: string }) => m.id === message.id);
    expect(row).toMatchObject({ viewOnce: true, openedAt: body.message.openedAt, viewOnceExpired: false });
  });

  it('open on a message that is not a view-once photo → 400', async () => {
    const mediaId = await uploadPhoto(t, a);
    const normal = (await sendPhoto(a, mediaId)).json().message;
    const res = await open(b, normal.id);
    expect(res.statusCode).toBe(400);
    expect(res.json().error.code).toBe('validation_failed');
  });

  it('viewOnce with a media already shared elsewhere → 409 conflict', async () => {
    const mediaId = await uploadPhoto(t, a);
    expect((await sendPhoto(a, mediaId)).statusCode).toBe(201);
    const res = await sendPhoto(a, mediaId, { viewOnce: true });
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('conflict');
  });

  it('cleanup deletes the opened object only after the URL TTL + 60 s grace (fake clock)', async () => {
    const mediaId = await uploadPhoto(t, a);
    const message = (await sendPhoto(a, mediaId, { viewOnce: true })).json().message;
    expect((await open(b, message.id)).statusCode).toBe(200);
    const key = (await t.db.media.findOne({ _id: new ObjectId(mediaId) }))!.storageKey;
    const cleanup = cleanupWorker();

    t.clock.advance(121_000); // URL expired, still within grace
    await cleanup.runOnce();
    expect(await t.storage.head(key)).not.toBeNull();

    t.clock.advance(60_000); // > TTL + 60 s after opening
    const report = await cleanup.runOnce();
    expect(report.viewOnceOpenedPurged).toBeGreaterThanOrEqual(1);
    expect(await t.storage.head(key)).toBeNull();
    const media = await t.db.media.findOne({ _id: new ObjectId(mediaId) });
    expect(media).toMatchObject({ status: 'deleted', objectDeleted: true });
    // message row is kept
    const msg = await t.db.messages.findOne({ _id: new ObjectId(message.id) });
    expect(msg!.deletedAt).toBeNull();
  });

  it('export keeps view-once rows without a media reference', async () => {
    const mediaId = await uploadPhoto(t, a);
    const message = (await sendPhoto(a, mediaId, { viewOnce: true })).json().message;
    const exp = await t.app.inject({ method: 'GET', url: `${API}/me/export`, headers: bearer(b) });
    expect(exp.statusCode).toBe(200);
    const row = exp.json().messages.find((m: { id: string }) => m.id === message.id);
    expect(row).toMatchObject({ viewOnce: true, mediaId: null });
    expect(JSON.stringify(exp.json())).not.toContain(mediaId);
  });
});

describe('view-once: unopened expiry', () => {
  it('purges unopened view-once photos after N days and reports viewOnceExpired', async () => {
    const t2 = await createTestApp({ envOverrides: { VIEW_ONCE_UNOPENED_DAYS: '7' } });
    try {
      const x = await register(t2.app, 'Xia');
      const y = await register(t2.app, 'Yan');
      await pair(t2.app, x, y);
      const mediaId = await uploadPhoto(t2, x);
      const sent = await t2.app.inject({
        method: 'POST',
        url: `${API}/messages`,
        headers: bearer(x),
        payload: { type: 'photo', mediaId, viewOnce: true, idempotencyKey: randomUUID() },
      });
      const message = sent.json().message;
      const key = (await t2.db.media.findOne({ _id: new ObjectId(mediaId) }))!.storageKey;
      const cleanup = new CleanupWorker(t2.db, t2.storage, t2.clock.now, pino({ level: 'silent' }), {
        intervalMs: 60_000,
        abandonedUploadHours: 24 * 30,
        viewOnceUrlTtlSeconds: 120,
        viewOnceUnopenedDays: 7,
      });
      t2.clock.advance(6 * 24 * 3600_000);
      expect((await cleanup.runOnce()).viewOnceUnopenedExpired).toBe(0);
      t2.clock.advance(1 * 24 * 3600_000 + 1000);
      expect((await cleanup.runOnce()).viewOnceUnopenedExpired).toBe(1);
      expect(await t2.storage.head(key)).toBeNull();

      // fresh access token (the clock moved past its TTL)
      const refreshed = await t2.app.inject({
        method: 'POST',
        url: `${API}/auth/refresh`,
        payload: { refreshToken: y.tokens.refreshToken },
      });
      expect(refreshed.statusCode).toBe(200);
      const token = refreshed.json().tokens.accessToken;
      const hist = await t2.app.inject({ method: 'GET', url: `${API}/messages`, headers: bearer(token) });
      const row = hist.json().items.find((m: { id: string }) => m.id === message.id);
      expect(row).toMatchObject({ viewOnce: true, openedAt: null, viewOnceExpired: true });
      const res = await t2.app.inject({
        method: 'POST',
        url: `${API}/messages/${message.id}/open`,
        headers: bearer(token),
      });
      expect(res.statusCode).toBe(410);
      expect(res.json().error.code).toBe('view_once_expired');
    } finally {
      await t2.close();
    }
  });
});

describe('new event types and the events validator', () => {
  it('message.opened and widget.updated match the stored pattern and fall back live-only on a stale enum', async () => {
    const pattern = new RegExp(VALIDATORS.events!.$jsonSchema.properties.type.pattern);
    for (const type of ['message.opened', 'widget.updated', 'memory.created', 'memory.deleted']) {
      expect(EVENT_TYPES).toContain(type);
      expect(pattern.test(type)).toBe(true);
    }

    const oldValidator = structuredClone(VALIDATORS.events!);
    oldValidator.$jsonSchema.properties.type = {
      enum: EVENT_TYPES.filter((x) => x !== 'message.opened' && x !== 'widget.updated'),
    };
    await t.ctx.mongoDb.command({ collMod: 'events', validator: oldValidator });
    const publish = vi.spyOn(t.ctx.hub, 'publish');
    try {
      await detectStaleEventTypes(t.ctx.mongoDb);
      expect(staleEventTypes).toEqual(['message.opened', 'widget.updated']);

      const mediaId = await uploadPhoto(t, a);
      const message = (await sendPhoto(a, mediaId, { viewOnce: true })).json().message;
      const opened = await open(b, message.id);
      expect(opened.statusCode).toBe(200);
      expect(
        await t.db.events.findOne({ type: 'message.opened', 'payload.messageId': message.id }),
      ).toBeNull();
      expect(publish.mock.calls.some(([ev]) => ev.type === 'message.opened')).toBe(true);

      const w = await t.app.inject({
        method: 'PUT',
        url: `${API}/widget-board`,
        headers: bearer(a),
        payload: { note: 'old validator' },
      });
      expect(w.statusCode).toBe(200);
      expect(publish.mock.calls.some(([ev]) => ev.type === 'widget.updated')).toBe(true);
    } finally {
      publish.mockRestore();
      await t.ctx.mongoDb.command({ collMod: 'events', validator: VALIDATORS.events });
      await detectStaleEventTypes(t.ctx.mongoDb);
    }
    expect(staleEventTypes).toEqual([]);
  });
});
