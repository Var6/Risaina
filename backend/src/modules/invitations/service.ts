import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type { CoupleDoc, InvitationDoc } from '../../db/types.js';
import { normaliseCrockford, randomCrockfordCode, sha256Hex } from '../../lib/crypto.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { addHours } from '../../lib/time.js';
import { toCoupleDto, toPartnerDto } from '../shared/dto.js';
import type { CoupleDto, PartnerDto } from '../shared/schemas.js';
import { InvitationRepo } from './repo.js';

export class InvitationService {
  private readonly repo: InvitationRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new InvitationRepo(ctx.db);
  }

  async create(userId: string): Promise<{ id: string; code: string; expiresAt: string }> {
    const now = this.ctx.now();
    const uid = new ObjectId(userId);
    const user = await this.repo.findUser(uid);
    if (!user) throw errors.unauthorized();
    if (user.activeCoupleId) throw errors.alreadyPaired();

    let couple = await this.repo.findPendingCoupleForUser(uid);
    if (!couple) {
      couple = {
        _id: newId(),
        status: 'pending',
        memberIds: [uid],
        createdBy: uid,
        eventSeq: 0,
        relationshipStartDate: null,
        nextMeetingDate: null,
        nextMeetingLabel: null,
        sharedPreferences: {},
        createdAt: now,
        updatedAt: now,
        activatedAt: null,
        endedAt: null,
        endedBy: null,
      };
      await this.repo.insertCouple(couple);
    }

    // Only one open invitation per inviter: creating a new one revokes the previous.
    await this.repo.revokeOpenForInviter(uid, now);

    // Retry on the (astronomically unlikely) code-hash collision.
    for (let attempt = 0; attempt < 3; attempt++) {
      const code = randomCrockfordCode(10);
      const doc: InvitationDoc = {
        _id: newId(),
        coupleId: couple._id,
        inviterId: uid,
        codeHash: sha256Hex(code),
        status: 'open',
        expiresAt: addHours(now, this.ctx.env.invitations.ttlHours),
        createdAt: now,
        acceptedBy: null,
        acceptedAt: null,
        revokedAt: null,
      };
      try {
        await this.repo.insert(doc);
        return { id: doc._id.toHexString(), code, expiresAt: doc.expiresAt.toISOString() };
      } catch (err) {
        if ((err as { code?: number }).code !== 11000) throw err;
      }
    }
    throw errors.internal('Could not allocate an invitation code');
  }

  async current(userId: string): Promise<{ id: string; expiresAt: string; createdAt: string } | null> {
    const doc = await this.repo.findOpenForInviter(new ObjectId(userId), this.ctx.now());
    if (!doc) return null;
    return {
      id: doc._id.toHexString(),
      expiresAt: doc.expiresAt.toISOString(),
      createdAt: doc.createdAt.toISOString(),
    };
  }

  async revoke(userId: string, invitationId: ObjectId): Promise<void> {
    const uid = new ObjectId(userId);
    const doc = await this.repo.findByIdForInviter(invitationId, uid);
    if (!doc) throw errors.notFound('Invitation');
    await this.repo.revoke(invitationId, uid, this.ctx.now()); // idempotent on already-revoked/accepted
  }

  async accept(
    userId: string,
    ip: string,
    rawCode: string,
  ): Promise<{ couple: CoupleDto; partner: PartnerDto }> {
    const now = this.ctx.now();
    const uid = new ObjectId(userId);

    const gate = this.ctx.inviteLimiter.attempt(`${userId}:${ip}`);
    if (!gate.allowed) throw errors.rateLimited(gate.retryAfterSeconds, 'Too many invitation attempts');

    const acceptor = await this.repo.findUser(uid);
    if (!acceptor) throw errors.unauthorized();
    if (acceptor.activeCoupleId) throw errors.alreadyPaired();

    const code = normaliseCrockford(rawCode);
    const invitation = await this.repo.findByCodeHash(sha256Hex(code));
    if (!invitation) throw errors.notFound('Invitation');
    if (invitation.inviterId.equals(uid)) throw errors.conflict('You cannot accept your own invitation');
    if (invitation.status === 'accepted') throw errors.invitationUsed();
    if (invitation.status === 'revoked') throw errors.invitationExpired('Invitation has been revoked');
    if (invitation.expiresAt.getTime() <= now.getTime()) throw errors.invitationExpired();

    const inviter = await this.repo.findUser(invitation.inviterId);
    if (!inviter) throw errors.invitationExpired('Inviter account no longer exists');
    if (inviter.activeCoupleId) throw errors.alreadyPaired('The inviter is already paired');

    // 1. consume the invitation (single use, atomic)
    const accepted = await this.repo.markAccepted(invitation._id, uid, now);
    if (!accepted) throw errors.invitationUsed();

    // 2. activate the couple (exactly two members, atomic)
    const couple = await this.repo.activateCouple(invitation.coupleId, invitation.inviterId, uid, now);
    if (!couple) {
      await this.repo.reopen(invitation._id);
      throw errors.invitationUsed('Invitation is no longer valid');
    }

    // 3. bind both users (each only if still unpaired)
    const acceptorBound = await this.repo.setActiveCouple(uid, couple._id, now);
    const inviterBound =
      acceptorBound && (await this.repo.setActiveCouple(invitation.inviterId, couple._id, now));
    if (!acceptorBound || !inviterBound) {
      await this.repo.clearActiveCouple(uid, couple._id, now);
      await this.repo.clearActiveCouple(invitation.inviterId, couple._id, now);
      await this.repo.rollbackActivation(couple._id, uid, now);
      await this.repo.reopen(invitation._id);
      throw errors.alreadyPaired();
    }

    this.ctx.inviteLimiter.reset(`${userId}:${ip}`);

    const dto = toCoupleDto(couple);
    await this.ctx.events.emit({
      coupleId: couple._id,
      type: 'couple.updated',
      actorId: uid,
      payload: { couple: dto },
    });
    return { couple: dto, partner: toPartnerDto(inviter) };
  }

  static isPendingFor(couple: CoupleDoc, userId: ObjectId): boolean {
    return couple.status === 'pending' && couple.memberIds.some((m) => m.equals(userId));
  }
}
