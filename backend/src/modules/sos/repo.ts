import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { SosDoc } from '../../db/types.js';

export class SosRepo {
  constructor(private readonly db: Collections) {}

  async insert(doc: SosDoc): Promise<void> {
    await this.db.sos.insertOne(doc);
  }

  /** The sender's alerts since `since`, oldest first (for the 3-per-10-minutes limit). */
  recentBySender(senderId: ObjectId, since: Date): Promise<SosDoc[]> {
    return this.db.sos
      .find({ senderId, createdAt: { $gte: since } }, { projection: { createdAt: 1 } })
      .sort({ createdAt: 1 })
      .toArray();
  }

  findInCouple(id: ObjectId, coupleId: ObjectId): Promise<SosDoc | null> {
    return this.db.sos.findOne({ _id: id, coupleId });
  }

  async acknowledge(id: ObjectId, now: Date): Promise<SosDoc | null> {
    return this.db.sos.findOneAndUpdate(
      { _id: id, acknowledgedAt: null },
      { $set: { acknowledgedAt: now } },
      { returnDocument: 'after' },
    );
  }

  list(coupleId: ObjectId, limit: number): Promise<SosDoc[]> {
    return this.db.sos.find({ coupleId }).sort({ createdAt: -1, _id: -1 }).limit(limit).toArray();
  }
}
