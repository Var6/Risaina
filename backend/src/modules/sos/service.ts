import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc, SosDoc } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { toSosDto } from '../shared/dto.js';
import type { SosDto } from '../shared/schemas.js';
import { SosRepo } from './repo.js';

export const SOS_MAX_PER_WINDOW = 3;
export const SOS_WINDOW_MS = 10 * 60_000;

/**
 * SOS alerts. `sos.raised` is pushed to the partner even when they disabled notifications or are in
 * quiet hours (see OutboxWorker). The message is private content and is never logged.
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
    input: { message?: string },
  ): Promise<{ sos: SosDto }> {
    const sender = new ObjectId(senderId);
    const now = this.ctx.now();
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
    };
    await this.repo.insert(doc);
    const dto = toSosDto(doc);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'sos.raised',
      actorId: sender,
      payload: { sos: dto },
    });
    return { sos: dto };
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
}
