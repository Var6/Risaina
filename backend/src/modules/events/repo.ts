import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { CoupleDoc, EventDoc, EventType } from '../../db/types.js';

export class EventRepo {
  constructor(private readonly db: Collections) {}

  since(coupleId: ObjectId, since: number, limit: number, onlyType?: EventType): Promise<EventDoc[]> {
    return this.db.events
      .find({ coupleId, seq: { $gt: since }, ...(onlyType ? { type: onlyType } : {}) })
      .sort({ seq: 1 })
      .limit(limit)
      .toArray();
  }

  async latestSeq(coupleId: ObjectId): Promise<number> {
    const couple = await this.db.couples.findOne({ _id: coupleId }, { projection: { eventSeq: 1 } });
    return couple?.eventSeq ?? 0;
  }

  activeCoupleIdOf(userId: ObjectId): Promise<{ activeCoupleId: ObjectId | null } | null> {
    return this.db.users.findOne({ _id: userId, deletedAt: null }, { projection: { activeCoupleId: 1 } });
  }

  findActiveCouple(id: ObjectId, memberId: ObjectId): Promise<CoupleDoc | null> {
    return this.db.couples.findOne({ _id: id, status: 'active', memberIds: memberId });
  }

  /** The (once active) couple this user was unlinked from most recently, if it ended after `since`. */
  findRecentlyEnded(memberId: ObjectId, since: Date): Promise<CoupleDoc | null> {
    return this.db.couples.findOne(
      { memberIds: memberId, status: 'ended', activatedAt: { $ne: null }, endedAt: { $gte: since } },
      { sort: { endedAt: -1 } },
    );
  }
}
