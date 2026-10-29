import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { CalendarEntryDoc, ListDoc, MessageDoc } from '../../db/types.js';
import { MAX_CALENDAR_ENTRIES } from './schemas.js';

export class CalendarRepo {
  constructor(private readonly db: Collections) {}

  async insert(doc: CalendarEntryDoc): Promise<void> {
    await this.db.calendarEntries.insertOne(doc);
  }

  count(coupleId: ObjectId): Promise<number> {
    return this.db.calendarEntries.countDocuments({ coupleId });
  }

  list(coupleId: ObjectId): Promise<CalendarEntryDoc[]> {
    return this.db.calendarEntries
      .find({ coupleId })
      .sort({ date: 1, time: 1, _id: 1 })
      .limit(MAX_CALENDAR_ENTRIES)
      .toArray();
  }

  findInCouple(id: ObjectId, coupleId: ObjectId): Promise<CalendarEntryDoc | null> {
    return this.db.calendarEntries.findOne({ _id: id, coupleId });
  }

  findByClientKey(coupleId: ObjectId, clientKey: string): Promise<CalendarEntryDoc | null> {
    return this.db.calendarEntries.findOne({ coupleId, clientKey });
  }

  /** Compare-and-set on `version`: null when the entry changed (or was deleted) since it was read. */
  patchAtVersion(
    id: ObjectId,
    coupleId: ObjectId,
    version: number,
    set: Partial<CalendarEntryDoc>,
  ): Promise<CalendarEntryDoc | null> {
    return this.db.calendarEntries.findOneAndUpdate(
      { _id: id, coupleId, version },
      { $set: set, $inc: { version: 1 } },
      { returnDocument: 'after' },
    );
  }

  async delete(id: ObjectId, coupleId: ObjectId): Promise<boolean> {
    const res = await this.db.calendarEntries.deleteOne({ _id: id, coupleId });
    return res.deletedCount === 1;
  }

  // --- source checks ---------------------------------------------------------------------------

  findMessage(
    id: ObjectId,
    coupleId: ObjectId,
  ): Promise<Pick<MessageDoc, '_id' | 'deletedAt' | 'viewOnce'> | null> {
    return this.db.messages.findOne({ _id: id, coupleId }, { projection: { deletedAt: 1, viewOnce: 1 } });
  }

  findList(id: ObjectId, coupleId: ObjectId): Promise<Pick<ListDoc, '_id'> | null> {
    return this.db.lists.findOne({ _id: id, coupleId, deletedAt: null }, { projection: { _id: 1 } });
  }
}
