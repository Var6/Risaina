import { randomUUID } from 'node:crypto';
import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { API, bearer, createTestApp, register, type TestApp } from './helpers/app.js';
import { FakeGoogle, GOOGLE_TEST_CLIENT_ID } from './helpers/google.js';

let t: TestApp;
let google: FakeGoogle;

beforeAll(async () => {
  google = await FakeGoogle.create();
  t = await createTestApp({
    envOverrides: { GOOGLE_CLIENT_IDS: `${GOOGLE_TEST_CLIENT_ID}, second-client.apps.googleusercontent.com` },
    googleKeys: google.keys,
  });
});
afterAll(async () => {
  await t.close();
});

function googleSignIn(idToken: string) {
  return t.app.inject({ method: 'POST', url: `${API}/auth/google`, payload: { idToken } });
}

function login(email: string, password: string) {
  return t.app.inject({ method: 'POST', url: `${API}/auth/login`, payload: { email, password } });
}

function refresh(refreshToken: string) {
  return t.app.inject({ method: 'POST', url: `${API}/auth/refresh`, payload: { refreshToken } });
}

const uniqueEmail = (p: string) => `${p}-${randomUUID().slice(0, 8)}@gmail.test`;
const uniqueSub = () => `${Date.now()}${Math.floor(Math.random() * 1e6)}`;

