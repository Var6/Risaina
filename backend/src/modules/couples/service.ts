import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc, SharedPreferences } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { toCoupleDto, toPartnerDto } from '../shared/dto.js';
import type { CoupleDto, PartnerDto } from '../shared/schemas.js';
import { CoupleRepo } from './repo.js';

export interface CouplePatch {
  relationshipStartDate?: string | null;
  nextMeetingDate?: string | null;
  nextMeetingLabel?: string | null;
  sharedPreferences?: SharedPreferences;
}

export class CoupleService {
  private readonly repo: CoupleRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new CoupleRepo(ctx.db);
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

  /**
   * End an active couple: status → ended, both members unpaired, `couple.unlinked` emitted
   * (so the partner is notified), shared media scheduled for deletion, widget boards removed,
   * sockets revoked.
   * Idempotent: a couple that is not active is left untouched.
   */
  async endCouple(coupleId: ObjectId, byUserId: ObjectId): Promise<CoupleDoc | null> {
    const now = this.ctx.now();
    const ended = await this.repo.end(coupleId, byUserId, now);
    if (!ended) return null;
    await this.repo.clearActiveCouple(ended.memberIds, ended._id, now);
    await this.repo.revokeOpenInvitations(ended._id, now);
    await this.ctx.events.emit({
      coupleId: ended._id,
      type: 'couple.unlinked',
      actorId: byUserId,
      payload: { coupleId: ended._id.toHexString(), endedAt: now.toISOString(), by: byUserId.toHexString() },
    });
    await this.repo.scheduleMediaDeletion(ended._id, now);
    await this.repo.deleteWidgetBoards(ended._id);
    this.ctx.hub.revokeCouple(ended._id.toHexString());
    return ended;
  }
}
