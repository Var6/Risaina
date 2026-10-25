import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc, MediaCategory, MessageDoc, MessageType } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { decodeCursor, pageResult } from '../../lib/pagination.js';
import { isDuplicateKey } from '../auth/service.js';
import { MemoryService } from '../memories/service.js';
import { toMessageDto } from '../shared/dto.js';
import type { MessageDto } from '../shared/schemas.js';
import { MessageRepo } from './repo.js';

export interface CreateMessageInput {
  type: MessageType;
  text?: string;
  mediaId?: string;
  idempotencyKey: string;
  viewOnce?: boolean;
}

const CATEGORY_FOR_TYPE: Partial<Record<MessageType, MediaCategory>> = { photo: 'photo', voice: 'voice' };

export class MessageService {
  private readonly repo: MessageRepo;
  private readonly memories: MemoryService;

  constructor(private readonly ctx: AppContext) {
    this.repo = new MessageRepo(ctx.db);
    this.memories = new MemoryService(ctx);
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

    const viewOnce = input.type === 'photo' && input.viewOnce === true;
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
      if (media.viewOnce) {
        throw errors.validation('This media was already sent as a view-once photo', [
          { path: '/mediaId', message: 'view-once media cannot be reused' },
        ]);
      }
      // A view-once photo must be fresh: if it is already visible elsewhere, view-once means nothing.
      if (viewOnce && (await this.repo.mediaInUse(media._id, couple._id))) {
        throw errors.conflict('This photo is already shared in the chat, a memory or a widget');
      }
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
      ...(viewOnce ? { viewOnce: true, openedAt: null } : {}),
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
    if (viewOnce && mediaId) await this.repo.markMediaViewOnce(mediaId);
    const dto = toMessageDto(doc);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'message.created',
      actorId: sender,
      payload: { message: dto },
    });
    // Every ordinary chat photo also lands in the shared memories (view-once never does).
    await this.memories.createFromMessage(couple, doc);
    return { message: dto, created: true };
  }

  /**
   * Recipient opens a view-once photo: the first call sets openedAt, emits message.opened and returns
   * a short-lived download URL; any later call is 410. The cleanup worker deletes the object once
   * that URL has expired.
   */
  async open(
    couple: CoupleDoc,
    userId: string,
    messageId: ObjectId,
  ): Promise<{ url: string; expiresAt: string; message: MessageDto }> {
    const msg = await this.repo.findInCouple(messageId, couple._id);
    if (!msg) throw errors.notFound('Message');
    if (msg.type !== 'photo' || !msg.viewOnce || !msg.mediaId) {
      throw errors.validation('Only view-once photo messages can be opened');
    }
    const uid = new ObjectId(userId);
    if (!msg.recipientId.equals(uid)) throw errors.forbidden('Only the recipient can open a view-once photo');
    if (msg.deletedAt) throw errors.notFound('Message');
    if (msg.openedAt) throw errors.viewOnceConsumed();
    if (msg.viewOnceExpiredAt) throw errors.viewOnceExpired();
    const media = await this.repo.findMedia(msg.mediaId);
    if (media?.status !== 'ready' || media.objectDeleted) throw errors.viewOnceExpired();

    const now = this.ctx.now();
    const opened = await this.repo.markOpened(msg._id, now);
    if (!opened?.openedAt) throw errors.viewOnceConsumed(); // lost a concurrent open (or just deleted)
    await this.repo.markMediaOpened(media._id, opened.openedAt);
    const signed = await this.ctx.storage.presignDownload(
      media.storageKey,
      this.ctx.env.viewOnce.urlTtlSeconds,
    );
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'message.opened',
      actorId: uid,
      payload: { messageId: opened._id.toHexString(), openedAt: opened.openedAt.toISOString() },
    });
    return { url: signed.url, expiresAt: signed.expiresAt.toISOString(), message: toMessageDto(opened) };
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
    await this.memories.deleteForMessage(couple, msg._id, uid);
    return { message: toMessageDto(updated) };
  }
}
