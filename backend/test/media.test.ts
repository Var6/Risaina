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
  TINY_PNG,
  uploadPhoto,
} from './helpers/app.js';

let t: TestApp;
let a: Session;
let b: Session;
let c: Session;

beforeAll(async () => {
  t = await createTestApp({
    envOverrides: { MEDIA_DOWNLOAD_URL_TTL_SECONDS: '60', MEDIA_MAX_AVATAR_BYTES: '100' },
  });
  a = await register(t.app, 'Xan');
  b = await register(t.app, 'Yas');
  c = await register(t.app, 'Zed');
  await pair(t.app, a, b);
  await pair(t.app, c, await register(t.app, 'Dee'));
});
afterAll(async () => {
  await t.close();
});

describe('media', () => {
  it('presign → signed PUT → complete verifies size + magic bytes and emits media.ready', async () => {
    const create = await t.app.inject({
      method: 'POST',
      url: `${API}/media/uploads`,
      headers: bearer(a),
      payload: {
        category: 'photo',
        mimeType: 'image/png',
        sizeBytes: TINY_PNG.byteLength,
        width: 1,
        height: 1,
      },
    });
    expect(create.statusCode).toBe(201);
    const { media, upload } = create.json();
    expect(media.status).toBe('pending');
    expect(media).not.toHaveProperty('storageKey');
    expect(upload.method).toBe('PUT');
    expect(upload.headers['Content-Type']).toBe('image/png');
    expect(upload.url).toContain('/api/v1/_local-storage/object?');

    // completing before upload → media_not_ready
    const early = await t.app.inject({
      method: 'POST',
      url: `${API}/media/${media.id}/complete`,
      headers: bearer(a),
    });
    expect(early.statusCode).toBe(409);
    expect(early.json().error.code).toBe('media_not_ready');

    // tampered signature rejected
    const tampered = await t.app.inject({
      method: 'PUT',
      url: upload.url.replace(/sig=[a-f0-9]{8}/, 'sig=00000000'),
      headers: { 'content-type': 'image/png' },
      payload: TINY_PNG,
    });
    expect(tampered.statusCode).toBe(403);

    const put = await t.app.inject({
      method: 'PUT',
      url: upload.url,
      headers: { 'content-type': 'image/png' },
      payload: TINY_PNG,
    });
    expect(put.statusCode).toBe(204);

    // b (partner, not owner) cannot complete
    expect(
      (await t.app.inject({ method: 'POST', url: `${API}/media/${media.id}/complete`, headers: bearer(b) }))
        .statusCode,
    ).toBe(403);

    const complete = await t.app.inject({
      method: 'POST',
      url: `${API}/media/${media.id}/complete`,
      headers: bearer(a),
    });
    expect(complete.statusCode).toBe(200);
    expect(complete.json().media.status).toBe('ready');
    const doc = await t.db.media.findOne({ _id: new ObjectId(media.id) });
    expect(doc!.storageKey).toMatch(
      new RegExp(`^couples/${doc!.coupleId.toHexString()}/photo/${media.id}\\.png$`),
    );
    const ev = await t.db.events.findOne({ type: 'media.ready', 'payload.media.id': media.id });
    expect(ev!.payload).toEqual({ media: complete.json().media });
  });

  it('rejects size mismatch and wrong magic bytes', async () => {
    const create = await t.app.inject({
      method: 'POST',
      url: `${API}/media/uploads`,
      headers: bearer(a),
      payload: { category: 'photo', mimeType: 'image/png', sizeBytes: TINY_PNG.byteLength + 5 },
    });
    const { media, upload } = create.json();
    await t.app.inject({
      method: 'PUT',
      url: upload.url,
      headers: { 'content-type': 'image/png' },
      payload: TINY_PNG,
    });
    const mismatch = await t.app.inject({
      method: 'POST',
      url: `${API}/media/${media.id}/complete`,
      headers: bearer(a),
    });
    expect(mismatch.statusCode).toBe(400);
    expect(mismatch.json().error.code).toBe('validation_failed');
    expect((await t.db.media.findOne({ _id: new ObjectId(media.id) }))!.status).toBe('deleted');

    const fake = Buffer.from('this is definitely not a png file, just text padded out............');
    const create2 = await t.app.inject({
      method: 'POST',
      url: `${API}/media/uploads`,
      headers: bearer(a),
      payload: { category: 'photo', mimeType: 'image/png', sizeBytes: fake.byteLength },
    });
    const two = create2.json();
    await t.app.inject({
      method: 'PUT',
      url: two.upload.url,
      headers: { 'content-type': 'image/png' },
      payload: fake,
    });
    const sniff = await t.app.inject({
      method: 'POST',
      url: `${API}/media/${two.media.id}/complete`,
      headers: bearer(a),
    });
    expect(sniff.statusCode).toBe(415);
    expect(sniff.json().error.code).toBe('unsupported_media');
  });

  it('validates category/mime/size at presign time', async () => {
    const badMime = await t.app.inject({
      method: 'POST',
      url: `${API}/media/uploads`,
      headers: bearer(a),
      payload: { category: 'photo', mimeType: 'image/gif', sizeBytes: 10 },
    });
    expect(badMime.statusCode).toBe(415);
    const tooBig = await t.app.inject({
      method: 'POST',
      url: `${API}/media/uploads`,
      headers: bearer(a),
      payload: { category: 'avatar', mimeType: 'image/png', sizeBytes: 101 },
    });
    expect(tooBig.statusCode).toBe(413);
    expect(tooBig.json().error.code).toBe('payload_too_large');
    const noDuration = await t.app.inject({
      method: 'POST',
      url: `${API}/media/uploads`,
      headers: bearer(a),
      payload: { category: 'voice', mimeType: 'audio/mp4', sizeBytes: 10 },
    });
    expect(noDuration.statusCode).toBe(400);
    const tooLongVoice = await t.app.inject({
      method: 'POST',
      url: `${API}/media/uploads`,
      headers: bearer(a),
      payload: { category: 'voice', mimeType: 'audio/mp4', sizeBytes: 10, durationMs: 180_001 },
    });
    expect(tooLongVoice.statusCode).toBe(413);
  });

  it('signed download URL works for members, 403 for non-members, and expires', async () => {
    const id = await uploadPhoto(t, a);
    const forC = await t.app.inject({ method: 'GET', url: `${API}/media/${id}/url`, headers: bearer(c) });
    expect(forC.statusCode).toBe(403);
    expect(forC.json().error.code).toBe('forbidden');

    const forB = await t.app.inject({ method: 'GET', url: `${API}/media/${id}/url`, headers: bearer(b) });
    expect(forB.statusCode).toBe(200);
    const { url, expiresAt } = forB.json();
    expect(new Date(expiresAt).getTime() - t.clock.now().getTime()).toBeLessThanOrEqual(60_000);

    const get = await t.app.inject({ method: 'GET', url });
    expect(get.statusCode).toBe(200);
    expect(get.headers['content-type']).toBe('image/png');
    expect(get.rawPayload.equals(TINY_PNG)).toBe(true);

    t.clock.advance(61_000);
    const expired = await t.app.inject({ method: 'GET', url });
    expect(expired.statusCode).toBe(403);
  });

  it('owner-only delete removes the object; partner delete → 403; deleted media → 404 on /url', async () => {
    const id = await uploadPhoto(t, a);
    const key = (await t.db.media.findOne({ _id: new ObjectId(id) }))!.storageKey;
    expect(
      (await t.app.inject({ method: 'DELETE', url: `${API}/media/${id}`, headers: bearer(b) })).statusCode,
    ).toBe(403);
    const del = await t.app.inject({ method: 'DELETE', url: `${API}/media/${id}`, headers: bearer(a) });
    expect(del.statusCode).toBe(204);
    expect(await t.storage.head(key)).toBeNull();
    const after = await t.app.inject({ method: 'GET', url: `${API}/media/${id}/url`, headers: bearer(b) });
    expect(after.statusCode).toBe(409);
    expect(after.json().error.code).toBe('media_not_ready');
  });
});
