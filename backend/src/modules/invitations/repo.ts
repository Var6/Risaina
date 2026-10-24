import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { CoupleDoc, InvitationDoc, UserDoc } from '../../db/types.js';

export class InvitationRepo {
  constructor(private readonly db: Collections) {}

  findUser(id: ObjectId): Promise<UserDoc | null> {
    return this.db.users.findOne({ _id: id, deletedAt: null });
  }

  findOpenForInviter(inviterId: ObjectId, now: Date): Promise<InvitationDoc | null> {
    return this.db.invitations.findOne(
      { inviterId, status: 'open', expiresAt: { $gt: now } },
      { sort: { createdAt: -1 } },
    );
  }

  async revokeOpenForInviter(inviterId: ObjectId, now: Date): Promise<void> {
    await this.db.invitations.updateMany(
      { inviterId, status: 'open' },
      { $set: { status: 'revoked', revokedAt: now } },
    );
  }

  async insert(doc: InvitationDoc): Promise<void> {
    await this.db.invitations.insertOne(doc);
  }

  findByIdForInviter(id: ObjectId, inviterId: ObjectId): Promise<InvitationDoc | null> {
    return this.db.invitations.findOne({ _id: id, inviterId });
  }

  async revoke(id: ObjectId, inviterId: ObjectId, now: Date): Promise<boolean> {
    const res = await this.db.invitations.updateOne(
      { _id: id, inviterId, status: 'open' },
      { $set: { status: 'revoked', revokedAt: now } },
    );
    return res.modifiedCount === 1;
  }

  findByCodeHash(codeHash: string): Promise<InvitationDoc | null> {
    return this.db.invitations.findOne({ codeHash });
  }

  /** Single-use guarantee: only an `open` invitation can transition to `accepted`. */
  async markAccepted(id: ObjectId, acceptedBy: ObjectId, now: Date): Promise<InvitationDoc | null> {
    return this.db.invitations.findOneAndUpdate(
      { _id: id, status: 'open', expiresAt: { $gt: now } },
      { $set: { status: 'accepted', acceptedBy, acceptedAt: now } },
      { returnDocument: 'after' },
    );
  }

  async reopen(id: ObjectId): Promise<void> {
    await this.db.invitations.updateOne(
      { _id: id, status: 'accepted' },
      { $set: { status: 'open', acceptedBy: null, acceptedAt: null } },
    );
  }

  findCouple(id: ObjectId): Promise<CoupleDoc | null> {
    return this.db.couples.findOne({ _id: id });
  }

  findPendingCoupleForUser(userId: ObjectId): Promise<CoupleDoc | null> {
    return this.db.couples.findOne(
      { createdBy: userId, memberIds: userId, status: 'pending' },
      { sort: { createdAt: -1 } },
    );
  }

  async insertCouple(doc: CoupleDoc): Promise<void> {
    await this.db.couples.insertOne(doc);
  }

  /** Exactly-two-members guarantee: pending couple with a single member → active with both. */
  async activateCouple(
    coupleId: ObjectId,
    inviterId: ObjectId,
    acceptorId: ObjectId,
    now: Date,
  ): Promise<CoupleDoc | null> {
    return this.db.couples.findOneAndUpdate(
      { _id: coupleId, status: 'pending', memberIds: { $size: 1 }, 'memberIds.0': inviterId },
      { $set: { status: 'active', activatedAt: now, updatedAt: now }, $push: { memberIds: acceptorId } },
      { returnDocument: 'after' },
    );
  }

  async rollbackActivation(coupleId: ObjectId, acceptorId: ObjectId, now: Date): Promise<void> {
    await this.db.couples.updateOne(
      { _id: coupleId, status: 'active' },
      { $set: { status: 'pending', activatedAt: null, updatedAt: now }, $pull: { memberIds: acceptorId } },
    );
  }

  /** Sets activeCoupleId only if the user is still unpaired. */
  async setActiveCouple(userId: ObjectId, coupleId: ObjectId, now: Date): Promise<boolean> {
    const res = await this.db.users.updateOne(
      { _id: userId, deletedAt: null, activeCoupleId: null },
      { $set: { activeCoupleId: coupleId, updatedAt: now } },
    );
    return res.modifiedCount === 1;
  }

  async clearActiveCouple(userId: ObjectId, coupleId: ObjectId, now: Date): Promise<void> {
    await this.db.users.updateOne(
      { _id: userId, activeCoupleId: coupleId },
      { $set: { activeCoupleId: null, updatedAt: now } },
    );
  }
}
