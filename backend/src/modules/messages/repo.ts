import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { MediaDoc, MessageDoc } from '../../db/types.js';
import { beforeCursorFilter, type Cursor } from '../../lib/pagination.js';

export class MessageRepo {
  constructor(private readonly db: Collections) {}

  findByIdempotency(senderId: ObjectId, idempotencyKey: string): Promise<MessageDoc | null> {
    return this.db.messages.findOne({ senderId, idempotencyKey });
  }

  findMedia(id: ObjectId): Promise<MediaDoc | null> {
    return this.db.media.findOne({ _id: id });
  }

  async insert(doc: MessageDoc): Promise<void> {
    await this.db.messages.insertOne(doc);
  }

  findInCouple(id: ObjectId, coupleId: ObjectId): Promise<MessageDoc | null> {
    return this.db.messages.findOne({ _id: id, coupleId });
  }

  async markRead(id: ObjectId, now: Date): Promise<MessageDoc | null> {
    return this.db.messages.findOneAndUpdate(
      { _id: id, readAt: null },
      { $set: { readAt: now } },
      { returnDocument: 'after' },
    );
  }

  async softDelete(id: ObjectId, now: Date): Promise<MessageDoc | null> {
    return this.db.messages.findOneAndUpdate(
      { _id: id, deletedAt: null },
      { $set: { deletedAt: now, text: null } },
      { returnDocument: 'after' },
    );
  }

  async markMediaDeleted(id: ObjectId, now: Date): Promise<MediaDoc | null> {
    return this.db.media.findOneAndUpdate(
      { _id: id, status: { $ne: 'deleted' } },
      { $set: { status: 'deleted', deletedAt: now } },
      { returnDocument: 'after' },
    );
  }

  /** Is this media already attached to a live message, memory or widget board of the couple? */
  async mediaInUse(mediaId: ObjectId, coupleId: ObjectId): Promise<boolean> {
    const [msg, mem, board] = await Promise.all([
      this.db.messages.findOne({ mediaId, coupleId, deletedAt: null }, { projection: { _id: 1 } }),
      this.db.memories.findOne({ mediaId, coupleId, deletedAt: null }, { projection: { _id: 1 } }),
      this.db.widgetBoards.findOne({ photoMediaId: mediaId, coupleId }, { projection: { _id: 1 } }),
    ]);
    return Boolean(msg || mem || board);
  }

  async markMediaViewOnce(id: ObjectId): Promise<void> {
    await this.db.media.updateOne({ _id: id }, { $set: { viewOnce: true, openedAt: null } });
  }

  /** First open wins: sets openedAt only while it is still null/absent. */
  async markOpened(id: ObjectId, now: Date): Promise<MessageDoc | null> {
    return this.db.messages.findOneAndUpdate(
      { _id: id, openedAt: null, deletedAt: null },
      { $set: { openedAt: now } },
      { returnDocument: 'after' },
    );
  }

  async markMediaOpened(id: ObjectId, now: Date): Promise<void> {
    await this.db.media.updateOne({ _id: id }, { $set: { openedAt: now } });
  }

  async markMediaObjectDeleted(id: ObjectId): Promise<void> {
    await this.db.media.updateOne({ _id: id }, { $set: { objectDeleted: true } });
  }

  list(coupleId: ObjectId, limit: number, before: Cursor | null): Promise<MessageDoc[]> {
    const filter = before ? { coupleId, ...beforeCursorFilter(before) } : { coupleId };
    return this.db.messages
      .find(filter)
      .sort({ createdAt: -1, _id: -1 })
      .limit(limit + 1)
      .toArray();
  }
}
