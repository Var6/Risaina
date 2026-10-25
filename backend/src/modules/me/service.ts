import { ObjectId } from 'mongodb';
import type { z } from 'zod';
import type { AppContext } from '../../app-context.js';
import type { Preferences, UserDoc } from '../../db/types.js';
import { verifyPassword } from '../../lib/crypto.js';
import { errors } from '../../lib/errors.js';
import { canonicalTimeZone } from '../../lib/time.js';
import { AuthService } from '../auth/service.js';
import { CoupleService } from '../couples/service.js';
import {
  toCoupleDto,
  toListDto,
  toMemoryDto,
  toMessageDto,
  toMilestoneDto,
  toPartnerDto,
  toPingDto,
  toPreferencesDto,
  toUserDto,
} from '../shared/dto.js';
import type { PreferencesUpdate } from '../shared/schemas.js';
import { MeRepo } from './repo.js';
import type { ExportResponse, MePatch, MeResponse } from './schemas.js';

const EXPORT_LIMIT = 1000;

export class MeService {
  private readonly repo: MeRepo;
  private readonly auth: AuthService;
  private readonly couples: CoupleService;

  constructor(private readonly ctx: AppContext) {
    this.repo = new MeRepo(ctx.db);
    this.auth = new AuthService(ctx);
    this.couples = new CoupleService(ctx);
  }

  private async mustUser(userId: string): Promise<UserDoc> {
    const user = await this.repo.findUser(new ObjectId(userId));
    if (!user) throw errors.unauthorized('Account no longer exists');
    return user;
  }

  async get(userId: string): Promise<z.infer<typeof MeResponse>> {
    const user = await this.mustUser(userId);
    let couple = user.activeCoupleId ? await this.repo.findCouple(user.activeCoupleId) : null;
    let partner: UserDoc | null = null;
    if (couple && couple.status === 'active') {
      const partnerId = couple.memberIds.find((m) => !m.equals(user._id));
      partner = partnerId ? await this.repo.findUser(partnerId) : null;
    } else {
      couple = await this.repo.findPendingCouple(user._id);
    }
    return {
      user: toUserDto(user),
      couple: couple ? toCoupleDto(couple) : null,
      partner: partner ? toPartnerDto(partner) : null,
      preferences: toPreferencesDto(user.preferences),
    };
  }

  async update(userId: string, patch: MePatch) {
    const user = await this.mustUser(userId);
    const set: Partial<UserDoc> = { updatedAt: this.ctx.now() };
    if (patch.displayName !== undefined) set.displayName = patch.displayName;
    if (patch.avatarMediaId !== undefined) {
      if (patch.avatarMediaId === null) {
        set.avatarMediaId = null;
      } else {
        const media = await this.repo.findMedia(new ObjectId(patch.avatarMediaId));
        if (!media?.ownerId.equals(user._id)) throw errors.notFound('Avatar media');
        if (media.category !== 'avatar')
          throw errors.validation('avatarMediaId must reference an avatar upload');
        if (media.status !== 'ready') throw errors.mediaNotReady();
        set.avatarMediaId = media._id;
      }
    }
    if (patch.birthday !== undefined) set.birthday = patch.birthday;
    if (patch.timeZone !== undefined) {
      set.timeZone = patch.timeZone === null ? null : canonicalTimeZone(patch.timeZone);
    }
    if (patch.shareLocalTime !== undefined) set.shareLocalTime = patch.shareLocalTime;

    const updated = await this.repo.update(user._id, set);
    if (!updated) throw errors.unauthorized();
    // partner.updated whenever a profile field the partner's view derives from actually changed
    if (updated.activeCoupleId && profileChanged(user, updated)) {
      await this.ctx.events.emit({
        coupleId: updated.activeCoupleId,
        type: 'partner.updated',
        actorId: updated._id,
        payload: { partner: toPartnerDto(updated) },
      });
    }
    return { user: toUserDto(updated) };
  }

  async getPreferences(userId: string) {
    const user = await this.mustUser(userId);
    return { preferences: toPreferencesDto(user.preferences) };
  }

