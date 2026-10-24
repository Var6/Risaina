import type { PushData, PushSendResult, PushService } from './types.js';

/** Used when no Firebase service account is configured. The outbox marks events `skipped`. */
export class DisabledPushService implements PushService {
  readonly enabled = false;
  readonly provider = 'disabled' as const;

  constructor(readonly reason: string) {}

  async send(_tokens: string[], _data: PushData): Promise<PushSendResult> {
    throw new Error(`Push is disabled: ${this.reason}`);
  }
}
