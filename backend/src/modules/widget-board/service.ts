import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { isDuplicateKey } from '../auth/service.js';
import { toWidgetBoardDto } from '../shared/dto.js';
import type { WidgetBoardDto } from '../shared/schemas.js';
import { type BoardFields, WidgetBoardRepo } from './repo.js';
import type { PutWidgetBoardInput } from './schemas.js';

/**
 * Each partner curates what the OTHER partner's home-screen widgets show: a short note, an emoji,
 * a mood and a photo. One board per author per couple; `widget.updated` reaches the partner via
 * the WebSocket and the push outbox like every other event.
 */
export class WidgetBoardService {
  private readonly repo: WidgetBoardRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new WidgetBoardRepo(ctx.db);
  }

  async get(
    couple: CoupleDoc,
    userId: string,
  ): Promise<{ mine: WidgetBoardDto | null; forMe: WidgetBoardDto | null }> {
    const uid = new ObjectId(userId);
    const boards = await this.repo.findForCouple(couple._id);
    const mine = boards.find((b) => b.authorId.equals(uid));
    const forMe = boards.find((b) => !b.authorId.equals(uid));
    return { mine: mine ? toWidgetBoardDto(mine) : null, forMe: forMe ? toWidgetBoardDto(forMe) : null };
  }

  async put(
    couple: CoupleDoc,
    userId: string,
    input: PutWidgetBoardInput,
  ): Promise<{ board: WidgetBoardDto }> {
    const uid = new ObjectId(userId);
    const set: Partial<BoardFields> = {};
    if (input.note !== undefined) set.note = input.note;
    if (input.emoji !== undefined) set.emoji = input.emoji;
    if (input.mood !== undefined) set.mood = input.mood;
    if (input.photoMediaId !== undefined) {
      set.photoMediaId =
        input.photoMediaId === null ? null : await this.checkPhoto(couple, new ObjectId(input.photoMediaId));
    }

    const now = this.ctx.now();
    let board = null;
    try {
      board = await this.repo.upsert(couple._id, uid, set, now);
    } catch (err) {
      // two first-time PUTs raced on the unique {coupleId, authorId} index: the retry updates
      if (!isDuplicateKey(err)) throw err;
      board = await this.repo.upsert(couple._id, uid, set, now);
    }
    if (!board) throw errors.internal('Widget board write failed');
    const dto = toWidgetBoardDto(board);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'widget.updated',
      actorId: uid,
      payload: { board: dto },
    });
    return { board: dto };
  }

  private async checkPhoto(couple: CoupleDoc, mediaId: ObjectId): Promise<ObjectId> {
    const bad = (message: string) => errors.validation(message, [{ path: '/photoMediaId', message }]);
    const media = await this.repo.findMedia(mediaId);
    if (!media) throw bad('photoMediaId does not reference an upload');
    if (!media.coupleId.equals(couple._id)) throw errors.forbidden('Media belongs to another couple');
    if (media.category !== 'photo' && media.category !== 'wallpaper') {
      throw bad('photoMediaId must reference a photo or wallpaper upload');
    }
    if (media.viewOnce) throw bad('A view-once photo cannot be shown on a widget');
    if (media.status !== 'ready') throw bad('photoMediaId must reference a ready (completed) upload');
    return media._id;
  }
}
