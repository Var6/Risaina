import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc, PingDoc, PingType } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { decodeCursor, pageResult } from '../../lib/pagination.js';
import { isDuplicateKey } from '../auth/service.js';
import { toPingDto } from '../shared/dto.js';
import type { PingDto } from '../shared/schemas.js';
import { PingRepo } from './repo.js';

export interface CreatePingInput {
  type: PingType;
  emoji: string;
  message?: string;
  idempotencyKey: string;
}

export class PingService {
  private readonly repo: PingRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new PingRepo(ctx.db);
  }

  /** Returns `{ ping, created }`: created=false means an idempotent replay (HTTP 200 instead of 201). */
  async create(
    couple: CoupleDoc,
    senderId: string,
    partnerId: string,
    input: CreatePingInput,
  ): Promise<{ ping: PingDto; created: boolean }> {
    const sender = new ObjectId(senderId);
    const existing = await this.repo.findByIdempotency(sender, input.idempotencyKey);
    if (existing) return { ping: toPingDto(existing), created: false };

    await this.enforceLimits(couple, sender);

    const now = this.ctx.now();
    const doc: PingDoc = {
      _id: newId(),
      coupleId: couple._id,
      senderId: sender,
      recipientId: new ObjectId(partnerId),
      type: input.type,
      emoji: input.emoji,
      message: input.message && input.message.length > 0 ? input.message : null,
      status: 'accepted',
      createdAt: now,
      deliveredAt: null,
      seenAt: null,
      idempotencyKey: input.idempotencyKey,
    };
    try {
      await this.repo.insert(doc);
    } catch (err) {
      if (isDuplicateKey(err)) {
        const replay = await this.repo.findByIdempotency(sender, input.idempotencyKey);
        if (replay) return { ping: toPingDto(replay), created: false };
      }
      throw err;
    }
    const dto = toPingDto(doc);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'ping.created',
      actorId: sender,
      payload: { ping: dto },
    });
    return { ping: dto, created: true };
  }

  /**
   * Cooldown and hourly cap. The sender's own `pingCooldownSeconds` may only tighten the
   * server defaults (effective cooldown = max(server, preference)); the hourly cap is server-only.
   */
  private async enforceLimits(couple: CoupleDoc, sender: ObjectId): Promise<void> {
    const now = this.ctx.now();
    const prefs = await this.repo.findSenderPrefs(sender);
    const cooldown = Math.max(
      this.ctx.env.pings.cooldownSeconds,
      prefs?.preferences.pingCooldownSeconds ?? 0,
    );
    if (cooldown > 0) {
      const last = await this.repo.lastBySender(sender, couple._id);
      if (last) {
        const elapsed = (now.getTime() - last.createdAt.getTime()) / 1000;
        if (elapsed < cooldown) {
          throw errors.rateLimited(
            cooldown - elapsed,
            `Please wait ${Math.ceil(cooldown - elapsed)} s before sending another ping`,
          );
        }
      }
    }
    const hourAgo = new Date(now.getTime() - 60 * 60 * 1000);
    const count = await this.repo.countBySenderSince(sender, couple._id, hourAgo);
    if (count >= this.ctx.env.pings.perHour) {
      throw errors.rateLimited(15 * 60, 'Hourly ping limit reached');
    }
  }

  async list(couple: CoupleDoc, limit: number, before?: string) {
    const docs = await this.repo.list(couple._id, limit, before ? decodeCursor(before) : null);
    return pageResult(docs, limit, toPingDto);
  }

  private async loadForRecipient(couple: CoupleDoc, userId: string, pingId: ObjectId): Promise<PingDoc> {
    const ping = await this.repo.findInCouple(pingId, couple._id);
    if (!ping) throw errors.notFound('Ping');
    if (!ping.recipientId.equals(new ObjectId(userId))) {
      throw errors.forbidden('Only the recipient can update delivery state');
    }
    return ping;
  }

  async markDelivered(couple: CoupleDoc, userId: string, pingId: ObjectId): Promise<{ ping: PingDto }> {
    const ping = await this.loadForRecipient(couple, userId, pingId);
    if (ping.deliveredAt) return { ping: toPingDto(ping) }; // idempotent
    const updated = (await this.repo.markDelivered(ping._id, this.ctx.now())) ?? ping;
    if (updated.deliveredAt && !ping.deliveredAt) {
      await this.ctx.events.emit({
        coupleId: couple._id,
        type: 'ping.delivered',
        actorId: new ObjectId(userId),
        payload: { pingId: updated._id.toHexString(), deliveredAt: updated.deliveredAt.toISOString() },
      });
    }
    return { ping: toPingDto(updated) };
  }

  async markSeen(couple: CoupleDoc, userId: string, pingId: ObjectId): Promise<{ ping: PingDto }> {
    const ping = await this.loadForRecipient(couple, userId, pingId);
    if (ping.seenAt) return { ping: toPingDto(ping) }; // idempotent
    const updated = (await this.repo.markSeen(ping._id, this.ctx.now())) ?? ping;
    if (updated.seenAt && !ping.seenAt) {
      const actorId = new ObjectId(userId);
      if (!ping.deliveredAt && updated.deliveredAt) {
        await this.ctx.events.emit({
          coupleId: couple._id,
          type: 'ping.delivered',
          actorId,
          payload: { pingId: updated._id.toHexString(), deliveredAt: updated.deliveredAt.toISOString() },
        });
      }
      await this.ctx.events.emit({
        coupleId: couple._id,
        type: 'ping.seen',
        actorId,
        payload: { pingId: updated._id.toHexString(), seenAt: updated.seenAt.toISOString() },
      });
    }
    return { ping: toPingDto(updated) };
  }
}
