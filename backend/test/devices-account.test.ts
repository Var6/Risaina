import { randomUUID } from 'node:crypto';
import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  API,
  bearer,
  createTestApp,
  pair,
  register,
  sendPing,
  type TestApp,
  uploadPhoto,
} from './helpers/app.js';

let t: TestApp;

beforeAll(async () => {
  t = await createTestApp();
});
afterAll(async () => {
  await t.close();
});

describe('devices', () => {
  it('upserts by deviceId, and the token never appears in any response body', async () => {
    const a = await register(t.app, 'Dev');
    const b = await register(t.app, 'Pal');
    await pair(t.app, a, b);
    const token = `fcm-secret-${randomUUID()}`;
    const put = await t.app.inject({
      method: 'PUT',
      url: `${API}/devices`,
      headers: bearer(a),
      payload: { deviceId: 'phone-1', platform: 'android', token, appVersion: '1.0.0', model: 'Pixel 9' },
    });
    expect(put.statusCode).toBe(204);
    expect(put.body).toBe('');
    const again = await t.app.inject({
      method: 'PUT',
      url: `${API}/devices`,
      headers: bearer(a),
      payload: { deviceId: 'phone-1', platform: 'android', token: `${token}-rotated`, appVersion: '1.0.1' },
    });
    expect(again.statusCode).toBe(204);
    const docs = await t.db.deviceTokens.find({ userId: new ObjectId(a.user.id) }).toArray();
    expect(docs).toHaveLength(1);
    expect(docs[0]!.token).toBe(`${token}-rotated`);

    // grep every response body a client could reasonably fetch
    const bodies: string[] = [];
    for (const path of [
      '/me',
      '/me/preferences',
      '/me/export',
      '/couple',
      '/events',
      '/pings',
      '/messages',
      '/memories',
      '/milestones',
      '/invitations/current',
      '/health',
    ]) {
      const res = await t.app.inject({ method: 'GET', url: `${API}${path}`, headers: bearer(a) });
      bodies.push(res.body);
    }
    bodies.push((await t.app.inject({ method: 'GET', url: '/docs/json' })).body);
    for (const body of bodies) {
      expect(body).not.toContain(token);
      expect(body).not.toContain('fcm-secret');
    }

    const bad = await t.app.inject({
      method: 'PUT',
      url: `${API}/devices`,
      headers: bearer(a),
      payload: { deviceId: 'phone-1', platform: 'ios', token, appVersion: '1' },
    });
    expect(bad.statusCode).toBe(400);

    const del = await t.app.inject({ method: 'DELETE', url: `${API}/devices/phone-1`, headers: bearer(a) });
    expect(del.statusCode).toBe(204);
    expect(await t.db.deviceTokens.countDocuments({ userId: new ObjectId(a.user.id) })).toBe(0);
  });

  it('a token re-registered by another account moves to that account (unique token)', async () => {
    const a = await register(t.app, 'One');
    const b = await register(t.app, 'Two');
    const token = `fcm-shared-${randomUUID()}`;
    expect(
      (
        await t.app.inject({
          method: 'PUT',
          url: `${API}/devices`,
          headers: bearer(a),
          payload: { deviceId: 'd', platform: 'android', token, appVersion: '1' },
        })
      ).statusCode,
    ).toBe(204);
    expect(
      (
        await t.app.inject({
          method: 'PUT',
          url: `${API}/devices`,
          headers: bearer(b),
          payload: { deviceId: 'd', platform: 'android', token, appVersion: '1' },
        })
      ).statusCode,
    ).toBe(204);
    const docs = await t.db.deviceTokens.find({ token }).toArray();
    expect(docs).toHaveLength(1);
    expect(docs[0]!.userId.toHexString()).toBe(b.user.id);
  });
});

