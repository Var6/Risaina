import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { SosDeliveryInfo, SosDoc } from '../../db/types.js';

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

  findByIdempotency(senderId: ObjectId, idempotencyKey: string): Promise<SosDoc | null> {
    return this.db.sos.findOne({ senderId, idempotencyKey });
  }

  async acknowledge(id: ObjectId, now: Date): Promise<SosDoc | null> {
    return this.db.sos.findOneAndUpdate(
      { _id: id, acknowledgedAt: null },
      { $set: { acknowledgedAt: now } },
      { returnDocument: 'after' },
    );
  }

  findById(id: ObjectId): Promise<SosDoc | null> {
    return this.db.sos.findOne({ _id: id });
  }

  /** Remembers the sos.raised event so SosRepushWorker can push it again (optional fields). */
  async setRaised(id: ObjectId, eventId: ObjectId, seq: number, now: Date): Promise<void> {
    await this.db.sos.updateOne(
      { _id: id },
      {
        $set: { raisedEventId: eventId, raisedSeq: seq, pushCount: 0, lastPushAt: now },
        $unset: { raiseLeaseUntil: '' },
      },
    );
  }

  /**
   * Claims the right to emit `sos.raised` for a stored SOS that has none yet, when no other request
   * holds the claim (or its lease ran out: that request died). `{ field: null }` matches missing too.
   */
  claimRaise(id: ObjectId, now: Date, until: Date): Promise<SosDoc | null> {
    return this.db.sos.findOneAndUpdate(
      {
        _id: id,
        raisedSeq: { $exists: false },
        $or: [{ raiseLeaseUntil: null }, { raiseLeaseUntil: { $lte: now } }],
      },
      { $set: { raiseLeaseUntil: until } },
      { returnDocument: 'after' },
    );
  }

  /** The emit failed: free the claim so the client's retry can raise it at once. */
  async releaseRaise(id: ObjectId): Promise<void> {
    await this.db.sos.updateOne(
      { _id: id, raisedSeq: { $exists: false } },
      { $unset: { raiseLeaseUntil: '' } },
    );
  }

  /** `{ deliveredAt: null }` also matches documents written before the field existed. */
  markDelivered(id: ObjectId, now: Date, delivery: SosDeliveryInfo): Promise<SosDoc | null> {
    return this.db.sos.findOneAndUpdate(
      { _id: id, deliveredAt: null },
      { $set: { deliveredAt: now, delivery } },
      { returnDocument: 'after' },
    );
  }

  resolve(id: ObjectId, now: Date): Promise<SosDoc | null> {
    return this.db.sos.findOneAndUpdate(
      { _id: id, resolvedAt: null },
      { $set: { resolvedAt: now } },
      { returnDocument: 'after' },
    );
  }

  list(coupleId: ObjectId, limit: number): Promise<SosDoc[]> {
    return this.db.sos.find({ coupleId }).sort({ createdAt: -1, _id: -1 }).limit(limit).toArray();
  }
}
