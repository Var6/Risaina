import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { MediaDoc, WidgetBoardDoc } from '../../db/types.js';
import { newId } from '../../lib/ids.js';

export type BoardFields = Pick<WidgetBoardDoc, 'note' | 'emoji' | 'mood' | 'photoMediaId'>;

const FIELDS = ['note', 'emoji', 'mood', 'photoMediaId'] as const;

export class WidgetBoardRepo {
  constructor(private readonly db: Collections) {}

  findForCouple(coupleId: ObjectId): Promise<WidgetBoardDoc[]> {
    return this.db.widgetBoards.find({ coupleId }).limit(2).toArray();
  }

  findMedia(id: ObjectId): Promise<MediaDoc | null> {
    return this.db.media.findOne({ _id: id });
  }

  /** Upsert the author's board: fields in `set` change, the others keep their value (null on insert). */
  async upsert(
    coupleId: ObjectId,
    authorId: ObjectId,
    set: Partial<BoardFields>,
    now: Date,
  ): Promise<WidgetBoardDoc | null> {
    const setOnInsert: Record<string, unknown> = { _id: newId(), createdAt: now };
    for (const f of FIELDS) if (!(f in set)) setOnInsert[f] = null;
    return this.db.widgetBoards.findOneAndUpdate(
      { coupleId, authorId },
      { $set: { ...set, updatedAt: now }, $setOnInsert: setOnInsert },
      { upsert: true, returnDocument: 'after' },
    );
  }

  async deleteForCouple(coupleId: ObjectId): Promise<void> {
    await this.db.widgetBoards.deleteMany({ coupleId });
  }

  async deleteForAuthor(authorId: ObjectId): Promise<void> {
    await this.db.widgetBoards.deleteMany({ authorId });
  }
}
