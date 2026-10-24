import { randomUUID } from 'node:crypto';
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
  a = await register(t.app, 'Vic');
  b = await register(t.app, 'Wen');
  coupleId = await pair(t.app, a, b);
});
afterAll(async () => {
  await t.close();
});

function send(s: Session, payload: Record<string, unknown>) {
  return t.app.inject({
    method: 'POST',
    url: `${API}/messages`,
    headers: bearer(s),
    payload: { idempotencyKey: randomUUID(), ...payload },
  });
}

describe('messages', () => {
  it('text/emoji require text; photo/voice require mediaId', async () => {
    const noText = await send(a, { type: 'text' });
    expect(noText.statusCode).toBe(400);
    expect(noText.json().error.code).toBe('validation_failed');
    const emptyText = await send(a, { type: 'text', text: '   ' });
    expect(emptyText.statusCode).toBe(400);
    const tooLong = await send(a, { type: 'text', text: 'x'.repeat(2001) });
    expect(tooLong.statusCode).toBe(400);
    const textWithMedia = await send(a, { type: 'text', text: 'hi', mediaId: new ObjectId().toHexString() });
    expect(textWithMedia.statusCode).toBe(400);
    const photoNoMedia = await send(a, { type: 'photo' });
    expect(photoNoMedia.statusCode).toBe(400);
    const badType = await send(a, { type: 'video', text: 'x' });
    expect(badType.statusCode).toBe(400);

    const ok = await send(a, { type: 'text', text: 'hello there' });
    expect(ok.statusCode).toBe(201);
    expect(ok.json().message).toMatchObject({
      type: 'text',
      text: 'hello there',
      senderId: a.user.id,
      recipientId: b.user.id,
      readAt: null,
      deletedAt: null,
    });
    const emoji = await send(b, { type: 'emoji', text: '❤️' });
    expect(emoji.statusCode).toBe(201);
  });

  it('mediaId must exist in the couple, be owned by the sender, be ready, and match the category', async () => {
    const missing = await send(a, { type: 'photo', mediaId: new ObjectId().toHexString() });
    expect(missing.statusCode).toBe(404);

    // pending (never completed) upload → media_not_ready
    const pending = await t.app.inject({
      method: 'POST',
      url: `${API}/media/uploads`,
      headers: bearer(a),
      payload: { category: 'photo', mimeType: 'image/jpeg', sizeBytes: 1234 },
    });
    expect(pending.statusCode).toBe(201);
    const notReady = await send(a, { type: 'photo', mediaId: pending.json().media.id });
    expect(notReady.statusCode).toBe(409);
    expect(notReady.json().error.code).toBe('media_not_ready');

    // ready photo owned by a
    const photoId = await uploadPhoto(t, a);
    const notOwner = await send(b, { type: 'photo', mediaId: photoId });
    expect(notOwner.statusCode).toBe(403);
    const wrongCategory = await send(a, { type: 'voice', mediaId: photoId });
    expect(wrongCategory.statusCode).toBe(400);
    expect(wrongCategory.json().error.code).toBe('validation_failed');

    const ok = await send(a, { type: 'photo', mediaId: photoId });
    expect(ok.statusCode).toBe(201);
    expect(ok.json().message).toMatchObject({ type: 'photo', mediaId: photoId, text: null });
  });

  it('idempotency replay returns the original with 200', async () => {
    const key = randomUUID();
    const first = await send(a, { type: 'text', text: 'once', idempotencyKey: key });
    expect(first.statusCode).toBe(201);
    const again = await send(a, { type: 'text', text: 'twice', idempotencyKey: key });
    expect(again.statusCode).toBe(200);
    expect(again.json().message).toEqual(first.json().message);
  });

  it('read by recipient only; delete by sender only (soft, media object removed); events match API.md', async () => {
    const photoId = await uploadPhoto(t, a);
    const created = await send(a, { type: 'photo', mediaId: photoId });
    const id = created.json().message.id;
    const media = await t.db.media.findOne({ _id: new ObjectId(photoId) });
    expect(await t.storage.head(media!.storageKey)).not.toBeNull();

    expect(
      (await t.app.inject({ method: 'POST', url: `${API}/messages/${id}/read`, headers: bearer(a) }))
        .statusCode,
    ).toBe(403);
    const read = await t.app.inject({
      method: 'POST',
      url: `${API}/messages/${id}/read`,
      headers: bearer(b),
    });
    expect(read.statusCode).toBe(200);
    expect(read.json().message.readAt).toBeTypeOf('string');
    const readAgain = await t.app.inject({
      method: 'POST',
      url: `${API}/messages/${id}/read`,
      headers: bearer(b),
    });
    expect(readAgain.json().message.readAt).toBe(read.json().message.readAt);

    expect(
      (await t.app.inject({ method: 'DELETE', url: `${API}/messages/${id}`, headers: bearer(b) })).statusCode,
    ).toBe(403);
    const del = await t.app.inject({ method: 'DELETE', url: `${API}/messages/${id}`, headers: bearer(a) });
    expect(del.statusCode).toBe(200);
    expect(del.json().message.deletedAt).toBeTypeOf('string');
    expect(await t.storage.head(media!.storageKey)).toBeNull();
    const mediaAfter = await t.db.media.findOne({ _id: new ObjectId(photoId) });
    expect(mediaAfter!.status).toBe('deleted');

    const events = await t.db.events
      .find({ coupleId: new ObjectId(coupleId), 'payload.messageId': id })
      .sort({ seq: 1 })
      .toArray();
    expect(events.map((e) => e.type)).toEqual(['message.read', 'message.deleted']);
    expect(events[0]!.payload).toEqual({ messageId: id, readAt: read.json().message.readAt });
    expect(events[1]!.payload).toEqual({ messageId: id, deletedAt: del.json().message.deletedAt });
    const createdEvent = await t.db.events.findOne({
      coupleId: new ObjectId(coupleId),
      type: 'message.created',
      'payload.message.id': id,
    });
    expect(createdEvent!.payload).toEqual({ message: created.json().message });

    // tombstone visible in the list
    const list = await t.app.inject({ method: 'GET', url: `${API}/messages?limit=50`, headers: bearer(b) });
    const tomb = list.json().items.find((m: { id: string }) => m.id === id);
    expect(tomb.deletedAt).toBeTypeOf('string');
  });
});
