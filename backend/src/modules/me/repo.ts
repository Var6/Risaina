import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type {
  CoupleDoc,
  GameDoc,
  ListDoc,
  MediaDoc,
  MemoryDoc,
  MessageDoc,
  MilestoneDoc,
  PingDoc,
  Preferences,
  SosDoc,
  UserDoc,
  WidgetBoardDoc,
} from '../../db/types.js';

export class MeRepo {
  constructor(private readonly db: Collections) {}

  findUser(id: ObjectId): Promise<UserDoc | null> {
    return this.db.users.findOne({ _id: id, deletedAt: null });
  }

  findCouple(id: ObjectId): Promise<CoupleDoc | null> {
    return this.db.couples.findOne({ _id: id });
  }

  findPendingCouple(userId: ObjectId): Promise<CoupleDoc | null> {
    return this.db.couples.findOne({ memberIds: userId, status: 'pending' }, { sort: { createdAt: -1 } });
  }

  findMedia(id: ObjectId): Promise<MediaDoc | null> {
    return this.db.media.findOne({ _id: id });
  }

  async update(id: ObjectId, set: Partial<UserDoc>): Promise<UserDoc | null> {
    return this.db.users.findOneAndUpdate(
      { _id: id, deletedAt: null },
      { $set: set },
      { returnDocument: 'after' },
    );
  }

  async setPreferences(id: ObjectId, preferences: Preferences, now: Date): Promise<UserDoc | null> {
    return this.db.users.findOneAndUpdate(
      { _id: id, deletedAt: null },
      { $set: { preferences, updatedAt: now } },
      { returnDocument: 'after' },
    );
  }

  // --- deletion cascade ---------------------------------------------------------------------

  async deleteDeviceTokens(userId: ObjectId): Promise<void> {
    await this.db.deviceTokens.deleteMany({ userId });
  }

  async deletePasswordResets(userId: ObjectId): Promise<void> {
    await this.db.passwordResets.deleteMany({ userId });
  }

  async deleteEmailVerifications(userId: ObjectId): Promise<void> {
    await this.db.emailVerifications.deleteMany({ userId });
  }

  async revokeInvitations(userId: ObjectId, now: Date): Promise<void> {
    await this.db.invitations.updateMany(
      { inviterId: userId, status: 'open' },
      { $set: { status: 'revoked', revokedAt: now } },
    );
  }

  async scheduleOwnMediaDeletion(userId: ObjectId, now: Date): Promise<void> {
    await this.db.media.updateMany(
      { ownerId: userId, status: { $ne: 'deleted' } },
      { $set: { status: 'deleted', deletedAt: now } },
    );
  }

  async deleteWidgetBoards(userId: ObjectId): Promise<void> {
    await this.db.widgetBoards.deleteMany({ authorId: userId });
  }

  async anonymise(userId: ObjectId, now: Date): Promise<void> {
    await this.db.users.updateOne(
      { _id: userId },
      {
        $set: {
          email: `deleted+${userId.toHexString()}@invalid.risaina.local`,
          displayName: 'Deleted user',
          passwordHash: null,
          emailVerifiedAt: null,
          avatarMediaId: null,
          activeCoupleId: null,
          birthday: null,
          timeZone: null,
          city: null,
          deletedAt: now,
          updatedAt: now,
        },
        // frees the Google identity so the same Google account can sign up again later
        $unset: { googleSub: '' },
      },
    );
  }

  // --- export ------------------------------------------------------------------------------

  pings(coupleId: ObjectId, limit: number): Promise<PingDoc[]> {
    return this.db.pings.find({ coupleId }).sort({ createdAt: -1 }).limit(limit).toArray();
  }

  messages(coupleId: ObjectId, limit: number): Promise<MessageDoc[]> {
    return this.db.messages.find({ coupleId }).sort({ createdAt: -1 }).limit(limit).toArray();
  }

  memories(coupleId: ObjectId, limit: number): Promise<MemoryDoc[]> {
    return this.db.memories
      .find({ coupleId, deletedAt: null })
      .sort({ createdAt: -1 })
      .limit(limit)
      .toArray();
  }

  lists(coupleId: ObjectId, limit: number): Promise<ListDoc[]> {
    return this.db.lists
      .find({ coupleId, deletedAt: null })
      .sort({ pinned: -1, updatedAt: -1 })
      .limit(limit)
      .toArray();
  }

  sos(coupleId: ObjectId, limit: number): Promise<SosDoc[]> {
    return this.db.sos.find({ coupleId }).sort({ createdAt: -1 }).limit(limit).toArray();
  }

  games(coupleId: ObjectId, limit: number): Promise<GameDoc[]> {
    return this.db.games.find({ coupleId }).sort({ createdAt: -1 }).limit(limit).toArray();
  }

  widgetBoards(coupleId: ObjectId): Promise<WidgetBoardDoc[]> {
    return this.db.widgetBoards.find({ coupleId }).limit(2).toArray();
  }

  milestones(coupleId: ObjectId, limit: number): Promise<MilestoneDoc[]> {
    return this.db.milestones.find({ coupleId }).sort({ date: 1 }).limit(limit).toArray();
  }
}
