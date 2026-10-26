import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc, EventDoc, SosDeliveryInfo, SosDoc } from '../../db/types.js';
import { AppError, errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { LocationService } from '../location/service.js';
import { toSosDto } from '../shared/dto.js';
import type { SosDto } from '../shared/schemas.js';
import { SosRepo } from './repo.js';
import type { SosDeliveredInput } from './schemas.js';

export const SOS_MAX_PER_WINDOW = 3;
export const SOS_WINDOW_MS = 10 * 60_000;
/**
 * How long one request may take to emit `sos.raised` for a stored SOS before a retry may do it
 * instead (the first request crashed or was killed by a redeploy between the insert and the event).
 */
export const SOS_RAISE_LEASE_MS = 20_000;

const isDuplicateKey = (err: unknown) =>
  typeof err === 'object' && err !== null && (err as { code?: number }).code === 11000;

/**
 * SOS alerts. `sos.raised` is pushed to the partner even when they disabled notifications or are in
 * quiet hours (see OutboxWorker), and pushed again by SosRepushWorker until the partner's phone
 * confirms it rang. The message is private content and is never logged.
 */
export class SosService {
  private readonly repo: SosRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new SosRepo(ctx.db);
  }

  async create(
    couple: CoupleDoc,
    senderId: string,
    partnerId: string,
    input: { message?: string; panic?: boolean; idempotencyKey?: string },
  ): Promise<{ sos: SosDto }> {
    const sender = new ObjectId(senderId);
    const now = this.ctx.now();
    // A retry of an SOS that already went through (lost response, offline retry) returns the first
    // one: no duplicate alarm, and it does not use up the rate limit. If the first request stored it
    // but never emitted its event, the retry emits it now (see ensureRaised).
    if (input.idempotencyKey) {
      const first = await this.repo.findByIdempotency(sender, input.idempotencyKey);
      if (first) {
        await this.ensureRaised(couple, first);
        return { sos: toSosDto(first) };
      }
    }
    // Durable (DB-backed) limit: survives restarts and follows the injected clock.
    const recent = await this.repo.recentBySender(sender, new Date(now.getTime() - SOS_WINDOW_MS));
    if (recent.length >= SOS_MAX_PER_WINDOW) {
      const oldest = recent[recent.length - SOS_MAX_PER_WINDOW]!.createdAt;
      const retryAfter = (oldest.getTime() + SOS_WINDOW_MS - now.getTime()) / 1000;
      throw errors.rateLimited(retryAfter, `At most ${SOS_MAX_PER_WINDOW} SOS alerts per 10 minutes`);
    }
    const doc: SosDoc = {
      _id: newId(),
      coupleId: couple._id,
      senderId: sender,
      recipientId: new ObjectId(partnerId),
      message: input.message && input.message.length > 0 ? input.message : null,
      createdAt: now,
      acknowledgedAt: null,
      // optional fields are written only when set, so ordinary alerts look exactly as before
      ...(input.panic ? { panic: true } : {}),
      ...(input.idempotencyKey ? { idempotencyKey: input.idempotencyKey } : {}),
      // This request emits the event; removed once it did (setRaised)
      raiseLeaseUntil: new Date(now.getTime() + SOS_RAISE_LEASE_MS),
    };
    try {
      await this.repo.insert(doc);
    } catch (err) {
      if (!(isDuplicateKey(err) && input.idempotencyKey)) throw err;
      const winner = await this.repo.findByIdempotency(sender, input.idempotencyKey);
      if (!winner) throw err;
      await this.ensureRaised(couple, winner);
      return { sos: toSosDto(winner) };
    }
    await this.raise(couple, doc, now);
    return { sos: toSosDto(doc) };
  }

  /** Emits `sos.raised` (what pushes and rings the partner's phone) for a stored SOS. */
  private async raise(couple: CoupleDoc, doc: SosDoc, now: Date): Promise<void> {
    let event: EventDoc;
    try {
      event = await this.ctx.events.emit({
        coupleId: couple._id,
        type: 'sos.raised',
        actorId: doc.senderId,
        payload: { sos: toSosDto(doc) },
      });
    } catch (err) {
      // Free the claim, so the client's retry with the same idempotencyKey can raise it at once.
      await this.repo.releaseRaise(doc._id).catch(() => undefined);
      throw err;
    }
    // The event id and seq exist only after emit, so they are stored in a second write.
    await this.repo.setRaised(doc._id, event._id, event.seq, now);
  }

  /**
   * A retry found its SOS stored. If the first request died between the insert and the event (the
   * emit failed, a restart, a redeploy), nothing would ever push it, and the sender would read
   * "sent" while the partner's phone never rings: raise it now. Only one request emits (an atomic
   * claim with a lease); one that finds another request emitting right now gets a retryable 503
   * rather than a "sent" for an alarm that may not go out.
   */
  private async ensureRaised(couple: CoupleDoc, doc: SosDoc): Promise<void> {
    if (doc.raisedSeq !== undefined) return;
    const now = this.ctx.now();
    const claimed = await this.repo.claimRaise(doc._id, now, new Date(now.getTime() + SOS_RAISE_LEASE_MS));
    if (claimed) return this.raise(couple, claimed, now);
    if ((await this.repo.findById(doc._id))?.raisedSeq !== undefined) return;
    throw new AppError(503, 'internal', 'This SOS is still being sent. Try again in a moment.', {
      headers: { 'retry-after': '5' },
    });
  }

  async list(couple: CoupleDoc, limit: number): Promise<{ items: SosDto[] }> {
    return { items: (await this.repo.list(couple._id, limit)).map(toSosDto) };
  }

  async acknowledge(couple: CoupleDoc, userId: string, id: ObjectId): Promise<{ sos: SosDto }> {
    const sos = await this.repo.findInCouple(id, couple._id);
    if (!sos) throw errors.notFound('SOS');
    const uid = new ObjectId(userId);
    if (!sos.recipientId.equals(uid)) throw errors.forbidden('Only the recipient can acknowledge an SOS');
    if (sos.acknowledgedAt) return { sos: toSosDto(sos) };
    const updated = await this.repo.acknowledge(sos._id, this.ctx.now());
    if (!updated?.acknowledgedAt) {
      // a concurrent ack won: answer with the stored state, emit nothing
      return { sos: toSosDto((await this.repo.findInCouple(id, couple._id)) ?? sos) };
    }
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'sos.acknowledged',
      actorId: uid,
      payload: { sosId: updated._id.toHexString(), acknowledgedAt: updated.acknowledgedAt.toISOString() },
    });
    return { sos: toSosDto(updated) };
  }

  /**
   * The recipient's phone started ringing. "Sent" only ever meant FCM accepted the push; this is
   * the receipt that tells the sender it actually reached the phone, and it stops the re-pushes.
   */
  async delivered(
    couple: CoupleDoc,
    userId: string,
    id: ObjectId,
    info: SosDeliveredInput | undefined,
  ): Promise<{ sos: SosDto }> {
    const sos = await this.repo.findInCouple(id, couple._id);
    if (!sos) throw errors.notFound('SOS');
    const uid = new ObjectId(userId);
    if (!sos.recipientId.equals(uid)) throw errors.forbidden('Only the recipient can confirm delivery');
    if (sos.deliveredAt) return { sos: toSosDto(sos) };
    const delivery: SosDeliveryInfo = {
      priority: info?.priority ?? null,
      originalPriority: info?.originalPriority ?? null,
      latencyMs: info?.latencyMs ?? null,
      alarmMuted: info?.alarmMuted ?? null,
      alarmVolume: info?.alarmVolume ?? null,
      ringerMode: info?.ringerMode ?? null,
      interruptionFilter: info?.interruptionFilter ?? null,
      notificationsEnabled: info?.notificationsEnabled ?? null,
      canUseFullScreenIntent: info?.canUseFullScreenIntent ?? null,
      fgsStarted: info?.fgsStarted ?? null,
    };
    const updated = await this.repo.markDelivered(sos._id, this.ctx.now(), delivery);
    if (!updated?.deliveredAt) {
      return { sos: toSosDto((await this.repo.findInCouple(id, couple._id)) ?? sos) };
    }
    // Device state only (no message, no ids of people): explains a silent alarm after the fact.
    this.ctx.logger.info({ sosId: updated._id.toHexString(), delivery }, 'sos delivered');
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'sos.delivered',
      actorId: uid,
      payload: { sosId: updated._id.toHexString(), deliveredAt: updated.deliveredAt.toISOString() },
    });
    return { sos: toSosDto(updated) };
  }

  /**
   * "I'm safe now" from the sender: stops the partner's alarm (sos.resolved) and ends the live
   * location shared for that SOS. Idempotent; the event is emitted once.
   */
  async resolve(couple: CoupleDoc, userId: string, id: ObjectId): Promise<{ sos: SosDto }> {
    const sos = await this.repo.findInCouple(id, couple._id);
    if (!sos) throw errors.notFound('SOS');
    if (!sos.senderId.equals(new ObjectId(userId))) {
      throw errors.forbidden('Only the sender can say they are safe');
    }
    let current = sos;
    if (!sos.resolvedAt) {
      const updated = await this.repo.resolve(sos._id, this.ctx.now());
      if (updated?.resolvedAt) {
        current = updated;
        await this.ctx.events.emit({
          coupleId: couple._id,
          type: 'sos.resolved',
          actorId: sos.senderId,
          payload: { sosId: updated._id.toHexString(), resolvedAt: updated.resolvedAt.toISOString() },
        });
      } else {
        current = (await this.repo.findInCouple(id, couple._id)) ?? sos;
      }
    }
    await new LocationService(this.ctx).stopForSos(couple, userId, sos._id);
    return { sos: toSosDto(current) };
  }

  /**
   * "Test through the server": a high-priority `sos.test` push to the caller's OWN phones after a
   * delay, so they can lock the phone or close the app first. No database write, no event.
   */
  async test(userId: string, delaySeconds: number): Promise<{ queued: boolean; devices: number }> {
    const tokens = (
      await this.ctx.db.deviceTokens
        .find({ userId: new ObjectId(userId) }, { projection: { token: 1 } })
        .toArray()
    ).map((d) => d.token);
    if (!this.ctx.push.enabled || tokens.length === 0) return { queued: false, devices: tokens.length };
    const send = async () => {
      try {
        await this.ctx.push.send(tokens, { type: 'sos.test', eventId: 'test', coupleId: '', seq: '0' });
      } catch (err) {
        this.ctx.logger.warn({ err: (err as Error).name }, 'sos test push failed');
      }
    };
    if (delaySeconds === 0) await send();
    // unref: a pending test must never hold the process open during a shutdown
    else setTimeout(() => void send(), delaySeconds * 1000).unref();
    return { queued: true, devices: tokens.length };
  }
}
