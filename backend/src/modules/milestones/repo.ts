import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { MilestoneDoc } from '../../db/types.js';

const MAX_MILESTONES = 500;

export class MilestoneRepo {
  constructor(private readonly db: Collections) {}

  async insert(doc: MilestoneDoc): Promise<void> {
    await this.db.milestones.insertOne(doc);
  }

  count(coupleId: ObjectId): Promise<number> {
    return this.db.milestones.countDocuments({ coupleId });
  }

  list(coupleId: ObjectId): Promise<MilestoneDoc[]> {
    return this.db.milestones.find({ coupleId }).sort({ date: 1, _id: 1 }).limit(MAX_MILESTONES).toArray();
  }

  findInCouple(id: ObjectId, coupleId: ObjectId): Promise<MilestoneDoc | null> {
    return this.db.milestones.findOne({ _id: id, coupleId });
  }

  async patch(id: ObjectId, set: Partial<MilestoneDoc>): Promise<MilestoneDoc | null> {
    return this.db.milestones.findOneAndUpdate({ _id: id }, { $set: set }, { returnDocument: 'after' });
  }

  async delete(id: ObjectId): Promise<boolean> {
    const res = await this.db.milestones.deleteOne({ _id: id });
    return res.deletedCount === 1;
  }

  static readonly max = MAX_MILESTONES;
}
