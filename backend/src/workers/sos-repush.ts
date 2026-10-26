import type { Logger } from 'pino';
import type { Collections } from '../db/collections.js';
import type { Clock } from '../lib/time.js';
import type { PushService } from '../services/push/types.js';

/** How often the worker looks for undelivered SOS alerts. */
export const SOS_REPUSH_INTERVAL_MS = 30_000;
/** At least this long between two pushes of the same SOS (the outbox push counts as the first). */
export const SOS_REPUSH_MIN_GAP_MS = 60_000;
/** Re-push only while the SOS is this young: after that the sender has been told to call. */
export const SOS_REPUSH_WINDOW_MS = 10 * 60_000;
/** Re-pushes per SOS, on top of the outbox's first push. */
export const SOS_REPUSH_MAX = 6;

/**
 * Pushes `sos.raised` again, about once a minute for ten minutes, until the partner's phone confirms
 * it is ringing (POST /sos/:id/delivered), the partner acknowledges, or the sender says they are
 * safe. "Sent" from FCM only means Google accepted the message; a phone in deep Doze, a deprioritised
 * app or a flaky network can still miss it. The phone de-duplicates by SOS id, so a re-push that
 * crosses a slow receipt never rings twice.
 *
 * Sends through PushService directly (not the outbox): the push priority policy lives in the outbox,
 * and this payload adds `sosId` and `at` so the phone can de-duplicate without a network call.
 */
export class SosRepushWorker {
  private timer: NodeJS.Timeout | null = null;
  private running = false;
  private stopped = true;

  constructor(
    private readonly db: Collections,
    private readonly push: PushService,
    private readonly now: Clock,
    private readonly logger: Logger,
    private readonly intervalMs = SOS_REPUSH_INTERVAL_MS,
  ) {}

  start(): void {
    this.stopped = false;
    this.timer = setInterval(() => void this.tick(), this.intervalMs);
  }

  async stop(): Promise<void> {
    this.stopped = true;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    while (this.running) await new Promise((r) => setTimeout(r, 10));
  }

  private async tick(): Promise<void> {
    if (this.running || this.stopped) return;
    this.running = true;
    try {
      const sent = await this.runOnce();
      if (sent > 0) this.logger.info({ sent }, 'sos re-push');
    } catch (err) {
      this.logger.error({ err }, 'sos re-push tick failed');
    } finally {
      this.running = false;
    }
  }

  /** One pass. Exposed for tests. Returns how many alerts were pushed again. */
  async runOnce(): Promise<number> {
    if (!this.push.enabled) return 0;
    const now = this.now();
    const due = new Date(now.getTime() - SOS_REPUSH_MIN_GAP_MS);
    // `{ field: null }` also matches documents written before the field existed.
    const pending = {
      acknowledgedAt: null,
      deliveredAt: null,
      resolvedAt: null,
      raisedSeq: { $exists: true },
      createdAt: { $gte: new Date(now.getTime() - SOS_REPUSH_WINDOW_MS), $lte: due },
      $and: [
        { $or: [{ lastPushAt: null }, { lastPushAt: { $lte: due } }] },
        { $or: [{ pushCount: { $exists: false } }, { pushCount: { $lt: SOS_REPUSH_MAX } }] },
      ],
    };
    const candidates = await this.db.sos
      .find(pending, { projection: { _id: 1 } })
      .limit(50)
      .toArray();
    let sent = 0;
    for (const c of candidates) {
      // Claim atomically, so two instances (or a tick overlapping a restart) never double-send.
      const sos = await this.db.sos.findOneAndUpdate(
        { _id: c._id, ...pending },
        { $set: { lastPushAt: now }, $inc: { pushCount: 1 } },
        { returnDocument: 'after' },
      );
      if (!sos?.raisedEventId || sos.raisedSeq === undefined) continue;
      const couple = await this.db.couples.countDocuments(
        { _id: sos.coupleId, status: 'active' },
        { limit: 1 },
      );
      if (couple === 0) continue;
      const tokens = (
        await this.db.deviceTokens.find({ userId: sos.recipientId }, { projection: { token: 1 } }).toArray()
      ).map((d) => d.token);
      if (tokens.length === 0) continue;
      try {
        const res = await this.push.send(tokens, {
          type: 'sos.raised',
          eventId: sos.raisedEventId.toHexString(),
          coupleId: sos.coupleId.toHexString(),
          seq: String(sos.raisedSeq),
          sosId: sos._id.toHexString(),
          at: String(sos.createdAt.getTime()),
        });
        if (res.unregisteredTokens.length > 0) {
          await this.db.deviceTokens.deleteMany({ token: { $in: res.unregisteredTokens } });
        }
        if (res.successCount > 0) sent++;
      } catch (err) {
        this.logger.warn({ sosId: sos._id.toHexString(), err: (err as Error).name }, 'sos re-push failed');
      }
    }
    return sent;
  }
}
