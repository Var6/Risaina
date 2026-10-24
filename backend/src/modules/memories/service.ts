import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc, MemoryDoc } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { decodeCursor, pageResult } from '../../lib/pagination.js';
import { toMemoryDto } from '../shared/dto.js';
import type { MemoryDto } from '../shared/schemas.js';
import { MemoryRepo } from './repo.js';

export class MemoryService {
  private readonly repo: MemoryRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new MemoryRepo(ctx.db);
  }

  async create(
    couple: CoupleDoc,
    userId: string,
    input: { mediaId: string; caption?: string; takenAt?: string },
  ): Promise<{ memory: MemoryDto }> {
    const uid = new ObjectId(userId);
    const media = await this.repo.findMedia(new ObjectId(input.mediaId));
    if (!media?.coupleId.equals(couple._id)) throw errors.notFound('Media');
    if (media.category !== 'photo') throw errors.validation('Memories require a photo upload');
    if (media.status !== 'ready') throw errors.mediaNotReady();

    const now = this.ctx.now();
    const doc: MemoryDoc = {
      _id: newId(),
      coupleId: couple._id,
      createdBy: uid,
      mediaId: media._id,
      caption: input.caption && input.caption.length > 0 ? input.caption : null,
      takenAt: input.takenAt ? new Date(input.takenAt) : null,
      pinned: false,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
    };
    await this.repo.insert(doc);
    const dto = toMemoryDto(doc);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'memory.created',
      actorId: uid,
      payload: { memory: dto },
    });
    return { memory: dto };
  }

  async list(couple: CoupleDoc, limit: number, before?: string, pinned?: boolean) {
    const docs = await this.repo.list(couple._id, limit, before ? decodeCursor(before) : null, pinned);
    return pageResult(docs, limit, toMemoryDto);
  }

  async update(
    couple: CoupleDoc,
    userId: string,
    id: ObjectId,
    patch: { caption?: string | null; pinned?: boolean },
  ): Promise<{ memory: MemoryDto }> {
    const existing = await this.repo.findInCouple(id, couple._id);
    if (!existing) throw errors.notFound('Memory');
    const set: Partial<MemoryDoc> = { updatedAt: this.ctx.now() };
    if (patch.caption !== undefined)
      set.caption = patch.caption && patch.caption.length > 0 ? patch.caption : null;
    if (patch.pinned !== undefined) set.pinned = patch.pinned;
    const updated = (await this.repo.patch(id, set)) ?? existing;
    const dto = toMemoryDto(updated);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'memory.updated',
      actorId: new ObjectId(userId),
      payload: { memory: dto },
    });
    return { memory: dto };
  }

  async delete(couple: CoupleDoc, userId: string, id: ObjectId): Promise<void> {
    const existing = await this.repo.findInCouple(id, couple._id);
    if (!existing) throw errors.notFound('Memory');
    const deleted = await this.repo.softDelete(id, this.ctx.now());
    if (deleted?.deletedAt) {
      await this.ctx.events.emit({
        coupleId: couple._id,
        type: 'memory.deleted',
        actorId: new ObjectId(userId),
        payload: { memoryId: deleted._id.toHexString(), deletedAt: deleted.deletedAt.toISOString() },
      });
    }
  }
}
