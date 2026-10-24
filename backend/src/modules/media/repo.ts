import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { MediaDoc } from '../../db/types.js';

export class MediaRepo {
  constructor(private readonly db: Collections) {}

  async insert(doc: MediaDoc): Promise<void> {
    await this.db.media.insertOne(doc);
  }

  findById(id: ObjectId): Promise<MediaDoc | null> {
    return this.db.media.findOne({ _id: id });
  }

  async markReady(id: ObjectId, sizeBytes: number, now: Date): Promise<MediaDoc | null> {
    return this.db.media.findOneAndUpdate(
      { _id: id, status: 'pending' },
      { $set: { status: 'ready', readyAt: now, sizeBytes } },
      { returnDocument: 'after' },
    );
  }

  async markDeleted(id: ObjectId, now: Date, objectDeleted: boolean): Promise<MediaDoc | null> {
    return this.db.media.findOneAndUpdate(
      { _id: id, status: { $ne: 'deleted' } },
      { $set: { status: 'deleted', deletedAt: now, objectDeleted } },
      { returnDocument: 'after' },
    );
  }

  async setObjectDeleted(id: ObjectId): Promise<void> {
    await this.db.media.updateOne({ _id: id }, { $set: { objectDeleted: true } });
  }
}
