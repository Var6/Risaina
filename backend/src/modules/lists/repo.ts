import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { ListDoc } from '../../db/types.js';

const MAX_LISTS = 200;

export class ListRepo {
  constructor(private readonly db: Collections) {}

  async insert(doc: ListDoc): Promise<void> {
    await this.db.lists.insertOne(doc);
  }

  count(coupleId: ObjectId): Promise<number> {
    return this.db.lists.countDocuments({ coupleId, deletedAt: null });
  }

  list(coupleId: ObjectId): Promise<ListDoc[]> {
    return this.db.lists
      .find({ coupleId, deletedAt: null })
      .sort({ pinned: -1, updatedAt: -1, _id: -1 })
      .limit(MAX_LISTS)
      .toArray();
  }

  findInCouple(id: ObjectId, coupleId: ObjectId): Promise<ListDoc | null> {
    return this.db.lists.findOne({ _id: id, coupleId, deletedAt: null });
  }

  /** Compare-and-set on `version`: null when the list changed (or was deleted) since it was read. */
  async patchAtVersion(
    id: ObjectId,
    coupleId: ObjectId,
    version: number,
    set: Partial<ListDoc>,
  ): Promise<ListDoc | null> {
    return this.db.lists.findOneAndUpdate(
      { _id: id, coupleId, deletedAt: null, version },
      { $set: set, $inc: { version: 1 } },
      { returnDocument: 'after' },
    );
  }

  async softDelete(id: ObjectId, coupleId: ObjectId, by: ObjectId, now: Date): Promise<boolean> {
    const res = await this.db.lists.updateOne(
      { _id: id, coupleId, deletedAt: null },
      { $set: { deletedAt: now, updatedAt: now, updatedBy: by } },
    );
    return res.modifiedCount === 1;
  }

  static readonly max = MAX_LISTS;
}
