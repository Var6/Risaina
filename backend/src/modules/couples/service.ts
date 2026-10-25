import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { ChatWallpaperStyle, CoupleDoc, SharedPreferences } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { toCoupleDto, toPartnerDto } from '../shared/dto.js';
import type { CoupleDto, PartnerDto } from '../shared/schemas.js';
import { CouplePurger } from './purge.js';
import { CoupleRepo } from './repo.js';

export interface CouplePatch {
  relationshipStartDate?: string | null;
  nextMeetingDate?: string | null;
  nextMeetingLabel?: string | null;
  sharedPreferences?: SharedPreferences;
  chatWallpaper?: { style: ChatWallpaperStyle; photoMediaId?: string | null | undefined } | null;
}

export class CoupleService {
  private readonly repo: CoupleRepo;
  private readonly purger: CouplePurger;

  constructor(private readonly ctx: AppContext) {
    this.repo = new CoupleRepo(ctx.db);
    this.purger = new CouplePurger(ctx.db, ctx.storage, ctx.logger, ctx.now);
  }

  async getWithPartner(
    couple: CoupleDoc,
    partnerId: string,
  ): Promise<{ couple: CoupleDto; partner: PartnerDto }> {
    const partner = await this.repo.findUser(new ObjectId(partnerId));
    if (!partner) throw errors.notFound('Partner');
    return { couple: toCoupleDto(couple), partner: toPartnerDto(partner) };
  }

  async update(couple: CoupleDoc, actorId: string, patch: CouplePatch): Promise<{ couple: CoupleDto }> {
    const set: Partial<CoupleDoc> = { updatedAt: this.ctx.now() };
    if (patch.relationshipStartDate !== undefined) set.relationshipStartDate = patch.relationshipStartDate;
    if (patch.nextMeetingDate !== undefined) {
      set.nextMeetingDate = patch.nextMeetingDate === null ? null : new Date(patch.nextMeetingDate);
    }
    if (patch.nextMeetingLabel !== undefined) set.nextMeetingLabel = patch.nextMeetingLabel;
    if (patch.sharedPreferences !== undefined) set.sharedPreferences = patch.sharedPreferences;
    if (patch.chatWallpaper !== undefined) {
      const w = patch.chatWallpaper;
      set.chatWallpaper =
        w === null
          ? null
          : {
              style: w.style,
              photoMediaId: w.photoMediaId
                ? await this.checkWallpaperPhoto(couple, new ObjectId(w.photoMediaId))
                : null,
              setBy: new ObjectId(actorId),
              setAt: set.updatedAt!,
            };
    }

    const updated = await this.repo.patch(couple._id, set);
    if (!updated) throw errors.notPaired();
    const dto = toCoupleDto(updated);
    await this.ctx.events.emit({
      coupleId: updated._id,
      type: 'couple.updated',
      actorId: new ObjectId(actorId),
      payload: { couple: dto },
    });
    return { couple: dto };
  }

  /** Same rules as the widget board photo: ready photo|wallpaper media of this couple, not view-once. */
  private async checkWallpaperPhoto(couple: CoupleDoc, mediaId: ObjectId): Promise<ObjectId> {
    const path = '/chatWallpaper/photoMediaId';
    const bad = (message: string) => errors.validation(message, [{ path, message }]);
    const media = await this.repo.findMedia(mediaId);
    if (!media) throw bad('photoMediaId does not reference an upload');
    if (!media.coupleId.equals(couple._id)) throw errors.forbidden('Media belongs to another couple');
    if (media.category !== 'photo' && media.category !== 'wallpaper') {
      throw bad('photoMediaId must reference a photo or wallpaper upload');
    }
    if (media.viewOnce) throw bad('A view-once photo cannot be used as a wallpaper');
    if (media.status !== 'ready') throw bad('photoMediaId must reference a ready (completed) upload');
    return media._id;
  }

  /**
   * End an active couple: status → ended, both members unpaired, `couple.unlinked` emitted (so the
   * partner is notified live, by replay and by push), sockets revoked, then everything the couple
   * created is purged (couples/purge.ts): couple-scoped collections and events except that final
   * `couple.unlinked` are hard-deleted now; storage objects and their media documents are deleted in
   * the background (failures are retried by the cleanup worker).
   * Idempotent: a couple that is not active is left untouched.
   */
  async endCouple(coupleId: ObjectId, byUserId: ObjectId): Promise<CoupleDoc | null> {
    const now = this.ctx.now();
    const ended = await this.repo.end(coupleId, byUserId, now);
    if (!ended) return null;
    await this.repo.clearActiveCouple(ended.memberIds, ended._id, now);
    await this.ctx.events.emit({
      coupleId: ended._id,
      type: 'couple.unlinked',
      actorId: byUserId,
      payload: { coupleId: ended._id.toHexString(), endedAt: now.toISOString(), by: byUserId.toHexString() },
    });
    this.ctx.hub.revokeCouple(ended._id.toHexString());
    await this.purger.purgeData(ended._id, ended.memberIds);
    this.ctx.background.run('unlink-media-purge', () => this.purger.purgeMedia(ended._id));
    return ended;
  }
}
