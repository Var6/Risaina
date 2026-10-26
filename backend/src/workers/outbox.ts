import type { ObjectId } from 'mongodb';
import type { Logger } from 'pino';
import type { Collections } from '../db/collections.js';
import type { EventDoc, EventType, Preferences } from '../db/types.js';
import { isInQuietHours } from '../lib/quiet-hours.js';
import type { Clock } from '../lib/time.js';
import { type PushPolicy, pushPolicyFor, quietPolicy } from '../services/push/policy.js';
import type { PushService } from '../services/push/types.js';

/** Event types pushed regardless of the recipient's notificationsEnabled / quiet hours. */
export const BYPASS_PREFERENCES: ReadonlySet<EventType> = new Set<EventType>(['sos.raised']);

export interface OutboxOptions {
  pollMs: number;
  leaseMs: number;
  maxAttempts: number;
  backoffBaseMs: number;
}

export type OutboxOutcome =
  | { state: 'sent' }
  | { state: 'skipped'; reason: string }
  | { state: 'failed'; error: string }
  | { state: 'retry'; error: string; nextAttemptInMs: number };

/**
 * Push outbox worker (D-04). Leases `push.state = pending` events whose lease has lapsed,
 * resolves the OTHER member of the couple, picks the push priority for the event type
 * (services/push/policy.ts) and the recipient's notification preferences and quiet hours, sends an
 * FCM data-only message to every registered device token, and records sent / skipped / failed with
 * bounded exponential backoff.
 *
 * `leaseUntil` doubles as "not before": a failed attempt sets it to now + backoff.
 */
export class OutboxWorker {
  private timer: NodeJS.Timeout | null = null;
  private running = false;
  private stopped = true;
  private wakeRequested = false;

  constructor(
    private readonly db: Collections,
    private readonly push: PushService,
    private readonly now: Clock,
    private readonly logger: Logger,
    private readonly opts: OutboxOptions,
  ) {}

  start(): void {
    this.stopped = false;
    this.schedule(0);
  }

  async stop(): Promise<void> {
    this.stopped = true;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    // Wait for an in-flight tick to finish so we never exit mid-send.
    while (this.running) await new Promise((r) => setTimeout(r, 10));
  }

  /** Called by the event publisher after each emit so pushes go out promptly. */
  wake(): void {
    if (this.stopped) return;
    this.wakeRequested = true;
    this.schedule(0);
  }