  async updatePreferences(userId: string, patch: z.infer<typeof PreferencesUpdate>) {
    const user = await this.mustUser(userId);
    const merged: Preferences = {
      ...user.preferences,
      ...stripUndefined(patch),
      quietHours: { ...user.preferences.quietHours, ...stripUndefined(patch.quietHours ?? {}) },
      widgetPrivacy: { ...user.preferences.widgetPrivacy, ...stripUndefined(patch.widgetPrivacy ?? {}) },
    };
    const updated = await this.repo.setPreferences(user._id, merged, this.ctx.now());
    if (!updated) throw errors.unauthorized();
    return { preferences: toPreferencesDto(updated.preferences) };
  }

  /**
   * DELETE /me: verifies the password (or, for accounts without one, `confirm: "DELETE"`), revokes
   * sessions + devices, ends the couple, schedules media deletion.
   */
  async deleteAccount(userId: string, proof: { password?: string; confirm?: 'DELETE' }): Promise<void> {
    const user = await this.mustUser(userId);
    if (user.passwordHash) {
      if (proof.password === undefined || !(await verifyPassword(user.passwordHash, proof.password))) {
        throw errors.unauthorized('Incorrect password');
      }
    } else if (proof.confirm !== 'DELETE') {
      throw errors.unauthorized('This account has no password; send confirm: "DELETE"');
    }
    const now = this.ctx.now();

    if (user.activeCoupleId) await this.couples.endCouple(user.activeCoupleId, user._id);
    await this.ctx.db.couples.updateMany(
      { memberIds: user._id, status: 'pending' },
      { $set: { status: 'ended', endedAt: now, endedBy: user._id, updatedAt: now } },
    );
    await this.repo.revokeInvitations(user._id, now);
    await this.repo.scheduleOwnMediaDeletion(user._id, now);
    await this.repo.deleteDeviceTokens(user._id);
    await this.repo.deletePasswordResets(user._id);
    await this.repo.deleteEmailVerifications(user._id);
    await this.repo.anonymise(user._id, now);
    await this.auth.revokeAllForUser(user._id, 'account_deleted');
  }

  async export(userId: string): Promise<z.infer<typeof ExportResponse>> {
    const user = await this.mustUser(userId);
    const couple = user.activeCoupleId ? await this.repo.findCouple(user.activeCoupleId) : null;
    const coupleId = couple?._id;
    const [pings, messages, memories, milestones, lists] = coupleId
      ? await Promise.all([
          this.repo.pings(coupleId, EXPORT_LIMIT),
          this.repo.messages(coupleId, EXPORT_LIMIT),
          this.repo.memories(coupleId, EXPORT_LIMIT),
          this.repo.milestones(coupleId, EXPORT_LIMIT),
          this.repo.lists(coupleId, EXPORT_LIMIT),
        ])
      : [[], [], [], [], []];
    return {
      exportedAt: this.ctx.now().toISOString(),
      user: toUserDto(user),
      couple: couple ? toCoupleDto(couple) : null,
      pings: pings.map(toPingDto),
      messages: messages.map(toMessageDto),
      memories: memories.map(toMemoryDto),
      milestones: milestones.map(toMilestoneDto),
      lists: lists.map(toListDto),
      truncated: [pings, messages, memories, milestones, lists].some((l) => l.length >= EXPORT_LIMIT),
    };
  }
}

function profileChanged(before: UserDoc, after: UserDoc): boolean {
  return (
    before.displayName !== after.displayName ||
    String(before.avatarMediaId ?? null) !== String(after.avatarMediaId ?? null) ||
    (before.birthday ?? null) !== (after.birthday ?? null) ||
    (before.timeZone ?? null) !== (after.timeZone ?? null) ||
    (before.shareLocalTime ?? true) !== (after.shareLocalTime ?? true)
  );
}

function stripUndefined<T extends object>(obj: T): Partial<T> {
  const out: Partial<T> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v !== undefined) (out as Record<string, unknown>)[k] = v;
  }
  return out;
}
