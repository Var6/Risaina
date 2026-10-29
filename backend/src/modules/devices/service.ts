import { ObjectId } from 'mongodb';
import type { z } from 'zod';
import type { AppContext } from '../../app-context.js';
import type { DeviceTokenDoc } from '../../db/types.js';
import type { PushData } from '../../services/push/types.js';
import { DeviceRepo } from './repo.js';
import {
  type DeviceHealth,
  type DeviceHealthResponse,
  HealthFlag,
  type TestPushResponse,
} from './schemas.js';

/** The test push is worth nothing once it is late, so FCM may drop it after ten minutes. */
const TEST_PUSH_TTL_MS = 10 * 60 * 1000;

export class DeviceService {
  private readonly repo: DeviceRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new DeviceRepo(ctx.db);
  }

  async upsert(
    userId: string,
    input: {
      deviceId: string;
      platform: 'android';
      token: string;
      appVersion: string;
      model?: string;
      sdkInt?: number;
    },
  ): Promise<void> {
    await this.repo.upsert(
      new ObjectId(userId),
      {
        deviceId: input.deviceId,
        platform: input.platform,
        token: input.token,
        appVersion: input.appVersion,
        model: input.model ?? null,
        ...(input.sdkInt !== undefined ? { sdkInt: input.sdkInt } : {}),
      },
      this.ctx.now(),
    );
  }

  async revoke(userId: string, deviceId: string): Promise<void> {
    await this.repo.remove(new ObjectId(userId), deviceId);
  }

  // --- delivery health (stream: delivery) -------------------------------------------------------

  /**
   * Sends a high-priority `test.push` straight to the caller's own phone(s), not through the
   * events collection: it is not couple data, must not reach the partner, and needs no event type.
   * The app answers it with a visible "Test notification" so the person can see that pushes reach
   * the phone with the app closed. With a delay, the send runs on an unref'd timer (it is not worth
   * holding a shutdown for) and touches no database, so a restart in between just drops it.
   */
  async testPush(
    userId: string,
    input: { deviceId?: string; delaySeconds: number },
  ): Promise<z.infer<typeof TestPushResponse>> {
    if (!this.ctx.push.enabled) return { scheduled: false, devices: 0, reason: 'push_disabled' };
    const tokens = await this.repo.tokensFor(new ObjectId(userId), input.deviceId);
    if (tokens.length === 0) return { scheduled: false, devices: 0, reason: 'no_tokens' };
    const data: PushData = {
      type: 'test.push',
      eventId: `test-${new ObjectId().toHexString()}`,
      coupleId: '',
      seq: '0',
      pr: 'h',
    };
    const send = () =>
      this.ctx.background.run('test_push', () =>
        this.ctx.push.send(tokens, data, { priority: 'high', ttlMs: TEST_PUSH_TTL_MS }),
      );
    if (input.delaySeconds > 0) setTimeout(send, input.delaySeconds * 1000).unref();
    else send();
    return { scheduled: true, devices: tokens.length, reason: null };
  }

  /**
   * The phone received a push. It says so on the GET /events request its sync makes anyway (no
   * extra request inside the few seconds Android gives the push handler):
   * `X-Risaina-Device: <deviceId>`, `X-Risaina-Push: <priority>/<originalPriority>` and optionally
   * `X-Risaina-Health: flag,flag`. Malformed headers are ignored; this never fails the request.
   */
  async ackFromHeaders(
    userId: string,
    headers: Record<string, string | string[] | undefined>,
  ): Promise<void> {
    const ack = parsePushAckHeaders(headers);
    if (!ack) return;
    await this.repo.recordAck(
      new ObjectId(userId),
      ack.deviceId,
      {
        priority: ack.priority,
        // FCM may lower a high push to normal on a phone whose high pushes rarely showed a
        // notification; the app sees both values on the received message.
        downgraded: ack.originalPriority === 'high' && ack.priority === 'normal',
        healthFlags: ack.healthFlags,
      },
      this.ctx.now(),
    );
  }

  /**
   * Delivery health of the caller's phones and, when paired, the partner's: when a push was last
   * accepted by FCM and when the phone last said it received one. Lets either partner see that,
   * say, the other phone has not received anything for hours. Tokens are never included.
   */
  async health(userId: string): Promise<z.infer<typeof DeviceHealthResponse>> {
    const me = new ObjectId(userId);
    const partnerId = await this.activePartnerOf(me);
    const docs = await this.repo.healthFor(partnerId ? [me, partnerId] : [me]);
    return {
      mine: docs.filter((d) => d.userId.equals(me)).map((d) => toHealth(d, true)),
      partner: partnerId ? docs.filter((d) => d.userId.equals(partnerId)).map((d) => toHealth(d, false)) : [],
    };
  }

  private async activePartnerOf(me: ObjectId): Promise<ObjectId | null> {
    const user = await this.ctx.db.users.findOne(
      { _id: me, deletedAt: null },
      { projection: { activeCoupleId: 1 } },
    );
    if (!user?.activeCoupleId) return null;
    const couple = await this.ctx.db.couples.findOne(
      { _id: user.activeCoupleId, status: 'active' },
      { projection: { memberIds: 1 } },
    );
    return couple?.memberIds.find((m) => !m.equals(me)) ?? null;
  }
}

function toHealth(d: Omit<DeviceTokenDoc, 'token'>, own: boolean): z.infer<typeof DeviceHealth> {
  return {
    deviceId: own ? d.deviceId : null,
    model: d.model ?? null,
    sdkInt: d.sdkInt ?? null,
    appVersion: d.appVersion,
    updatedAt: d.updatedAt.toISOString(),
    lastPushSentAt: d.lastPushSentAt?.toISOString() ?? null,
    lastPushAckAt: d.lastPushAckAt?.toISOString() ?? null,
    lastPushPriority: d.lastPushPriority ?? null,
    lastPushDowngraded: d.lastPushDowngraded ?? false,
    healthFlags: d.healthFlags ?? [],
  };
}

export interface PushAck {
  deviceId: string;
  priority: 'high' | 'normal';
  originalPriority: 'high' | 'normal';
  healthFlags?: string[];
}

function header(headers: Record<string, string | string[] | undefined>, name: string): string | null {
  const v = headers[name];
  return typeof v === 'string' ? v.trim() : null;
}

/** Parses the push-ack headers of GET /events, or null when absent or malformed. */
export function parsePushAckHeaders(headers: Record<string, string | string[] | undefined>): PushAck | null {
  const deviceId = header(headers, 'x-risaina-device');
  const push = header(headers, 'x-risaina-push');
  if (!deviceId || deviceId.length > 128 || !push) return null;
  const [priority, originalPriority] = push.split('/');
  const isPriority = (p: string | undefined): p is 'high' | 'normal' => p === 'high' || p === 'normal';
  if (!isPriority(priority) || !isPriority(originalPriority)) return null;
  const health = header(headers, 'x-risaina-health');
  let healthFlags: string[] | undefined;
  if (health !== null) {
    const flags = health === '' ? [] : health.split(',').map((f) => f.trim());
    if (flags.length > 16 || !flags.every((f) => HealthFlag.safeParse(f).success)) return null;
    healthFlags = [...new Set(flags)];
  }
  return { deviceId, priority, originalPriority, healthFlags };
}
