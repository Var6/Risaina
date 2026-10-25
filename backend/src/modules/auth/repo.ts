import { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { EmailVerificationDoc, PasswordResetDoc, SessionDoc, UserDoc } from '../../db/types.js';

export class AuthRepo {
  constructor(private readonly db: Collections) {}

  findUserByEmail(email: string): Promise<UserDoc | null> {
    return this.db.users.findOne({ email, deletedAt: null });
  }

  findUserById(id: ObjectId): Promise<UserDoc | null> {
    return this.db.users.findOne({ _id: id, deletedAt: null });
  }

  findUserByGoogleSub(sub: string): Promise<UserDoc | null> {
    return this.db.users.findOne({ googleSub: sub, deletedAt: null });
  }

  /**
   * Links a Google identity to an existing account that has none yet (atomic: loses cleanly to a
   * concurrent link). `clearPassword` is used when the account's email was never verified.
   */
  linkGoogle(
    userId: ObjectId,
    googleSub: string,
    opts: { verifiedAt: Date; clearPassword: boolean; now: Date },
  ): Promise<UserDoc | null> {
    const set: Partial<UserDoc> = { googleSub, emailVerifiedAt: opts.verifiedAt, updatedAt: opts.now };
    if (opts.clearPassword) set.passwordHash = null;
    return this.db.users.findOneAndUpdate(
      { _id: userId, deletedAt: null, googleSub: { $exists: false } },
      { $set: set },
      { returnDocument: 'after' },
    );
  }

  /** Sets emailVerifiedAt if it is not set yet; returns the current user document. */
  async markEmailVerified(userId: ObjectId, now: Date): Promise<UserDoc | null> {
    await this.db.users.updateOne(
      { _id: userId, deletedAt: null, emailVerifiedAt: null }, // `null` also matches a missing field
      { $set: { emailVerifiedAt: now, updatedAt: now } },
    );
    return this.findUserById(userId);
  }

  async insertUser(doc: UserDoc): Promise<void> {
    await this.db.users.insertOne(doc);
  }

  async updatePassword(userId: ObjectId, passwordHash: string, now: Date): Promise<void> {
    await this.db.users.updateOne({ _id: userId }, { $set: { passwordHash, updatedAt: now } });
  }

  // --- sessions ---------------------------------------------------------------------------

  async insertSession(doc: SessionDoc): Promise<void> {
    await this.db.sessions.insertOne(doc);
  }

  findSessionByRefreshHash(refreshHash: string): Promise<SessionDoc | null> {
    return this.db.sessions.findOne({ refreshHash });
  }

  /** Atomically marks a session's refresh token as consumed. Returns false if it already was. */
  async markRotated(sessionId: ObjectId, now: Date): Promise<boolean> {
    const res = await this.db.sessions.updateOne(
      { _id: sessionId, rotatedAt: null, revokedAt: null },
      { $set: { rotatedAt: now, lastUsedAt: now } },
    );
    return res.modifiedCount === 1;
  }

  async familySessionIds(familyId: ObjectId): Promise<ObjectId[]> {
    const docs = await this.db.sessions
      .find({ familyId, revokedAt: null }, { projection: { _id: 1 } })
      .toArray();
    return docs.map((d) => d._id);
  }

  async revokeFamily(familyId: ObjectId, reason: string, now: Date): Promise<void> {
    await this.db.sessions.updateMany(
      { familyId, revokedAt: null },
      { $set: { revokedAt: now, revokedReason: reason } },
    );
  }

  async userSessionIds(userId: ObjectId): Promise<ObjectId[]> {
    const docs = await this.db.sessions
      .find({ userId, revokedAt: null }, { projection: { _id: 1 } })
      .toArray();
    return docs.map((d) => d._id);
  }

  async revokeAllForUser(userId: ObjectId, reason: string, now: Date): Promise<void> {
    await this.db.sessions.updateMany(
      { userId, revokedAt: null },
      { $set: { revokedAt: now, revokedReason: reason } },
    );
  }

  // --- password resets --------------------------------------------------------------------

  async insertPasswordReset(doc: PasswordResetDoc): Promise<void> {
    await this.db.passwordResets.insertOne(doc);
  }

  /** Consumes an unused, unexpired reset token atomically. */
  async consumePasswordReset(tokenHash: string, now: Date): Promise<PasswordResetDoc | null> {
    return this.db.passwordResets.findOneAndUpdate(
      { tokenHash, usedAt: null, expiresAt: { $gt: now } },
      { $set: { usedAt: now } },
      { returnDocument: 'after' },
    );
  }

  // --- email verification codes -----------------------------------------------------------

  async insertEmailVerification(doc: EmailVerificationDoc): Promise<void> {
    await this.db.emailVerifications.insertOne(doc);
  }

  /** The newest code is the only live one; older documents are kept until TTL for the hourly cap. */
  latestEmailVerification(userId: ObjectId): Promise<EmailVerificationDoc | null> {
    return this.db.emailVerifications.findOne({ userId }, { sort: { createdAt: -1, _id: -1 } });
  }

  async emailVerificationTimesSince(userId: ObjectId, since: Date): Promise<Date[]> {
    const docs = await this.db.emailVerifications
      .find({ userId, createdAt: { $gt: since } }, { projection: { createdAt: 1 } })
      .sort({ createdAt: 1 })
      .toArray();
    return docs.map((d) => d.createdAt);
  }

  /** Records a wrong guess. Returns null when the code had already used up its attempts. */
  incrementVerificationAttempts(id: ObjectId, maxAttempts: number): Promise<EmailVerificationDoc | null> {
    return this.db.emailVerifications.findOneAndUpdate(
      { _id: id, attempts: { $lt: maxAttempts } },
      { $inc: { attempts: 1 } },
      { returnDocument: 'after' },
    );
  }

  async deleteEmailVerifications(userId: ObjectId): Promise<void> {
    await this.db.emailVerifications.deleteMany({ userId });
  }

  async deleteDeviceToken(userId: ObjectId, deviceId: string): Promise<void> {
    await this.db.deviceTokens.deleteOne({ userId, deviceId });
  }

  static id(hex: string): ObjectId {
    return new ObjectId(hex);
  }
}