describe('POST /auth/google', () => {
  it('creates a Google-only account for a new identity (200, isNewUser, verified, no password)', async () => {
    const email = uniqueEmail('asha');
    const sub = uniqueSub();
    const res = await googleSignIn(await google.token({ sub, email, given_name: 'Asha', name: 'Asha Rao' }));
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.isNewUser).toBe(true);
    expect(body.user).toMatchObject({ displayName: 'Asha', emailVerified: true, hasPassword: false });
    expect(body.user).not.toHaveProperty('email');
    expect(body.tokens.refreshToken).toHaveLength(43);

    const doc = await t.db.users.findOne({ _id: new ObjectId(body.user.id) });
    expect(doc!.googleSub).toBe(sub);
    expect(doc!.passwordHash).toBeNull();
    expect(doc!.email).toBe(email);
    expect(doc!.emailVerifiedAt).toBeInstanceOf(Date);

    const me = await t.app.inject({
      method: 'GET',
      url: `${API}/me`,
      headers: bearer(body.tokens.accessToken),
    });
    expect(me.statusCode).toBe(200);
    expect(me.json().user).toMatchObject({ id: body.user.id, emailVerified: true, hasPassword: false });

    // password login on a password-less account fails exactly like a wrong password
    const pw = await login(email, 'any-password-at-all');
    const unknown = await login(uniqueEmail('nobody'), 'any-password-at-all');
    expect(pw.statusCode).toBe(401);
    expect(pw.json()).toEqual(unknown.json());
  });

  it('signs a returning user in by sub (even if the Google email changed)', async () => {
    const sub = uniqueSub();
    const first = await googleSignIn(await google.token({ sub, email: uniqueEmail('ravi'), name: 'Ravi' }));
    expect(first.statusCode).toBe(200);
    const again = await googleSignIn(await google.token({ sub, email: uniqueEmail('ravi-new') }));
    expect(again.statusCode).toBe(200);
    expect(again.json().isNewUser).toBe(false);
    expect(again.json().user.id).toBe(first.json().user.id);
    expect(again.json().user.displayName).toBe('Ravi');
    expect(await t.db.users.countDocuments({ googleSub: sub })).toBe(1);
  });

  it('links a VERIFIED password account by email and keeps its password and sessions', async () => {
    const email = uniqueEmail('mira');
    const s = await register(t.app, 'Mira', 'mira-password-123', email);
    const code = t.mailer.lastCodeFor(email)!;
    const verified = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/email/verify`,
      headers: bearer(s),
      payload: { code },
    });
    expect(verified.statusCode).toBe(200);

    const sub = uniqueSub();
    // Google may present the address in a different case
    const res = await googleSignIn(await google.token({ sub, email: email.toUpperCase(), given_name: 'M' }));
    expect(res.statusCode).toBe(200);
    expect(res.json().isNewUser).toBe(false);
    expect(res.json().user).toMatchObject({
      id: s.user.id,
      displayName: 'Mira',
      emailVerified: true,
      hasPassword: true,
    });

    const doc = await t.db.users.findOne({ _id: new ObjectId(s.user.id) });
    expect(doc!.googleSub).toBe(sub);
    expect(doc!.passwordHash).toEqual(expect.any(String));
    expect((await login(email, 'mira-password-123')).statusCode).toBe(200);
    expect((await refresh(s.tokens.refreshToken)).statusCode).toBe(200);
  });

  it('claims an UNVERIFIED password account: password removed and old sessions revoked', async () => {
    // An attacker pre-registers the victim's address with a password of their choosing...
    const email = uniqueEmail('victim');
    const attacker = await register(t.app, 'Squatter', 'attacker-password-1', email);
    expect(attacker.user.emailVerified).toBe(false);

    // ...then the real owner signs in with Google.
    const res = await googleSignIn(await google.token({ sub: uniqueSub(), email, given_name: 'Owner' }));
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.isNewUser).toBe(false);
    expect(body.user).toMatchObject({ id: attacker.user.id, emailVerified: true, hasPassword: false });

    const doc = await t.db.users.findOne({ _id: new ObjectId(attacker.user.id) });
    expect(doc!.passwordHash).toBeNull();
    expect(await t.db.emailVerifications.countDocuments({ userId: doc!._id })).toBe(0);

    // the attacker's refresh + access tokens are dead, and the password no longer works
    expect((await refresh(attacker.tokens.refreshToken)).statusCode).toBe(401);
    expect(
      (await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(attacker) })).statusCode,
    ).toBe(401);
    const pw = await login(email, 'attacker-password-1');
    expect(pw.statusCode).toBe(401);
    expect(pw.json().error).toEqual({ code: 'unauthorized', message: 'Invalid email or password' });
    const sessions = await t.db.sessions.find({ userId: doc!._id, revokedAt: { $ne: null } }).toArray();
    expect(sessions.map((x) => x.revokedReason)).toContain('google_claimed_unverified');

    // the new Google tokens work
    expect(
      (await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(body.tokens.accessToken) }))
        .statusCode,
    ).toBe(200);

    // the owner can still set a password through forgot/reset
    const sentBefore = t.mailer.sent.length;
    expect(
      (await t.app.inject({ method: 'POST', url: `${API}/auth/password/forgot`, payload: { email } }))
        .statusCode,
    ).toBe(202);
    const token = t.mailer.sent[sentBefore]!.resetToken;
    const reset = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/password/reset`,
      payload: { token, newPassword: 'owner-password-2' },
    });
    expect(reset.statusCode).toBe(204);
    expect((await login(email, 'owner-password-2')).statusCode).toBe(200);
  });

  it('refuses to link an email already bound to a different Google sub (409)', async () => {
    const email = uniqueEmail('bound');
    expect((await googleSignIn(await google.token({ sub: uniqueSub(), email }))).statusCode).toBe(200);
    const other = await googleSignIn(await google.token({ sub: uniqueSub(), email }));
    expect(other.statusCode).toBe(409);
    expect(other.json().error.code).toBe('conflict');
  });

  it('rejects a token for a different audience, issuer or signing key', async () => {
    for (const idToken of [
      await google.token({ aud: 'someone-elses-client.apps.googleusercontent.com' }),
      await google.token({ iss: 'https://evil.example' }),
      await google.token({}, { signWithUnknownKey: true }),
      'not-a-jwt',
    ]) {
      const res = await googleSignIn(idToken);
      expect(res.statusCode).toBe(401);
      expect(res.json().error.code).toBe('unauthorized');
    }
    // the second configured client id and the bare issuer form are accepted
    const ok = await googleSignIn(
      await google.token({ aud: 'second-client.apps.googleusercontent.com', iss: 'accounts.google.com' }),
    );
    expect(ok.statusCode).toBe(200);
  });

  it('rejects an expired token', async () => {
    const res = await googleSignIn(
      await google.token({ issuedAt: new Date(Date.now() - 2 * 3600_000), expiresIn: 3600 }),
    );
    expect(res.statusCode).toBe(401);
    expect(res.json().error.code).toBe('unauthorized');
  });

  it('rejects email_verified=false and creates nothing', async () => {
    const email = uniqueEmail('unverified');
    const res = await googleSignIn(await google.token({ email, email_verified: false }));
    expect(res.statusCode).toBe(401);
    expect(res.json().error.code).toBe('unauthorized');
    expect(await t.db.users.countDocuments({ email })).toBe(0);
  });

  it('validates the body (idToken max 4096)', async () => {
    expect((await googleSignIn('x'.repeat(4097))).statusCode).toBe(400);
    const empty = await t.app.inject({ method: 'POST', url: `${API}/auth/google`, payload: {} });
    expect(empty.statusCode).toBe(400);
    expect(empty.json().error.code).toBe('validation_failed');
  });

  it('derives the display name from name / email local part, trimmed to 60 characters', async () => {
    const fromEmail = await googleSignIn(await google.token({ email: 'only.local@gmail.test' }));
    expect(fromEmail.json().user.displayName).toBe('only.local');
    const long = await googleSignIn(await google.token({ name: `  ${'N'.repeat(80)}  ` }));
    expect(long.json().user.displayName).toBe('N'.repeat(60));
  });

  it('reports google ok in /health when configured', async () => {
    const res = await t.app.inject({ method: 'GET', url: `${API}/health` });
    expect(res.json().checks.google).toEqual({ status: 'ok' });
  });
});

