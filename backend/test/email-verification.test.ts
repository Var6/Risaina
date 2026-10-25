import { randomUUID } from 'node:crypto';
import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { API, bearer, createTestApp, register, type Session, type TestApp } from './helpers/app.js';

let t: TestApp;

beforeAll(async () => {
  t = await createTestApp();
});
afterAll(async () => {
  await t.close();
});

const uniqueEmail = (p: string) => `${p}-${randomUUID().slice(0, 8)}@example.test`;

function verify(s: Session, code: string) {
  return t.app.inject({
    method: 'POST',
    url: `${API}/auth/email/verify`,
    headers: bearer(s),
    payload: { code },
  });
}
function resend(s: Session) {
  return t.app.inject({ method: 'POST', url: `${API}/auth/email/resend`, headers: bearer(s), payload: {} });
}
function me(s: Session) {
  return t.app.inject({ method: 'GET', url: `${API}/me`, headers: bearer(s) });
}
const otherCode = (code: string) => (code === '000000' ? '111111' : '000000');

describe('email verification', () => {
  it('register emails a 6-digit code stored only as a hash; verify flips emailVerified everywhere', async () => {
    const email = uniqueEmail('vera');
    const s = await register(t.app, 'Vera', 'vera-password-123', email);
    expect(s.user.emailVerified).toBe(false);
    expect(s.user.hasPassword).toBe(true);

    const mail = t.mailer.verifications.find((m) => m.to === email)!;
    expect(mail.code).toMatch(/^\d{6}$/);
    expect(mail.displayName).toBe('Vera');
    const doc = await t.db.emailVerifications.findOne({ userId: new ObjectId(s.user.id) });
    expect(doc!.codeHash).toMatch(/^[a-f0-9]{64}$/);
    expect(doc!.codeHash).not.toContain(mail.code);
    expect(doc!.attempts).toBe(0);
    const ttlMs = doc!.expiresAt.getTime() - doc!.createdAt.getTime();
    expect(ttlMs).toBe(30 * 60_000);

    expect((await me(s)).json().user.emailVerified).toBe(false);

    const ok = await verify(s, ` ${mail.code} `);
    expect(ok.statusCode).toBe(200);
    expect(ok.json().user).toMatchObject({ id: s.user.id, emailVerified: true });
    expect((await me(s)).json().user.emailVerified).toBe(true);
    expect(await t.db.emailVerifications.countDocuments({ userId: new ObjectId(s.user.id) })).toBe(0);

    const login = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/login`,
      payload: { email, password: 'vera-password-123' },
    });
    expect(login.json().user.emailVerified).toBe(true);

    // idempotent once verified; resend is pointless now
    expect((await verify(s, '123456')).statusCode).toBe(200);
    const again = await resend(s);
    expect(again.statusCode).toBe(409);
    expect(again.json().error.code).toBe('conflict');
  });

  it('5 wrong codes kill the code (400 ×4 with attemptsRemaining, then 429); a new code works', async () => {
    const email = uniqueEmail('wrong');
    const s = await register(t.app, 'Wrong', 'wrong-password-123', email);
    const code = t.mailer.lastCodeFor(email)!;
    const bad = otherCode(code);

    for (const remaining of [4, 3, 2, 1]) {
      const res = await verify(s, bad);
      expect(res.statusCode).toBe(400);
      expect(res.json().error).toMatchObject({
        code: 'verification_code_invalid',
        details: { attemptsRemaining: remaining },
      });
    }
    const fifth = await verify(s, bad);
    expect(fifth.statusCode).toBe(429);
    expect(fifth.json().error.code).toBe('verification_attempts_exceeded');
    expect(fifth.headers['retry-after']).toBeDefined();
    // even the right code is refused now
    const right = await verify(s, code);
    expect(right.statusCode).toBe(429);
    expect((await me(s)).json().user.emailVerified).toBe(false);

    // malformed codes are validation errors and do not count as attempts
    expect((await verify(s, '12ab56')).statusCode).toBe(400);
    expect((await verify(s, '12ab56')).json().error.code).toBe('validation_failed');

    t.clock.advance(61_000);
    expect((await resend(s)).statusCode).toBe(202);
    const fresh = t.mailer.lastCodeFor(email)!;
    expect((await verify(s, fresh)).statusCode).toBe(200);
  });

  it('an expired code answers 410 verification_code_expired', async () => {
    const email = uniqueEmail('late');
    await register(t.app, 'Late', 'late-password-123', email);
    const code = t.mailer.lastCodeFor(email)!;
    t.clock.advance(30 * 60_000 + 1000);
    // the 15-minute access token has expired too; sign in again
    const login = await t.app.inject({
      method: 'POST',
      url: `${API}/auth/login`,
      payload: { email, password: 'late-password-123' },
    });
    const res = await verify(login.json<Session>(), code);
    expect(res.statusCode).toBe(410);
    expect(res.json().error.code).toBe('verification_code_expired');
  });

  it('resend: 60 s cooldown, only the newest code is live, at most 5 codes per hour', async () => {
    const email = uniqueEmail('again');
    const s = await register(t.app, 'Again', 'again-password-1', email);
    const first = t.mailer.lastCodeFor(email)!;

    const tooSoon = await resend(s);
    expect(tooSoon.statusCode).toBe(429);
    expect(tooSoon.json().error.code).toBe('rate_limited');
    expect(Number(tooSoon.headers['retry-after'])).toBeGreaterThan(0);
    expect(Number(tooSoon.headers['retry-after'])).toBeLessThanOrEqual(60);

    t.clock.advance(61_000);
    const ok = await resend(s);
    expect(ok.statusCode).toBe(202);
    expect(ok.json()).toEqual({ accepted: true });
    const second = t.mailer.lastCodeFor(email)!;
    expect(t.mailer.verifications.filter((m) => m.to === email)).toHaveLength(2);

    if (first !== second) {
      const old = await verify(s, first);
      expect(old.statusCode).toBe(400); // superseded
    }

    // register (1) + 4 resends = 5 within the hour; the 6th is refused
    for (let i = 0; i < 3; i++) {
      t.clock.advance(61_000);
      expect((await resend(s)).statusCode).toBe(202);
    }
    t.clock.advance(61_000);
    const capped = await resend(s);
    expect(capped.statusCode).toBe(429);
    expect(Number(capped.headers['retry-after'])).toBeGreaterThan(60);

    // requires authentication
    expect(
      (await t.app.inject({ method: 'POST', url: `${API}/auth/email/resend`, payload: {} })).statusCode,
    ).toBe(401);
  });

  it('a failing mailer never breaks register, resend or forgot-password', async () => {
    t.mailer.fail = true;
    try {
      const email = uniqueEmail('outage');
      const s = await register(t.app, 'Outage', 'outage-password-1', email);
      expect(s.user.emailVerified).toBe(false);
      expect(await t.db.emailVerifications.countDocuments({ userId: new ObjectId(s.user.id) })).toBe(1);
      t.clock.advance(61_000);
      expect((await resend(s)).statusCode).toBe(202);
      const forgot = await t.app.inject({
        method: 'POST',
        url: `${API}/auth/password/forgot`,
        payload: { email },
      });
      expect(forgot.statusCode).toBe(202);
    } finally {
      t.mailer.fail = false;
    }
  });

  it('creates the TTL index on expiresAt', async () => {
    const indexes = await t.db.emailVerifications.indexes();
    const ttl = indexes.find((i) => i.name === 'expiresAt_ttl');
    expect(ttl?.expireAfterSeconds).toBe(3600);
    const users = await t.db.users.indexes();
    expect(users.find((i) => i.name === 'googleSub_unique')).toMatchObject({ unique: true });
  });
});
