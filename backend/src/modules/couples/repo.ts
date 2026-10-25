import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { CoupleDoc, MediaDoc, UserDoc } from '../../db/types.js';

export class CoupleRepo {
  constructor(private readonly db: Collections) {}

  findById(id: ObjectId): Promise<CoupleDoc | null> {
    return this.db.couples.findOne({ _id: id });
  }

  findPendingForUser(userId: ObjectId): Promise<CoupleDoc | null> {
    return this.db.couples.findOne({ memberIds: userId, status: 'pending' }, { sort: { createdAt: -1 } });
  }

  findMedia(id: ObjectId): Promise<MediaDoc | null> {
    return this.db.media.findOne({ _id: id });
  }

  async insert(doc: CoupleDoc): Promise<void> {
    await this.db.couples.insertOne(doc);
  }

  findUser(id: ObjectId): Promise<UserDoc | null> {
    return this.db.users.findOne({ _id: id });
  }

  async patch(id: ObjectId, set: Partial<CoupleDoc>): Promise<CoupleDoc | null> {
    return this.db.couples.findOneAndUpdate(
      { _id: id, status: 'active' },
      { $set: set },
      { returnDocument: 'after' },
    );
  }

  /**
   * Atomically transition active → ended and mark the couple for purging. Returns null when the
   * couple was not active.
   */
  async end(id: ObjectId, endedBy: ObjectId, now: Date): Promise<CoupleDoc | null> {
    return this.db.couples.findOneAndUpdate(
      { _id: id, status: 'active' },
      { $set: { status: 'ended', endedAt: now, endedBy, updatedAt: now, purgedAt: now } },
      { returnDocument: 'after' },
    );
  }

  async endPendingForUser(userId: ObjectId, now: Date): Promise<void> {
    await this.db.couples.updateMany(
      { memberIds: userId, status: 'pending' },
      { $set: { status: 'ended', endedAt: now, endedBy: userId, updatedAt: now } },
    );
  }

  async clearActiveCouple(memberIds: ObjectId[], coupleId: ObjectId, now: Date): Promise<void> {
    await this.db.users.updateMany(
      { _id: { $in: memberIds }, activeCoupleId: coupleId },
      { $set: { activeCoupleId: null, updatedAt: now } },
    );
  }
}
