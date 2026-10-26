import type { ObjectId, UpdateFilter } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { LiveLocationDoc } from '../../db/types.js';

export class LocationRepo {
  constructor(private readonly db: Collections) {}

  byId(id: ObjectId): Promise<LiveLocationDoc | null> {
    return this.db.liveLocations.findOne({ _id: id });
  }

  liveOf(coupleId: ObjectId, userId: ObjectId): Promise<LiveLocationDoc | null> {
    return this.db.liveLocations.findOne({ coupleId, userId, live: true });
  }

  /** Both members' live sessions (the caller filters out expired ones: the TTL monitor lags). */
  liveForCouple(coupleId: ObjectId): Promise<LiveLocationDoc[]> {
    return this.db.liveLocations.find({ coupleId, live: true }).toArray();
  }

  liveOfUser(
    userId: ObjectId,
    filter: { sosId?: ObjectId; coupleId?: ObjectId } = {},
  ): Promise<LiveLocationDoc[]> {
    return this.db.liveLocations.find({ userId, live: true, ...filter }).toArray();
  }

  async insert(doc: LiveLocationDoc): Promise<void> {
    await this.db.liveLocations.insertOne(doc);
  }

  /** Updates a session only while it is live. Never upserts: a late write cannot recreate it. */
  updateLive(id: ObjectId, update: UpdateFilter<LiveLocationDoc>): Promise<LiveLocationDoc | null> {
    return this.db.liveLocations.findOneAndUpdate({ _id: id, live: true }, update, {
      returnDocument: 'after',
    });
  }

  /**
   * Turns a live session into a tombstone: coordinates are removed at once and the document itself
   * is purged by the TTL index at `purgeAt`. Returns the session as it was, or null if not live.
   */
  tombstone(id: ObjectId, userId: ObjectId, now: Date, purgeAt: Date): Promise<LiveLocationDoc | null> {
    return this.db.liveLocations.findOneAndUpdate(
      { _id: id, userId, live: true },
      { $set: { live: false, fix: null, stoppedAt: now, purgeAt, updatedAt: now } },
      { returnDocument: 'before' },
    );
  }

  async deleteById(id: ObjectId): Promise<void> {
    await this.db.liveLocations.deleteOne({ _id: id });
  }

  async deleteAllOfUser(userId: ObjectId): Promise<number> {
    return (await this.db.liveLocations.deleteMany({ userId })).deletedCount;
  }
}
