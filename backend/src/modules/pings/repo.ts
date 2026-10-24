import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { PingDoc, UserDoc } from '../../db/types.js';
import { beforeCursorFilter, type Cursor } from '../../lib/pagination.js';

export class PingRepo {
  constructor(private readonly db: Collections) {}

  findByIdempotency(senderId: ObjectId, idempotencyKey: string): Promise<PingDoc | null> {
    return this.db.pings.findOne({ senderId, idempotencyKey });
  }

  findSenderPrefs(senderId: ObjectId): Promise<UserDoc | null> {
    return this.db.users.findOne({ _id: senderId }, { projection: { preferences: 1 } });
  }

  lastBySender(senderId: ObjectId, coupleId: ObjectId): Promise<PingDoc | null> {
    return this.db.pings.findOne({ senderId, coupleId }, { sort: { createdAt: -1 } });
  }

  countBySenderSince(senderId: ObjectId, coupleId: ObjectId, since: Date): Promise<number> {
    return this.db.pings.countDocuments({ senderId, coupleId, createdAt: { $gt: since } });
  }

  async insert(doc: PingDoc): Promise<void> {
    await this.db.pings.insertOne(doc);
  }

  findInCouple(id: ObjectId, coupleId: ObjectId): Promise<PingDoc | null> {
    return this.db.pings.findOne({ _id: id, coupleId });
  }

  async markDelivered(id: ObjectId, now: Date): Promise<PingDoc | null> {
    return this.db.pings.findOneAndUpdate(
      { _id: id, deliveredAt: null },
      { $set: { status: 'delivered', deliveredAt: now } },
      { returnDocument: 'after' },
    );
  }

  async markSeen(id: ObjectId, now: Date): Promise<PingDoc | null> {
    const doc = await this.db.pings.findOne({ _id: id });
    if (!doc) return null;
    return this.db.pings.findOneAndUpdate(
      { _id: id, seenAt: null },
      { $set: { status: 'seen', seenAt: now, deliveredAt: doc.deliveredAt ?? now } },
      { returnDocument: 'after' },
    );
  }

  list(coupleId: ObjectId, limit: number, before: Cursor | null): Promise<PingDoc[]> {
    const filter = before ? { coupleId, ...beforeCursorFilter(before) } : { coupleId };
    return this.db.pings
      .find(filter)
      .sort({ createdAt: -1, _id: -1 })
      .limit(limit + 1)
      .toArray();
  }
}
