import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import { newId } from '../../lib/ids.js';

export class DeviceRepo {
  constructor(private readonly db: Collections) {}

  async upsert(
    userId: ObjectId,
    input: { deviceId: string; platform: 'android'; token: string; appVersion: string; model: string | null },
    now: Date,
  ): Promise<void> {
    // A token belongs to one (user, device). If it was previously registered elsewhere (device
    // re-login as another account), drop the stale row first so the unique index is honoured.
    await this.db.deviceTokens.deleteMany({
      token: input.token,
      $or: [{ userId: { $ne: userId } }, { deviceId: { $ne: input.deviceId } }],
    });
    await this.db.deviceTokens.updateOne(
      { userId, deviceId: input.deviceId },
      {
        $set: {
          platform: input.platform,
          token: input.token,
          appVersion: input.appVersion,
          model: input.model,
          updatedAt: now,
        },
        $setOnInsert: { _id: newId(), userId, deviceId: input.deviceId, createdAt: now },
      },
      { upsert: true },
    );
  }

  async remove(userId: ObjectId, deviceId: string): Promise<void> {
    await this.db.deviceTokens.deleteOne({ userId, deviceId });
  }
}
