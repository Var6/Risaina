import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc, MediaCategory, MessageDoc, MessageType } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { decodeCursor, pageResult } from '../../lib/pagination.js';
import { isDuplicateKey } from '../auth/service.js';
import { toMessageDto } from '../shared/dto.js';
import type { MessageDto } from '../shared/schemas.js';
import { MessageRepo } from './repo.js';

export interface CreateMessageInput {
  type: MessageType;
  text?: string;
  mediaId?: string;
  idempotencyKey: string;
}

const CATEGORY_FOR_TYPE: Partial<Record<MessageType, MediaCategory>> = { photo: 'photo', voice: 'voice' };

export class MessageService {
  private readonly repo: MessageRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new MessageRepo(ctx.db);
  }

  async create(
    couple: CoupleDoc,
    senderId: string,
    partnerId: string,
    input: CreateMessageInput,
  ): Promise<{ message: MessageDto; created: boolean }> {
    const sender = new ObjectId(senderId);
    const existing = await this.repo.findByIdempotency(sender, input.idempotencyKey);
    if (existing) return { message: toMessageDto(existing), created: false };

    let mediaId: ObjectId | null = null;
    const expectedCategory = CATEGORY_FOR_TYPE[input.type];
    if (expectedCategory) {
      const media = await this.repo.findMedia(new ObjectId(input.mediaId!));
      if (!media?.coupleId.equals(couple._id)) throw errors.notFound('Media');
      if (!media.ownerId.equals(sender)) throw errors.forbidden('Media is not owned by the sender');
      if (media.category !== expectedCategory) {
        throw errors.validation(`mediaId must reference a ${expectedCategory} upload`, [
          { path: '/mediaId', message: 'category mismatch' },
        ]);
      }
      if (media.status !== 'ready') throw errors.mediaNotReady('Media upload has not been completed');
      mediaId = media._id;
    }

    const now = this.ctx.now();
    const doc: MessageDoc = {
      _id: newId(),
      coupleId: couple._id,
      senderId: sender,
      recipientId: new ObjectId(partnerId),
      type: input.type,
      text: expectedCategory ? null : (input.text ?? null),
      mediaId,
      createdAt: now,
      editedAt: null,
      deletedAt: null,
      readAt: null,
      idempotencyKey: input.idempotencyKey,
    };
    try {
      await this.repo.insert(doc);
    } catch (err) {
      if (isDuplicateKey(err)) {
        const replay = await this.repo.findByIdempotency(sender, input.idempotencyKey);
        if (replay) return { message: toMessageDto(replay), created: false };
      }
      throw err;
    }
    const dto = toMessageDto(doc);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'message.created',
      actorId: sender,
      payload: { message: dto },
    });
    return { message: dto, created: true };
  }

  async list(couple: CoupleDoc, limit: number, before?: string) {
    const docs = await this.repo.list(couple._id, limit, before ? decodeCursor(before) : null);
    return pageResult(docs, limit, toMessageDto);
  }

  async markRead(couple: CoupleDoc, userId: string, messageId: ObjectId): Promise<{ message: MessageDto }> {
    const msg = await this.repo.findInCouple(messageId, couple._id);
    if (!msg) throw errors.notFound('Message');
    const uid = new ObjectId(userId);
    if (!msg.recipientId.equals(uid)) throw errors.forbidden('Only the recipient can mark a message as read');
    if (msg.readAt) return { message: toMessageDto(msg) };
    const updated = (await this.repo.markRead(msg._id, this.ctx.now())) ?? msg;
    if (updated.readAt && !msg.readAt) {
      await this.ctx.events.emit({
        coupleId: couple._id,
        type: 'message.read',
        actorId: uid,
        payload: { messageId: updated._id.toHexString(), readAt: updated.readAt.toISOString() },
      });
    }
    return { message: toMessageDto(updated) };
  }

  async softDelete(couple: CoupleDoc, userId: string, messageId: ObjectId): Promise<{ message: MessageDto }> {
    const msg = await this.repo.findInCouple(messageId, couple._id);
    if (!msg) throw errors.notFound('Message');
    const uid = new ObjectId(userId);
    if (!msg.senderId.equals(uid)) throw errors.forbidden('Only the sender can delete a message');
    if (msg.deletedAt) return { message: toMessageDto(msg) };
    const now = this.ctx.now();
    const updated = (await this.repo.softDelete(msg._id, now)) ?? msg;
    if (msg.mediaId) {
      const media = await this.repo.markMediaDeleted(msg.mediaId, now);
      if (media) {
        try {
          await this.ctx.storage.delete(media.storageKey);
          await this.repo.markMediaObjectDeleted(media._id);
        } catch (err) {
          // Leave objectDeleted=false; the cleanup worker retries storage deletion.
          this.ctx.logger.warn(
            { mediaId: media._id.toHexString(), err: (err as Error).message },
            'media object delete failed; deferred to cleanup',
          );
        }
      }
    }
    if (updated.deletedAt) {
      await this.ctx.events.emit({
        coupleId: couple._id,
        type: 'message.deleted',
        actorId: uid,
        payload: { messageId: updated._id.toHexString(), deletedAt: updated.deletedAt.toISOString() },
      });
    }
    return { message: toMessageDto(updated) };
  }
}
