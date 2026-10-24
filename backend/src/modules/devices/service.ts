import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import { DeviceRepo } from './repo.js';

export class DeviceService {
  private readonly repo: DeviceRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new DeviceRepo(ctx.db);
  }

  async upsert(
    userId: string,
    input: { deviceId: string; platform: 'android'; token: string; appVersion: string; model?: string },
  ): Promise<void> {
    await this.repo.upsert(
      new ObjectId(userId),
      {
        deviceId: input.deviceId,
        platform: input.platform,
        token: input.token,
        appVersion: input.appVersion,
        model: input.model ?? null,
      },
      this.ctx.now(),
    );
  }

  async revoke(userId: string, deviceId: string): Promise<void> {
    await this.repo.remove(new ObjectId(userId), deviceId);
  }
}
