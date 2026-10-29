import type { ObjectId, UpdateFilter } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { AlarmDoc, AlarmWake } from '../../db/types.js';
import { MAX_ALARMS } from './schemas.js';

export class AlarmRepo {
  constructor(private readonly db: Collections) {}

  async insert(doc: AlarmDoc): Promise<void> {
    await this.db.alarms.insertOne(doc);
  }

  count(coupleId: ObjectId): Promise<number> {
    return this.db.alarms.countDocuments({ coupleId });
  }

  list(coupleId: ObjectId): Promise<AlarmDoc[]> {
    return this.db.alarms.find({ coupleId }).sort({ createdAt: 1, _id: 1 }).limit(MAX_ALARMS).toArray();
  }

  findInCouple(id: ObjectId, coupleId: ObjectId): Promise<AlarmDoc | null> {
    return this.db.alarms.findOne({ _id: id, coupleId });
  }

  /** Compare-and-set on `version` (+1): null when the alarm changed (or was deleted) since it was read. */
  patchAtVersion(
    id: ObjectId,
    coupleId: ObjectId,
    version: number,
    set: Partial<AlarmDoc>,
  ): Promise<AlarmDoc | null> {
    return this.db.alarms.findOneAndUpdate(
      { _id: id, coupleId, version },
      { $set: set, $inc: { version: 1 } },
      { returnDocument: 'after' },
    );
  }

  /**
   * Sets fields of ONE member's entry (`fields` keys are member field names), with compare-and-set
   * on `version` (+1) and the alarm's updatedAt/updatedBy. Only the named fields are written, so a
   * concurrent silent report (armedFor) is never overwritten with a stale copy.
   */
  patchMemberAtVersion(
    id: ObjectId,
    coupleId: ObjectId,
    version: number,
    userId: ObjectId,
    fields: Record<string, unknown>,
    now: Date,
  ): Promise<AlarmDoc | null> {
    const set: Record<string, unknown> = { updatedAt: now, updatedBy: userId };
    for (const [k, v] of Object.entries(fields)) set[`members.$[me].${k}`] = v;
    return this.db.alarms.findOneAndUpdate(
      { _id: id, coupleId, version },
      { $set: set, $inc: { version: 1 } } as UpdateFilter<AlarmDoc>,
      { arrayFilters: [{ 'me.userId': userId }], returnDocument: 'after' },
    );
  }

  /** A readiness report that changes nothing anyone is told about: no version, no event. */
  async setMemberSilently(
    id: ObjectId,
    coupleId: ObjectId,
    userId: ObjectId,
    fields: Record<string, unknown>,
  ): Promise<void> {
    const set: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(fields)) set[`members.$[me].${k}`] = v;
    await this.db.alarms.updateOne({ _id: id, coupleId }, { $set: set } as UpdateFilter<AlarmDoc>, {
      arrayFilters: [{ 'me.userId': userId }],
    });
  }

  /**
   * Stores a wake report on the caller's entry unless that entry already has one for this or a later
   * occurrence (the report is idempotent and never goes backwards). Null when nothing was written.
   */
  recordWake(id: ObjectId, coupleId: ObjectId, userId: ObjectId, wake: AlarmWake): Promise<AlarmDoc | null> {
    return this.db.alarms.findOneAndUpdate(
      {
        _id: id,
        coupleId,
        members: {
          $elemMatch: {
            userId,
            $or: [{ lastWake: null }, { 'lastWake.occurrence': { $lt: wake.occurrence } }],
          },
        },
      },
      { $set: { 'members.$.lastWake': wake } },
      { returnDocument: 'after' },
    );
  }

  async delete(id: ObjectId, coupleId: ObjectId): Promise<boolean> {
    const res = await this.db.alarms.deleteOne({ _id: id, coupleId });
    return res.deletedCount === 1;
  }
}
