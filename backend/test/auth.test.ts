import { randomUUID } from 'node:crypto';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { API, bearer, createTestApp, register, type TestApp } from './helpers/app.js';

let t: TestApp;

beforeAll(async () => {
  t = await createTestApp();
});
afterAll(async () => {
  await t.close();
});

describe('auth', () => {
  it('register → login → /me', async () => {
    const email = `alice-${randomUUID().slice(0, 8)}@Example.TEST`;
    const reg = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/register`,
      payload: { email, password: 'correct-horse-battery', displayName: 'Alice' },
    });
    expect(reg.statusCode).toBe(201);
    const body = reg.json();
    expect(body.user.displayName).toBe('Alice');
    expect(body.user).not.toHaveProperty('email');
    expect(body.user).not.toHaveProperty('passwordHash');
    expect(body.tokens.accessToken).toBeTypeOf('string');
    expect(body.tokens.refreshToken).toHaveLength(43);

    // duplicate email (case-insensitive) → 409
    const dup = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/register`,
      payload: { email: email.toLowerCase(), password: 'correct-horse-battery', displayName: 'Alice2' },
    });
    expect(dup.statusCode).toBe(409);
    expect(dup.json().error.code).toBe('conflict');

    const login = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/login`,
      payload: { email, password: 'correct-horse-battery' },
    });
    expect(login.statusCode).toBe(200);

    const bad = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/login`,
      payload: { email, password: 'wrong-password-xx' },
    });
    expect(bad.statusCode).toBe(401);
    expect(bad.json().error.code).toBe('unauthorized');

    const me = await t.app.inject({
      method: 'GET',
      url: `${API}/me`,
      headers: bearer(login.json().tokens.accessToken),
    });
    expect(me.statusCode).toBe(200);
    expect(me.json().user.id).toBe(body.user.id);
    expect(me.json().couple).toBeNull();
    expect(me.json().preferences.pingCooldownSeconds).toBe(15);
  });

  it('rejects weak passwords and malformed bodies with validation_failed', async () => {
    const res = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/register`,
      payload: { email: 'not-an-email', password: 'short', displayName: '' },
    });
    expect(res.statusCode).toBe(400);
    expect(res.json().error.code).toBe('validation_failed');
    expect(Array.isArray(res.json().error.details)).toBe(true);
  });

  it('refresh rotates tokens; reuse of a rotated token revokes the whole family', async () => {
    const s = await register(t.app, 'Bob');
    const r1 = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/refresh`,
      payload: { refreshToken: s.tokens.refreshToken },
    });
    expect(r1.statusCode).toBe(200);
    const t1 = r1.json().tokens;
    expect(t1.refreshToken).not.toBe(s.tokens.refreshToken);
    expect(t1.accessToken).not.toBe(s.tokens.accessToken);

    // new access token works
    const ok = await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(t1.accessToken) });
    expect(ok.statusCode).toBe(200);

    // reuse of the OLD refresh token → 401 and family revoked
    const reuse = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/refresh`,
      payload: { refreshToken: s.tokens.refreshToken },
    });
    expect(reuse.statusCode).toBe(401);

    // the legitimately rotated token is now dead too
    const after = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/refresh`,
      payload: { refreshToken: t1.refreshToken },
    });
    expect(after.statusCode).toBe(401);

    // and the access token issued with it no longer authenticates
    const dead = await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(t1.accessToken) });
    expect(dead.statusCode).toBe(401);

    const sessions = await t.db.sessions.find({}).toArray();
    const family = sessions.filter((x) => x.userId.toHexString() === s.user.id);
    expect(family.length).toBe(2);
    expect(family.every((x) => x.revokedAt !== null && x.revokedReason === 'refresh_reuse')).toBe(true);
  });

  it('logout revokes the session and the device token', async () => {
    const s = await register(t.app, 'Carol');
    const dev = await t.app.inject({
      method: 'PUT',
      url: `${API}/devices`,
      headers: bearer(s),
      payload: { deviceId: 'dev-1', platform: 'android', token: `fcm-${randomUUID()}`, appVersion: '1.0.0' },
    });
    expect(dev.statusCode).toBe(204);
    expect(await t.db.deviceTokens.countDocuments({ deviceId: 'dev-1' })).toBe(1);

    const out = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/logout`,
      payload: { refreshToken: s.tokens.refreshToken, deviceId: 'dev-1' },
    });
    expect(out.statusCode).toBe(204);

    const me = await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(s) });
    expect(me.statusCode).toBe(401);
    const refresh = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/refresh`,
      payload: { refreshToken: s.tokens.refreshToken },
    });
    expect(refresh.statusCode).toBe(401);
    expect(await t.db.deviceTokens.countDocuments({ deviceId: 'dev-1' })).toBe(0);
  });

  it('forgot/reset: hashed token, single use, revokes sessions; unknown email still 202', async () => {
    const s = await register(t.app, 'Dave', 'original-password-1');
    const user = await t.db.users.findOne({ _id: { $exists: true }, displayName: 'Dave' });
    const unknown = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/password/forgot`,
      payload: { email: 'nobody@example.test' },
    });
    expect(unknown.statusCode).toBe(202);
    expect(t.mailer.sent).toHaveLength(0);

    const forgot = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/password/forgot`,
      payload: { email: user!.email },
    });
    expect(forgot.statusCode).toBe(202);
    expect(t.mailer.sent).toHaveLength(1);
    const token = t.mailer.sent[0]!.resetToken;
    const stored = await t.db.passwordResets.findOne({ userId: user!._id });
    expect(stored!.tokenHash).not.toBe(token);
    expect(stored!.tokenHash).toMatch(/^[a-f0-9]{64}$/);

    const reset = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/password/reset`,
      payload: { token, newPassword: 'brand-new-password-2' },
    });
    expect(reset.statusCode).toBe(204);
    const again = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/password/reset`,
      payload: { token, newPassword: 'another-password-3' },
    });
    expect(again.statusCode).toBe(400);

    // old session revoked, old password rejected, new password works
    expect((await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(s) })).statusCode).toBe(
      401,
    );
    expect(
      (
        await t.app.inject({
          method: 'POST',
          url: `${API}/auth/login`,
          payload: { email: user!.email, password: 'original-password-1' },
        })
      ).statusCode,
    ).toBe(401);
    expect(
      (
        await t.app.inject({
          method: 'POST',
          url: `${API}/auth/login`,
          payload: { email: user!.email, password: 'brand-new-password-2' },
        })
      ).statusCode,
    ).toBe(200);
  });

  it('requires a bearer token on protected routes and sets x-request-id', async () => {
    const res = await t.app.inject({ method: 'GET', url: `${API}/me` });
    expect(res.statusCode).toBe(401);
    expect(res.json()).toEqual({ error: { code: 'unauthorized', message: expect.any(String) } });
    expect(res.headers['x-request-id']).toMatch(/^[0-9a-f-]{36}$/);
    const garbage = await t.app.inject({
      method: 'GET',
      url: `${API}/me`,
      headers: { authorization: 'Bearer not.a.jwt' },
    });
    expect(garbage.statusCode).toBe(401);
  });

  it('unknown routes return the not_found envelope', async () => {
    const res = await t.app.inject({ method: 'GET', url: `${API}/nope` });
    expect(res.statusCode).toBe(404);
    expect(res.json().error.code).toBe('not_found');
  });
});
