import type { ObjectId } from 'mongodb';
import type { Logger } from 'pino';
import type { Collections } from '../../db/collections.js';
import type { Clock } from '../../lib/time.js';
import type { StorageService } from '../../services/storage/types.js';

/**
 * How long the final `couple.unlinked` event of an ended couple is kept, so the ex-partner's app
 * still gets it (WebSocket, `GET /events`/sync replay, push). The cleanup worker deletes it after.
 */
export const UNLINKED_EVENT_RETENTION_DAYS = 7;
export const UNLINKED_EVENT_RETENTION_MS = UNLINKED_EVENT_RETENTION_DAYS * 24 * 60 * 60 * 1000;

/** Parallel storage deletes per batch (background work; keeps R2 request bursts small). */
const STORAGE_DELETE_CONCURRENCY = 8;

export interface MediaPurgeReport {
  objectsDeleted: number;
  objectDeleteFailures: number;
  mediaDocsDeleted: number;
}

/**
 * Removes everything a couple created once it has ended (to save database and storage space).
 * Every step is a filter-based delete/update, so the whole purge is idempotent and safe to re-run:
 * `endCouple` runs it at unlink time and the cleanup worker re-runs it while the couple's final
 * event is retained (catching writes that raced the unlink and failed storage deletes).
 *
 * Kept: the couple record (status, memberIds, endedAt, endedBy plus the fields the validator
 * requires) and its single `couple.unlinked` event.
 */
export class CouplePurger {
  constructor(
    private readonly db: Collections,
    private readonly storage: StorageService,
    private readonly logger: Logger,
    private readonly now: Clock,
  ) {}

  /**
   * Database phase (fast; awaited by the unlink request). Returns the number of documents deleted.
   * Media documents are only marked `deleted` here; `purgeMedia` removes each one once its storage
   * object is gone.
   */
  async purgeData(coupleId: ObjectId, memberIds: ObjectId[]): Promise<number> {
    const now = this.now();

    // Media becomes unusable at once (GET /media/:id/url etc. require status ready).
    await this.db.media.updateMany(
      { coupleId, status: { $ne: 'deleted' } },
      { $set: { status: 'deleted', deletedAt: now } },
    );

    // Avatars are uploaded under the couple's key prefix, so they go with it: both members' avatars
    // are cleared, except one uploaded in a NEWER, still active couple (a re-run after re-pairing).
    const withAvatar = await this.db.users
      .find({ _id: { $in: memberIds }, avatarMediaId: { $ne: null } }, { projection: { avatarMediaId: 1 } })
      .toArray();
    for (const u of withAvatar) {
      const avatarId = u.avatarMediaId;
      if (!avatarId) continue;
      const media = await this.db.media.findOne({ _id: avatarId }, { projection: { coupleId: 1 } });
      if (media && !media.coupleId.equals(coupleId)) {
        const other = await this.db.couples.countDocuments(
          { _id: media.coupleId, status: 'active' },
          { limit: 1 },
        );
        if (other > 0) continue;
      }
      await this.db.users.updateOne(
        { _id: u._id, avatarMediaId: avatarId },
        { $set: { avatarMediaId: null, updatedAt: now } },
      );
    }

    const byCouple = { coupleId };
    const results = await Promise.all([
      this.db.messages.deleteMany(byCouple),
      this.db.pings.deleteMany(byCouple),
      this.db.memories.deleteMany(byCouple),
      this.db.milestones.deleteMany(byCouple),
      this.db.lists.deleteMany(byCouple),
      this.db.games.deleteMany(byCouple),
      this.db.widgetBoards.deleteMany(byCouple),
      this.db.sos.deleteMany(byCouple),
      this.db.liveLocations.deleteMany(byCouple),
      this.db.calendarEntries.deleteMany(byCouple),
      this.db.invitations.deleteMany(byCouple),
      // keep the final couple.unlinked event for the ex-partner (deleted by the cleanup worker later)
      this.db.events.deleteMany({ coupleId, type: { $ne: 'couple.unlinked' } }),
    ]);

    // Reset the relationship data; the minimal ended record stays.
    await this.db.couples.updateOne(
      { _id: coupleId, status: 'ended' },
      {
        $set: {
          relationshipStartDate: null,
          nextMeetingDate: null,
          nextMeetingLabel: null,
          sharedPreferences: {},
        },
        $unset: { chatWallpaper: '' },
      },
    );

    return results.reduce((n, r) => n + r.deletedCount, 0);
  }

  /**
   * Storage phase (slow; run in the background after unlink and re-run by the cleanup worker).
   * Deletes each storage object, then its media document. A failed delete leaves the document
   * (`status: deleted`, `objectDeleted: false`) for the cleanup worker to retry. Documents whose
   * object is already gone (`objectDeleted: true`) are deleted outright.
   */
  async purgeMedia(coupleId: ObjectId): Promise<MediaPurgeReport> {
    const report: MediaPurgeReport = { objectsDeleted: 0, objectDeleteFailures: 0, mediaDocsDeleted: 0 };
    const docs = await this.db.media
      .find({ coupleId, objectDeleted: false }, { projection: { _id: 1, storageKey: 1 } })
      .toArray();
    for (let i = 0; i < docs.length; i += STORAGE_DELETE_CONCURRENCY) {
      const batch = docs.slice(i, i + STORAGE_DELETE_CONCURRENCY);
      await Promise.all(
        batch.map(async (m) => {
          try {
            await this.storage.delete(m.storageKey);
          } catch (err) {
            report.objectDeleteFailures++;
            this.logger.warn(
              { mediaId: m._id.toHexString(), err: (err as Error).message },
              'unlink purge: storage delete failed; the cleanup worker will retry',
            );
            return;
          }
          report.objectsDeleted++;
          const res = await this.db.media.deleteOne({ _id: m._id });
          report.mediaDocsDeleted += res.deletedCount;
        }),
      );
    }
    const gone = await this.db.media.deleteMany({ coupleId, objectDeleted: true });
    report.mediaDocsDeleted += gone.deletedCount;
    return report;
  }
}
