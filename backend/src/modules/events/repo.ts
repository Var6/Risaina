import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { EventDoc } from '../../db/types.js';

export class EventRepo {
  constructor(private readonly db: Collections) {}

  since(coupleId: ObjectId, since: number, limit: number): Promise<EventDoc[]> {
    return this.db.events
      .find({ coupleId, seq: { $gt: since } })
      .sort({ seq: 1 })
      .limit(limit)
      .toArray();
  }

  async latestSeq(coupleId: ObjectId): Promise<number> {
    const couple = await this.db.couples.findOne({ _id: coupleId }, { projection: { eventSeq: 1 } });
    return couple?.eventSeq ?? 0;
  }
}
