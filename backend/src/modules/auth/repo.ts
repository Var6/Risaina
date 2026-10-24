import { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { PasswordResetDoc, SessionDoc, UserDoc } from '../../db/types.js';

export class AuthRepo {
  constructor(private readonly db: Collections) {}

  findUserByEmail(email: string): Promise<UserDoc | null> {
    return this.db.users.findOne({ email, deletedAt: null });
  }

  findUserById(id: ObjectId): Promise<UserDoc | null> {
    return this.db.users.findOne({ _id: id, deletedAt: null });
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

  async deleteDeviceToken(userId: ObjectId, deviceId: string): Promise<void> {
    await this.db.deviceTokens.deleteOne({ userId, deviceId });
  }

  static id(hex: string): ObjectId {
    return new ObjectId(hex);
  }
}
