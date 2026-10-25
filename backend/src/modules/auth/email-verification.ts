import { randomInt } from 'node:crypto';
import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { UserDoc } from '../../db/types.js';
import { deriveSubkey, hmacHex, safeEqualHex } from '../../lib/crypto.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { addMinutes } from '../../lib/time.js';
import { MailerError } from '../../services/mailer/types.js';
import { toUserDto } from '../shared/dto.js';
import type { UserDto } from '../shared/schemas.js';
import { AuthRepo } from './repo.js';

/** Wrong guesses allowed per code; the code is dead after this many. */
export const MAX_CODE_ATTEMPTS = 5;
const HOUR_MS = 60 * 60 * 1000;

/** Error description that is safe to log: never a recipient, code or provider response body. */
export function describeMailError(err: unknown): string {
  if (err instanceof MailerError) return err.message;
  return err instanceof Error ? err.name : 'unknown';
}

/**
 * Email verification for password accounts (non-blocking: the app works unverified, `emailVerified`
 * on the User DTO only reports the status).
 *
 * A 6-digit code (crypto.randomInt) is stored as HMAC-SHA256(userId:code) under a key derived from
 * JWT_SECRET, so a database dump alone does not let anyone brute-force the 10^6 code space offline.
 * Only the newest code for a user is live.
 */
export class EmailVerificationService {
  private readonly repo: AuthRepo;
  private readonly key: Buffer;

  constructor(private readonly ctx: AppContext) {
    this.repo = new AuthRepo(ctx.db);
    this.key = deriveSubkey(ctx.env.jwtSecret, 'risaina/email-verification/v1');
  }

  private hash(userId: ObjectId, code: string): string {
    return hmacHex(this.key, `${userId.toHexString()}:${code}`);
  }

  /**
   * Stores a fresh code and hands the email to the mailer without waiting for delivery. Delivery
   * failures are logged (redacted) and never surface to the client.
   */
  async issue(user: UserDoc): Promise<void> {
    const now = this.ctx.now();
    const code = randomInt(0, 1_000_000).toString().padStart(6, '0');
    const expiresAt = addMinutes(now, this.ctx.env.emailVerification.ttlMinutes);
    await this.repo.insertEmailVerification({
      _id: newId(),
      userId: user._id,
      codeHash: this.hash(user._id, code),
      expiresAt,
      attempts: 0,
      createdAt: now,
    });
    const userId = user._id.toHexString();
    let sending: Promise<void>;
    try {
      sending = this.ctx.mailer.sendEmailVerification({
        to: user.email,
        displayName: user.displayName,
        code,
        expiresAt,
      });
    } catch (err) {
      sending = Promise.reject(err);
    }
    void sending.catch((err: unknown) => {
      this.ctx.logger.warn(
        { kind: 'email_verification', userId, reason: describeMailError(err) },
        'verification email could not be sent',
      );
    });
  }

  /** POST /auth/email/resend: at most one per cooldown (60 s) and N (5) per rolling hour. */
  async resend(userIdHex: string): Promise<void> {
    const user = await this.mustUser(userIdHex);
    if (user.emailVerifiedAt) throw errors.conflict('Email is already verified');
    const now = this.ctx.now();
    const { resendCooldownSeconds, maxPerHour } = this.ctx.env.emailVerification;

    const latest = await this.repo.latestEmailVerification(user._id);
    if (latest) {
      const elapsedMs = now.getTime() - latest.createdAt.getTime();
      if (elapsedMs < resendCooldownSeconds * 1000) {
        throw errors.rateLimited(
          (resendCooldownSeconds * 1000 - elapsedMs) / 1000,
          'Please wait a moment before asking for another code',
        );
      }
    }
    const recent = await this.repo.emailVerificationTimesSince(user._id, new Date(now.getTime() - HOUR_MS));
    if (recent.length >= maxPerHour) {
      const oldest = recent[0] ?? now;
      throw errors.rateLimited(
        (oldest.getTime() + HOUR_MS - now.getTime()) / 1000,
        'Too many codes requested. Try again later.',
      );
    }
    await this.issue(user);
  }

  /** POST /auth/email/verify. Idempotent for an already verified account. */
  async verify(userIdHex: string, code: string): Promise<{ user: UserDto }> {
    const user = await this.mustUser(userIdHex);
    if (user.emailVerifiedAt) return { user: toUserDto(user) };
    const now = this.ctx.now();

    const latest = await this.repo.latestEmailVerification(user._id);
    if (!latest || latest.expiresAt.getTime() <= now.getTime()) throw errors.verificationCodeExpired();
    if (latest.attempts >= MAX_CODE_ATTEMPTS)
      throw errors.verificationAttemptsExceeded(this.retryAfter(latest.createdAt, now));

    if (!safeEqualHex(this.hash(user._id, code), latest.codeHash)) {
      const updated = await this.repo.incrementVerificationAttempts(latest._id, MAX_CODE_ATTEMPTS);
      if (!updated || updated.attempts >= MAX_CODE_ATTEMPTS) {
        throw errors.verificationAttemptsExceeded(this.retryAfter(latest.createdAt, now));
      }
      throw errors.verificationCodeInvalid(MAX_CODE_ATTEMPTS - updated.attempts);
    }

    const verified = await this.repo.markEmailVerified(user._id, now);
    if (!verified) throw errors.unauthorized('Account no longer exists');
    await this.repo.deleteEmailVerifications(user._id);
    return { user: toUserDto(verified) };
  }

  /** Seconds until a new code may be requested (used as Retry-After once a code is used up). */
  private retryAfter(createdAt: Date, now: Date): number {
    const cooldownMs = this.ctx.env.emailVerification.resendCooldownSeconds * 1000;
    return Math.max(1, (createdAt.getTime() + cooldownMs - now.getTime()) / 1000);
  }

  private async mustUser(userIdHex: string): Promise<UserDoc> {
    const user = await this.repo.findUserById(new ObjectId(userIdHex));
    if (!user) throw errors.unauthorized('Account no longer exists');
    return user;
  }
}