  private schedule(delayMs: number): void {
    if (this.stopped) return;
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.tick(), delayMs);
  }

  private async tick(): Promise<void> {
    if (this.running || this.stopped) return;
    this.running = true;
    this.wakeRequested = false;
    try {
      await this.runOnce();
    } catch (err) {
      this.logger.error({ err }, 'outbox tick failed');
    } finally {
      this.running = false;
      if (!this.stopped) this.schedule(this.wakeRequested ? 0 : this.opts.pollMs);
    }
  }

  /** Drain everything that is currently leasable. Exposed for tests. Returns processed count. */
  async runOnce(maxEvents = 100): Promise<number> {
    let processed = 0;
    for (; processed < maxEvents; processed++) {
      const event = await this.lease();
      if (!event) break;
      const outcome = await this.process(event);
      await this.record(event, outcome);
    }
    return processed;
  }

  private async lease(): Promise<EventDoc | null> {
    const now = this.now();
    return this.db.events.findOneAndUpdate(
      {
        'push.state': 'pending',
        $or: [{ 'push.leaseUntil': null }, { 'push.leaseUntil': { $lte: now } }],
      },
      { $set: { 'push.leaseUntil': new Date(now.getTime() + this.opts.leaseMs) } },
      { sort: { createdAt: 1 }, returnDocument: 'after' },
    );
  }

  async process(event: EventDoc): Promise<OutboxOutcome> {
    const couple = await this.db.couples.findOne({ _id: event.coupleId }, { projection: { memberIds: 1 } });
    if (!couple) return { state: 'skipped', reason: 'couple_missing' };
    const recipientId = couple.memberIds.find((m) => !m.equals(event.actorId));
    if (!recipientId) return { state: 'skipped', reason: 'no_recipient' };

    if (!this.push.enabled) return { state: 'skipped', reason: 'push_disabled' };

    const basePolicy = pushPolicyFor(event.type, event.payload);
    // Stored for the socket and the next sync; nothing on the recipient's phone needs a wake-up.
    if (basePolicy.priority === 'none') return { state: 'skipped', reason: 'not_pushed' };

    const recipient = await this.db.users.findOne(
      { _id: recipientId },
      { projection: { preferences: 1, deletedAt: 1 } },
    );
    if (!recipient || recipient.deletedAt) return { state: 'skipped', reason: 'recipient_missing' };
    const policy = this.applyPreferences(event, basePolicy, recipient.preferences);
    const priority = policy.priority === 'normal' ? 'normal' : 'high';

    const tokens = await this.tokensFor(recipientId);
    if (tokens.length === 0) return { state: 'skipped', reason: 'no_tokens' };

    let result: Awaited<ReturnType<PushService['send']>>;
    try {
      result = await this.push.send(
        tokens,
        {
          type: event.type,
          eventId: event._id.toHexString(),
          coupleId: event.coupleId.toHexString(),
          seq: String(event.seq),
          // What the server meant: lets the app tell a push FCM downgraded from one sent as normal.
          pr: priority === 'high' ? 'h' : 'n',
        },
        { priority, collapseKey: policy.collapseKey, ttlMs: policy.ttlMs },
      );
    } catch (err) {
      return this.retryOrFail(event, (err as Error).name || 'send_error');
    }

    if (result.unregisteredTokens.length > 0) {
      await this.db.deviceTokens.deleteMany({ token: { $in: result.unregisteredTokens } });
      this.logger.info({ count: result.unregisteredTokens.length }, 'removed unregistered device tokens');
    }
    if (result.acceptedTokens.length > 0) {
      // Delivery health (GET /devices/health): when FCM last accepted a push for each phone.
      // Optional fields, safe under the live validator.
      await this.db.deviceTokens.updateMany(
        { token: { $in: result.acceptedTokens } },
        { $set: { lastPushSentAt: this.now(), lastPushSentPriority: priority } },
      );
    }
    if (result.successCount > 0) return { state: 'sent' };
    if (!result.retryable) return { state: 'skipped', reason: 'tokens_unregistered' };
    return this.retryOrFail(event, result.errorSummary ?? 'unknown');
  }

  /**
   * Notifications off, or quiet hours (without "still push during quiet hours"): the push is sent
   * silently at normal priority instead of being skipped, so the recipient's widgets still update;
   * the app itself stays quiet (quiet channel, or no notification when notifications are off).
   * An SOS bypasses both preferences and stays high.
   */
  private applyPreferences(event: EventDoc, policy: PushPolicy, prefs: Preferences): PushPolicy {
    if (policy.bypassPrefs || BYPASS_PREFERENCES.has(event.type)) return policy;
    const muted = !prefs.notificationsEnabled;
    const quiet = !prefs.allowPushInQuietHours && isInQuietHours(prefs.quietHours, this.now());
    return muted || quiet ? quietPolicy(policy) : policy;
  }

  private retryOrFail(event: EventDoc, error: string): OutboxOutcome {
    const attempts = event.push.attempts + 1;
    if (attempts >= this.opts.maxAttempts) return { state: 'failed', error };
    return { state: 'retry', error, nextAttemptInMs: this.opts.backoffBaseMs * 2 ** (attempts - 1) };
  }

  private async tokensFor(userId: ObjectId): Promise<string[]> {
    const docs = await this.db.deviceTokens.find({ userId }, { projection: { token: 1 } }).toArray();
    return docs.map((d) => d.token);
  }

  private async record(event: EventDoc, outcome: OutboxOutcome): Promise<void> {
    const now = this.now();
    switch (outcome.state) {
      case 'sent':
        await this.db.events.updateOne(
          { _id: event._id },
          {
            $set: {
              'push.state': 'sent',
              'push.sentAt': now,
              'push.leaseUntil': null,
              'push.lastError': null,
            },
            $inc: { 'push.attempts': 1 },
          },
        );
        return;
      case 'skipped':
        await this.db.events.updateOne(
          { _id: event._id },
          { $set: { 'push.state': 'skipped', 'push.skipReason': outcome.reason, 'push.leaseUntil': null } },
        );
        return;
      case 'failed':
        await this.db.events.updateOne(
          { _id: event._id },
          {
            $set: { 'push.state': 'failed', 'push.lastError': outcome.error, 'push.leaseUntil': null },
            $inc: { 'push.attempts': 1 },
          },
        );
        this.logger.warn({ eventId: event._id.toHexString(), type: event.type }, 'push permanently failed');
        return;
      case 'retry':
        await this.db.events.updateOne(
          { _id: event._id },
          {
            $set: {
              'push.lastError': outcome.error,
              'push.leaseUntil': new Date(now.getTime() + outcome.nextAttemptInMs),
            },
            $inc: { 'push.attempts': 1 },
          },
        );
        return;
    }
  }
}
