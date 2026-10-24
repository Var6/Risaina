import type { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import { DEFAULT_PREFERENCES, type SessionDoc, type UserDoc } from '../../db/types.js';
import { hashPassword, randomToken, sha256Hex, verifyPassword } from '../../lib/crypto.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { addDays, addMinutes } from '../../lib/time.js';
import { toUserDto } from '../shared/dto.js';
import type { TokensDto, UserDto } from '../shared/schemas.js';
import { AuthRepo } from './repo.js';

export interface SessionMeta {
  deviceId?: string | null;
  userAgent?: string | null;
}

export class AuthService {
  private readonly repo: AuthRepo;
  // argon2id hash of a random string, computed once per process; used to equalise login timing
  // when the email is unknown (so "no such user" is not trivially distinguishable from "wrong password").
  private readonly dummyHash: Promise<string>;

  constructor(private readonly ctx: AppContext) {
    this.repo = new AuthRepo(ctx.db);
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
    return { user: toUserDto(user), tokens };
  }

  async login(
    input: { email: string; password: string },
    meta: SessionMeta,
  ): Promise<{ user: UserDto; tokens: TokensDto }> {
    const user = await this.repo.findUserByEmail(input.email);
    const ok = await verifyPassword(user?.passwordHash ?? (await this.dummyHash), input.password);
    if (!user || !ok) throw errors.unauthorized('Invalid email or password');
    const tokens = await this.createSession(user._id, null, meta);
    return { user: toUserDto(user), tokens };
  }

  // --- refresh rotation ------------------------------------------------------------------------

  async refresh(refreshToken: string, meta: SessionMeta): Promise<{ tokens: TokensDto }> {
    const now = this.ctx.now();
    const session = await this.repo.findSessionByRefreshHash(sha256Hex(refreshToken));
    if (!session) throw errors.unauthorized('Invalid refresh token');

    if (session.revokedAt) throw errors.unauthorized('Session revoked');

    if (session.rotatedAt) {
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

    const tokens = await this.createSession(session.userId, session.familyId, {
      deviceId: meta.deviceId ?? session.deviceId,
      userAgent: meta.userAgent ?? session.userAgent,
    });
    return { tokens };
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
    await this.ctx.mailer.sendPasswordReset({ to: user.email, resetToken: token, resetUrl, expiresAt });
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
      accessToken: access.token,
      accessExpiresAt: access.expiresAt.toISOString(),
      refreshToken,
      refreshExpiresAt: session.expiresAt.toISOString(),
    };
  }
}

export function isDuplicateKey(err: unknown): boolean {
  return typeof err === 'object' && err !== null && (err as { code?: number }).code === 11000;
}
