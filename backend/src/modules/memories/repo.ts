import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { MediaDoc, MemoryDoc } from '../../db/types.js';
import { beforeCursorFilter, type Cursor } from '../../lib/pagination.js';

export class MemoryRepo {
  constructor(private readonly db: Collections) {}

  findMedia(id: ObjectId): Promise<MediaDoc | null> {
    return this.db.media.findOne({ _id: id });
  }

  async insert(doc: MemoryDoc): Promise<void> {
    await this.db.memories.insertOne(doc);
  }

  findInCouple(id: ObjectId, coupleId: ObjectId): Promise<MemoryDoc | null> {
    return this.db.memories.findOne({ _id: id, coupleId, deletedAt: null });
  }

  async patch(id: ObjectId, set: Partial<MemoryDoc>): Promise<MemoryDoc | null> {
    return this.db.memories.findOneAndUpdate(
      { _id: id, deletedAt: null },
      { $set: set },
      { returnDocument: 'after' },
    );
  }

  async softDelete(id: ObjectId, now: Date): Promise<MemoryDoc | null> {
    return this.db.memories.findOneAndUpdate(
      { _id: id, deletedAt: null },
      { $set: { deletedAt: now, updatedAt: now } },
      { returnDocument: 'after' },
    );
  }

  /** The live memory auto-created from a chat photo message, if any. */
  findBySourceMessage(messageId: ObjectId, coupleId: ObjectId): Promise<MemoryDoc | null> {
    return this.db.memories.findOne({ sourceMessageId: messageId, coupleId, deletedAt: null });
  }

  list(
    coupleId: ObjectId,
    limit: number,
    before: Cursor | null,
    pinned: boolean | undefined,
  ): Promise<MemoryDoc[]> {
    const filter: Record<string, unknown> = { coupleId, deletedAt: null };
    if (pinned !== undefined) filter.pinned = pinned;
    if (before) Object.assign(filter, beforeCursorFilter(before));
    return this.db.memories
      .find(filter)
      .sort({ createdAt: -1, _id: -1 })
      .limit(limit + 1)
      .toArray();
  }
}
