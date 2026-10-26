import type { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import { DEFAULT_PREFERENCES, type SessionDoc, type UserDoc } from '../../db/types.js';
import { hashPassword, randomToken, sha256Hex, verifyPassword } from '../../lib/crypto.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { addDays, addMinutes } from '../../lib/time.js';
import { toUserDto } from '../shared/dto.js';
import type { TokensDto, UserDto } from '../shared/schemas.js';
import { describeMailError, EmailVerificationService } from './email-verification.js';
import type { GoogleIdentity } from './google.js';
import { AuthRepo } from './repo.js';

export interface SessionMeta {
  deviceId?: string | null;
  userAgent?: string | null;
}

const DISPLAY_NAME_MAX = 60; // users validator + DisplayName schema

/**
 * How long a just-rotated refresh token may be presented again without counting as reuse. A phone
 * syncing in the background can be frozen or killed after the server rotated its token but before
 * it stored the new one; it then retries with the old token and used to be signed out (the whole
 * family was revoked). Kept short, and honoured only while the replacement session is unused.
 */
export const REFRESH_GRACE_MS = 60_000;

export class AuthService {
  private readonly repo: AuthRepo;
  private readonly verification: EmailVerificationService;
  // argon2id hash of a random string, computed once per process; used to equalise login timing
  // when the email is unknown (so "no such user" is not trivially distinguishable from "wrong password").
  private readonly dummyHash: Promise<string>;

  constructor(private readonly ctx: AppContext) {
    this.repo = new AuthRepo(ctx.db);
    this.verification = new EmailVerificationService(ctx);
    this.dummyHash = hashPassword(randomToken(24));
  }

  // --- registration / login ------------------------------------------------------------------

  async register(
    input: { email: string; password: string; displayName: string },
    meta: SessionMeta,
  ): Promise<{ user: UserDto; tokens: TokensDto }> {
    const now = this.ctx.now();
    const user: UserDoc = {
      _id: newId(),
      email: input.email,
      displayName: input.displayName,
      passwordHash: await hashPassword(input.password),
      emailVerifiedAt: null,
      avatarMediaId: null,
      activeCoupleId: null,
      preferences: { ...DEFAULT_PREFERENCES, pingCooldownSeconds: this.ctx.env.pings.cooldownSeconds },
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
    };
    try {
      await this.repo.insertUser(user);
    } catch (err) {
      if (isDuplicateKey(err)) throw errors.conflict('An account with this email already exists');
      throw err;
    }
    const tokens = await this.createSession(user._id, null, meta);
    try {
      await this.verification.issue(user);
    } catch (err) {
      // Registration must not fail because the verification code could not be stored or sent.
      this.ctx.logger.warn(
        { kind: 'email_verification', userId: user._id.toHexString(), reason: describeMailError(err) },
        'could not issue verification code at registration',
      );
    }
    return { user: toUserDto(user), tokens };
  }

  async login(
    input: { email: string; password: string },
    meta: SessionMeta,
  ): Promise<{ user: UserDto; tokens: TokensDto }> {
    const user = await this.repo.findUserByEmail(input.email);
    // Accounts without a password (Google-only, or claimed via Google while unverified) are
    // verified against the dummy hash too, so they fail exactly like a wrong password.
    const ok = await verifyPassword(user?.passwordHash ?? (await this.dummyHash), input.password);
    if (!user?.passwordHash || !ok) throw errors.unauthorized('Invalid email or password');
    const tokens = await this.createSession(user._id, null, meta);
    return { user: toUserDto(user), tokens };
  }

  // --- Google sign-in ---------------------------------------------------------------------------

  /**
   * POST /auth/google. Resolution order: (a) account with this Google `sub`; (b) account with the
   * same (lowercased) email, which gets linked; (c) a new Google-only account.
   *
   * Account pre-hijacking defence for (b): if the existing account never verified its email, whoever
   * set its password may not own the address. The password is removed and every session revoked
   * before the Google user (who provably owns the address) gets tokens.
   */
  async googleSignIn(
    idToken: string,
    meta: SessionMeta,
  ): Promise<{ user: UserDto; tokens: TokensDto; isNewUser: boolean }> {
    if (!this.ctx.google) throw errors.googleNotConfigured();
    const identity = await this.ctx.google.verify(idToken);
    return this.resolveGoogle(identity, meta, 0);
  }

  private async resolveGoogle(
    identity: GoogleIdentity,
    meta: SessionMeta,
    attempt: number,
  ): Promise<{ user: UserDto; tokens: TokensDto; isNewUser: boolean }> {
    const retry = () => {
      if (attempt >= 2) throw errors.conflict('Could not complete Google sign-in, please try again');
      return this.resolveGoogle(identity, meta, attempt + 1);
    };
    const now = this.ctx.now();

    // (a) returning Google user
    const bySub = await this.repo.findUserByGoogleSub(identity.sub);
    if (bySub) {
      const tokens = await this.createSession(bySub._id, null, meta);
      return { user: toUserDto(bySub), tokens, isNewUser: false };
    }

    // (b) existing account with the same email → link
    const byEmail = await this.repo.findUserByEmail(identity.email);
    if (byEmail) {
      if (byEmail.googleSub && byEmail.googleSub !== identity.sub) {
        throw errors.conflict('This email is already linked to a different Google account');
      }
      const wasVerified = byEmail.emailVerifiedAt instanceof Date;
      let linked: UserDoc | null;
      try {
        linked = await this.repo.linkGoogle(byEmail._id, identity.sub, {
          verifiedAt: byEmail.emailVerifiedAt ?? now,
          clearPassword: !wasVerified,
          now,
        });
      } catch (err) {
        if (isDuplicateKey(err)) return retry();
        throw err;
      }
      if (!linked) return retry(); // lost a race with a concurrent link
      if (!wasVerified) {
        await this.revokeAllForUser(linked._id, 'google_claimed_unverified');
        this.ctx.logger.warn(
          { userId: linked._id.toHexString() },
          'google sign-in claimed an unverified account: password removed, sessions revoked',
        );
      }
      await this.repo.deleteEmailVerifications(linked._id);
      const tokens = await this.createSession(linked._id, null, meta);
      return { user: toUserDto(linked), tokens, isNewUser: false };
    }

    // (c) new Google-only account
    const user: UserDoc = {
      _id: newId(),
      email: identity.email,
      displayName: googleDisplayName(identity),
      passwordHash: null,
      googleSub: identity.sub,
      emailVerifiedAt: now,
      avatarMediaId: null,
      activeCoupleId: null,
      preferences: { ...DEFAULT_PREFERENCES, pingCooldownSeconds: this.ctx.env.pings.cooldownSeconds },
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
    };
    try {
      await this.repo.insertUser(user);
    } catch (err) {
      if (isDuplicateKey(err)) return retry(); // concurrent sign-in / register with the same identity
      throw err;
    }
    const tokens = await this.createSession(user._id, null, meta);
    return { user: toUserDto(user), tokens, isNewUser: true };
  }

  // --- refresh rotation ------------------------------------------------------------------------

  async refresh(refreshToken: string, meta: SessionMeta): Promise<{ tokens: TokensDto }> {
    const now = this.ctx.now();
    const session = await this.repo.findSessionByRefreshHash(sha256Hex(refreshToken));
    if (!session) throw errors.unauthorized('Invalid refresh token');

    if (session.revokedAt) throw errors.unauthorized('Session revoked');

    if (session.rotatedAt) {
      const retried = await this.graceRetry(session, meta, now);
      if (retried) return { tokens: retried };
      // Reuse of an already-rotated refresh token: treat the whole family as compromised.
      await this.revokeFamily(session.familyId, 'refresh_reuse');
      this.ctx.logger.warn(
        { userId: session.userId.toHexString() },
        'refresh token reuse detected; family revoked',
      );
      throw errors.unauthorized('Refresh token reuse detected');
    }

    if (session.expiresAt.getTime() <= now.getTime()) throw errors.unauthorized('Refresh token expired');

    const rotated = await this.repo.markRotated(session._id, now);
    if (!rotated) {
      // Lost a race with a concurrent refresh using the same token: also reuse.
      await this.revokeFamily(session.familyId, 'refresh_reuse');
      throw errors.unauthorized('Refresh token reuse detected');
    }

    const next = await this.createSessionWithId(session.userId, session.familyId, {
      deviceId: meta.deviceId ?? session.deviceId,
      userAgent: meta.userAgent ?? session.userAgent,
    });
    await this.repo.setReplacedBy(session._id, next.sessionId);
    return { tokens: next.tokens };
  }

  /**
   * The phone presents a token it already rotated. Within REFRESH_GRACE_MS, if the session it was
   * rotated into has never been used (no request with its access token, no refresh with its refresh
   * token), the phone most likely never received that response: revoke the unused replacement and
   * issue a fresh pair in the same family instead of signing the person out. The user agent must
   * match (a weak binding; both phones share it, so the "unused" check is the real guard). Anything
   * else falls through to reuse detection.
   */
  private async graceRetry(session: SessionDoc, meta: SessionMeta, now: Date): Promise<TokensDto | null> {
    if (!session.rotatedAt || !session.replacedBy || session.revokedAt) return null;
    if (now.getTime() - session.rotatedAt.getTime() > REFRESH_GRACE_MS) return null;
    if (meta.userAgent && session.userAgent && meta.userAgent.slice(0, 200) !== session.userAgent)
      return null;
    if (session.expiresAt.getTime() <= now.getTime()) return null;
    const dropped = await this.repo.revokeIfUnused(session.replacedBy, 'refresh_grace_replaced', now);
    if (!dropped) return null;
    this.ctx.hub.revokeSession(session.replacedBy.toHexString());
    const next = await this.createSessionWithId(session.userId, session.familyId, {
      deviceId: meta.deviceId ?? session.deviceId,
      userAgent: meta.userAgent ?? session.userAgent,
    });
    await this.repo.setReplacedBy(session._id, next.sessionId);
    this.ctx.logger.info(
      { userId: session.userId.toHexString() },
      'refresh retried within the grace window; unused replacement session revoked',
    );
    return next.tokens;
  }

  async logout(refreshToken: string, deviceId: string | undefined): Promise<void> {
    const session = await this.repo.findSessionByRefreshHash(sha256Hex(refreshToken));
    if (!session) return; // idempotent; do not reveal whether the token existed
    await this.revokeFamily(session.familyId, 'logout');
    const targetDeviceId = deviceId ?? session.deviceId;
    if (targetDeviceId) await this.repo.deleteDeviceToken(session.userId, targetDeviceId);
  }

  async revokeFamily(familyId: ObjectId, reason: string): Promise<void> {
    const ids = await this.repo.familySessionIds(familyId);
    await this.repo.revokeFamily(familyId, reason, this.ctx.now());
    for (const id of ids) this.ctx.hub.revokeSession(id.toHexString());
  }

  async revokeAllForUser(userId: ObjectId, reason: string): Promise<void> {
    const ids = await this.repo.userSessionIds(userId);
    await this.repo.revokeAllForUser(userId, reason, this.ctx.now());
    for (const id of ids) this.ctx.hub.revokeSession(id.toHexString());
    this.ctx.hub.revokeUser(userId.toHexString());
  }

  // --- password reset ---------------------------------------------------------------------------

  async forgotPassword(email: string): Promise<void> {
    const user = await this.repo.findUserByEmail(email);
    if (!user) return; // 202 regardless
    const now = this.ctx.now();
    const token = randomToken(32);
    const expiresAt = addMinutes(now, this.ctx.env.auth.passwordResetTtlMinutes);
    await this.repo.insertPasswordReset({
      _id: newId(),
      userId: user._id,
      tokenHash: sha256Hex(token),
      expiresAt,
      usedAt: null,
      createdAt: now,
    });
    const resetUrl = `${this.ctx.env.publicBaseUrl}/reset-password?token=${encodeURIComponent(token)}`;
    // Not awaited: the response (always 202) must not reveal, by timing or by error, whether an
    // account exists or whether delivery worked.
    let sending: Promise<void>;
    try {
      sending = this.ctx.mailer.sendPasswordReset({ to: user.email, resetToken: token, resetUrl, expiresAt });
    } catch (err) {
      sending = Promise.reject(err);
    }
    const userId = user._id.toHexString();
    void sending.catch((err: unknown) => {
      this.ctx.logger.warn(
        { kind: 'password_reset', userId, reason: describeMailError(err) },
        'password reset email could not be sent',
      );
    });
  }

  async resetPassword(token: string, newPassword: string): Promise<void> {
    const now = this.ctx.now();
    const reset = await this.repo.consumePasswordReset(sha256Hex(token), now);
    if (!reset) throw errors.validation('Invalid or expired reset token');
    const user = await this.repo.findUserById(reset.userId);
    if (!user) throw errors.validation('Invalid or expired reset token');
    await this.repo.updatePassword(user._id, await hashPassword(newPassword), now);
    await this.revokeAllForUser(user._id, 'password_reset');
  }

  // --- helpers ------------------------------------------------------------------------------------

  private async createSession(
    userId: ObjectId,
    familyId: ObjectId | null,
    meta: SessionMeta,
  ): Promise<TokensDto> {
    return (await this.createSessionWithId(userId, familyId, meta)).tokens;
  }

  private async createSessionWithId(
    userId: ObjectId,
    familyId: ObjectId | null,
    meta: SessionMeta,
  ): Promise<{ tokens: TokensDto; sessionId: ObjectId }> {
    const now = this.ctx.now();
    const refreshToken = randomToken(32); // 256-bit
    const session: SessionDoc = {
      _id: newId(),
      userId,
      familyId: familyId ?? newId(),
      refreshHash: sha256Hex(refreshToken),
      expiresAt: addDays(now, this.ctx.env.auth.refreshTokenTtlDays),
      createdAt: now,
      lastUsedAt: null,
      rotatedAt: null,
      revokedAt: null,
      revokedReason: null,
      deviceId: meta.deviceId ?? null,
      userAgent: meta.userAgent ? meta.userAgent.slice(0, 200) : null,
    };
    await this.repo.insertSession(session);
    const access = await this.ctx.tokens.signAccess({
      userId: userId.toHexString(),
      sessionId: session._id.toHexString(),
    });
    return {
      sessionId: session._id,
      tokens: {
        accessToken: access.token,
        accessExpiresAt: access.expiresAt.toISOString(),
        refreshToken,
        refreshExpiresAt: session.expiresAt.toISOString(),
      },
    };
  }
}

/** given_name → name → email local part, stripped of control characters and cut to the schema max. */
export function googleDisplayName(identity: Pick<GoogleIdentity, 'givenName' | 'name' | 'email'>): string {
  for (const candidate of [identity.givenName, identity.name, identity.email.split('@')[0]]) {
    const cleaned = cleanDisplayName(candidate);
    if (cleaned) return cleaned;
  }
  return 'Risaina user';
}

function cleanDisplayName(value: string | null | undefined): string {
  if (!value) return '';
  let out = '';
  for (const ch of value.replace(/\s+/g, ' ').trim()) {
    const cp = ch.codePointAt(0) ?? 0;
    if (cp < 0x20 || cp === 0x7f || (cp >= 0x80 && cp < 0xa0)) continue;
    if (out.length + ch.length > DISPLAY_NAME_MAX) break; // never split a surrogate pair
    out += ch;
  }
  return out.trim();
}

export function isDuplicateKey(err: unknown): boolean {
  return typeof err === 'object' && err !== null && (err as { code?: number }).code === 11000;
}
