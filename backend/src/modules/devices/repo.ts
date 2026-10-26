import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { DeviceTokenDoc } from '../../db/types.js';
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

  // --- delivery health (stream: delivery) -------------------------------------------------------

  async tokensFor(userId: ObjectId, deviceId?: string): Promise<string[]> {
    const filter = deviceId ? { userId, deviceId } : { userId };
    const docs = await this.db.deviceTokens.find(filter, { projection: { token: 1 } }).toArray();
    return docs.map((d) => d.token);
  }

  /** Records that the phone received a push. Only the caller's own row can match. */
  async recordAck(
    userId: ObjectId,
    deviceId: string,
    ack: { priority: 'high' | 'normal'; downgraded: boolean; healthFlags?: string[] },
    now: Date,
  ): Promise<void> {
    const set: Partial<DeviceTokenDoc> = {
      lastPushAckAt: now,
      lastPushPriority: ack.priority,
      lastPushDowngraded: ack.downgraded,
    };
    if (ack.healthFlags) set.healthFlags = ack.healthFlags;
    await this.db.deviceTokens.updateOne({ userId, deviceId }, { $set: set });
  }

  /** Every phone of these users, without tokens (never returned by any endpoint). */
  healthFor(userIds: ObjectId[]): Promise<Omit<DeviceTokenDoc, 'token'>[]> {
    return this.db.deviceTokens
      .find({ userId: { $in: userIds } }, { projection: { token: 0 } })
      .sort({ updatedAt: -1 })
      .toArray();
  }
}