describe('account deletion', () => {
  it('requires the password, revokes sessions, removes devices, ends the couple, emits couple.unlinked, schedules media deletion', async () => {
    const a = await register(t.app, 'Gone', 'leaving-password-1');
    const b = await register(t.app, 'Stays');
    const coupleId = await pair(t.app, a, b);
    await sendPing(t.app, a);
    const mediaId = await uploadPhoto(t, a);
    await t.app.inject({
      method: 'PUT',
      url: `${API}/devices`,
      headers: bearer(a),
      payload: { deviceId: 'x', platform: 'android', token: `fcm-${randomUUID()}`, appVersion: '1' },
    });
    const refreshed = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/refresh`,
      payload: { refreshToken: a.tokens.refreshToken },
    });
    const live = refreshed.json().tokens;

    const wrong = await t.app.inject({
      method: 'DELETE',
      url: `${API}/me`,
      headers: bearer(live.accessToken),
      payload: { password: 'not-the-password' },
    });
    expect(wrong.statusCode).toBe(401);

    const del = await t.app.inject({
      method: 'DELETE',
      url: `${API}/me`,
      headers: bearer(live.accessToken),
      payload: { password: 'leaving-password-1' },
    });
    expect(del.statusCode).toBe(204);

    // sessions revoked
    expect(
      (await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(live.accessToken) })).statusCode,
    ).toBe(401);
    expect(
      (
        await t.app.inject({
          method: 'POST',
          url: `${API}/auth/refresh`,
          payload: { refreshToken: live.refreshToken },
        })
      ).statusCode,
    ).toBe(401);
    const sessions = await t.db.sessions.find({ userId: new ObjectId(a.user.id) }).toArray();
    expect(sessions.length).toBeGreaterThan(0);
    expect(sessions.every((s) => s.revokedAt !== null)).toBe(true);

    // devices gone, user anonymised, cannot log in
    expect(await t.db.deviceTokens.countDocuments({ userId: new ObjectId(a.user.id) })).toBe(0);
    const user = await t.db.users.findOne({ _id: new ObjectId(a.user.id) });
    expect(user!.deletedAt).toBeInstanceOf(Date);
    expect(user!.passwordHash).toBeNull();
    expect(user!.activeCoupleId).toBeNull();
    expect(user!.email).not.toContain('@example.test');

    // couple ended + partner unpaired + event emitted
    const couple = await t.db.couples.findOne({ _id: new ObjectId(coupleId) });
    expect(couple!.status).toBe('ended');
    const meB = await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(b) });
    expect(meB.json().couple).toBeNull();
    const unlinked = await t.db.events.findOne({ coupleId: new ObjectId(coupleId), type: 'couple.unlinked' });
    expect(unlinked).not.toBeNull();
    expect(unlinked!.payload).toEqual({ coupleId, endedAt: expect.any(String), by: a.user.id });

    // own media scheduled for deletion; cleanup removes the object
    const media = await t.db.media.findOne({ _id: new ObjectId(mediaId) });
    expect(media!.status).toBe('deleted');
    const { CleanupWorker } = await import('../src/workers/cleanup.js');
    const { pino } = await import('pino');
    const cleanup = new CleanupWorker(t.db, t.storage, t.clock.now, pino({ level: 'silent' }), {
      intervalMs: 60_000,
      abandonedUploadHours: 24,
    });
    const report = await cleanup.runOnce();
    expect(report.objectsDeleted).toBeGreaterThanOrEqual(1);
    expect(await t.storage.head(media!.storageKey)).toBeNull();
    expect((await t.db.media.findOne({ _id: new ObjectId(mediaId) }))!.objectDeleted).toBe(true);
  });
});

describe('me', () => {
  it('PATCH /me validates avatar media and emits partner.updated; preferences merge partially', async () => {
    const a = await register(t.app, 'Pia');
    const b = await register(t.app, 'Quo');
    const coupleId = await pair(t.app, a, b);
    const photo = await uploadPhoto(t, a); // category photo → rejected as avatar
    const wrongCat = await t.app.inject({
      method: 'PATCH',
      url: `${API}/me`,
      headers: bearer(a),
      payload: { avatarMediaId: photo },
    });
    expect(wrongCat.statusCode).toBe(400);
    const avatar = await uploadPhoto(t, a, undefined, 'avatar');
    const ok = await t.app.inject({
      method: 'PATCH',
      url: `${API}/me`,
      headers: bearer(a),
      payload: { displayName: 'Pia ✨', avatarMediaId: avatar },
    });
    expect(ok.statusCode).toBe(200);
    expect(ok.json().user).toMatchObject({ displayName: 'Pia ✨', avatarMediaId: avatar });
    const ev = await t.db.events.findOne({ coupleId: new ObjectId(coupleId), type: 'partner.updated' });
    expect(ev!.payload).toEqual({
      partner: {
        id: a.user.id,
        displayName: 'Pia ✨',
        avatarMediaId: avatar,
        birthday: null,
        timeZone: null,
      },
    });

    const prefs = await t.app.inject({
      method: 'PUT',
      url: `${API}/me/preferences`,
      headers: bearer(a),
      payload: {
        quietHours: { enabled: true, timeZone: 'Europe/Paris' },
        widgetPrivacy: { showPhoto: false },
      },
    });
    expect(prefs.statusCode).toBe(200);
    expect(prefs.json().preferences.quietHours).toEqual({
      enabled: true,
      start: '22:00',
      end: '07:00',
      timeZone: 'Europe/Paris',
    });
    expect(prefs.json().preferences.widgetPrivacy).toEqual({
      showPhoto: false,
      showNoteText: true,
      showEmoji: true,
    });
    const badTz = await t.app.inject({
      method: 'PUT',
      url: `${API}/me/preferences`,
      headers: bearer(a),
      payload: { quietHours: { timeZone: 'Mars/Olympus' } },
    });
    expect(badTz.statusCode).toBe(400);
    const badTime = await t.app.inject({
      method: 'PUT',
      url: `${API}/me/preferences`,
      headers: bearer(a),
      payload: { quietHours: { start: '25:00' } },
    });
    expect(badTime.statusCode).toBe(400);

    const exp = await t.app.inject({ method: 'GET', url: `${API}/me/export`, headers: bearer(a) });
    expect(exp.statusCode).toBe(200);
    expect(exp.json()).toMatchObject({ user: { id: a.user.id }, couple: { id: coupleId }, truncated: false });
    expect(exp.body).not.toContain('passwordHash');
  });
});