describe('DELETE /me for a Google-only account', () => {
  it('requires confirm: "DELETE" instead of a password and frees the Google identity', async () => {
    const sub = uniqueSub();
    const email = uniqueEmail('leaver');
    const res = await googleSignIn(await google.token({ sub, email, given_name: 'Leaver' }));
    const { user, tokens } = res.json();

    const withPassword = await t.app.inject({
      method: 'DELETE',
      url: `${API}/me`,
      headers: bearer(tokens.accessToken),
      payload: { password: 'guess-guess-guess' },
    });
    expect(withPassword.statusCode).toBe(401);
    const empty = await t.app.inject({
      method: 'DELETE',
      url: `${API}/me`,
      headers: bearer(tokens.accessToken),
      payload: {},
    });
    expect(empty.statusCode).toBe(400);
    const wrongWord = await t.app.inject({
      method: 'DELETE',
      url: `${API}/me`,
      headers: bearer(tokens.accessToken),
      payload: { confirm: 'delete' },
    });
    expect(wrongWord.statusCode).toBe(400);

    const del = await t.app.inject({
      method: 'DELETE',
      url: `${API}/me`,
      headers: bearer(tokens.accessToken),
      payload: { confirm: 'DELETE' },
    });
    expect(del.statusCode).toBe(204);
    expect(
      (await t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(tokens.accessToken) }))
        .statusCode,
    ).toBe(401);
    const doc = await t.db.users.findOne({ _id: new ObjectId(user.id) });
    expect(doc!.deletedAt).toBeInstanceOf(Date);
    expect(doc!.googleSub).toBeUndefined();

    // the same Google account can start over with a fresh account
    const again = await googleSignIn(await google.token({ sub, email }));
    expect(again.statusCode).toBe(200);
    expect(again.json().isNewUser).toBe(true);
    expect(again.json().user.id).not.toBe(user.id);
  });

  it('a password account cannot delete with confirm alone', async () => {
    const s = await register(t.app, 'Keeper', 'keeper-password-1');
    const res = await t.app.inject({
      method: 'DELETE',
      url: `${API}/me`,
      headers: bearer(s),
      payload: { confirm: 'DELETE' },
    });
    expect(res.statusCode).toBe(401);
  });
});

describe('Google sign-in not configured', () => {
  it('returns 503 google_not_configured and /health reports disabled', async () => {
    const plain = await createTestApp();
    try {
      const res = await plain.app.inject({
        method: 'POST',
        url: `${API}/auth/google`,
        payload: { idToken: await google.token() },
      });
      expect(res.statusCode).toBe(503);
      expect(res.json().error.code).toBe('google_not_configured');
      const health = await plain.app.inject({ method: 'GET', url: `${API}/health` });
      expect(health.json().checks.google).toEqual({ status: 'disabled' });
    } finally {
      await plain.close();
    }
  });
});
