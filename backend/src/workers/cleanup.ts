import type { ObjectId } from 'mongodb';
import type { Logger } from 'pino';
import type { Collections } from '../db/collections.js';
import type { Clock } from '../lib/time.js';
import { CouplePurger, UNLINKED_EVENT_RETENTION_MS } from '../modules/couples/purge.js';
import type { StorageService } from '../services/storage/types.js';

export interface CleanupOptions {
  intervalMs: number;
  abandonedUploadHours: number;
  /** Open URL TTL (default 120); opened view-once media is purged once openedAt + TTL + 60 s passed. */
  viewOnceUrlTtlSeconds?: number;
  /** Unopened view-once photos are purged this many days after they were sent (default 7). */
  viewOnceUnopenedDays?: number;
}

/** Grace after the open URL's expiry before its object is deleted (clock skew, slow downloads). */
export const VIEW_ONCE_PURGE_GRACE_SECONDS = 60;

export interface CleanupReport {
  abandonedUploads: number;
  viewOnceOpenedPurged: number;
  viewOnceUnopenedExpired: number;
  objectsDeleted: number;
  /** Documents removed by re-running the purge of recently unlinked couples (race leftovers). */
  unlinkedCoupleDocsPurged: number;
  /** Final `couple.unlinked` events dropped after the retention window. */
  unlinkedEventsPurged: number;
  sessionsPurged: number;
  passwordResetsPurged: number;
}

/**
 * Periodic housekeeping (D-06): deletes abandoned `pending` uploads older than N hours, retires
 * view-once photos (opened ones once their open URL expired, unopened ones after N days), re-runs
 * the (idempotent) purge of couples unlinked within the last 7 days, removes storage objects for
 * media already marked `deleted` (deferred deletions; media of a purged couple loses its document
 * too), drops each unlinked couple's final `couple.unlinked` event after 7 days, and purges
 * long-expired sessions and password-reset tokens.
 */
export class CleanupWorker {
  private timer: NodeJS.Timeout | null = null;
  private running = false;
  private stopped = true;
  private readonly purger: CouplePurger;

  constructor(
    private readonly db: Collections,
    private readonly storage: StorageService,
    private readonly now: Clock,
    private readonly logger: Logger,
    private readonly opts: CleanupOptions,
  ) {
    this.purger = new CouplePurger(db, storage, logger, now);
  }

  start(): void {
    this.stopped = false;
    this.timer = setInterval(() => void this.tick(), this.opts.intervalMs);
    void this.tick();
  }

  async stop(): Promise<void> {
    this.stopped = true;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    while (this.running) await new Promise((r) => setTimeout(r, 10));
  }

  private async tick(): Promise<void> {
    if (this.running || this.stopped) return;
    this.running = true;
    try {
      const report = await this.runOnce();
      if (Object.values(report).some((n) => n > 0)) this.logger.info(report, 'cleanup run');
    } catch (err) {
      this.logger.error({ err }, 'cleanup tick failed');
    } finally {
      this.running = false;
    }
  }

  async runOnce(): Promise<CleanupReport> {
    const now = this.now();
    const report: CleanupReport = {
      abandonedUploads: 0,
      viewOnceOpenedPurged: 0,
      viewOnceUnopenedExpired: 0,
      objectsDeleted: 0,
      unlinkedCoupleDocsPurged: 0,
      unlinkedEventsPurged: 0,
      sessionsPurged: 0,
      passwordResetsPurged: 0,
    };

    // 1. abandoned pending uploads → deleted (object removed if it was partially uploaded)
    const cutoff = new Date(now.getTime() - this.opts.abandonedUploadHours * 60 * 60 * 1000);
    const abandoned = await this.db.media
      .find({ status: 'pending', createdAt: { $lt: cutoff } })
      .limit(200)
      .toArray();
    for (const m of abandoned) {
      await this.db.media.updateOne({ _id: m._id }, { $set: { status: 'deleted', deletedAt: now } });
      report.abandonedUploads++;
    }

    // 1b. opened view-once media whose download URL has expired → deleted (object removed in step 2)
    const openedCutoff = new Date(
      now.getTime() - ((this.opts.viewOnceUrlTtlSeconds ?? 120) + VIEW_ONCE_PURGE_GRACE_SECONDS) * 1000,
    );
    const opened = await this.db.media.updateMany(
      { viewOnce: true, openedAt: { $lt: openedCutoff }, status: { $ne: 'deleted' }, objectDeleted: false },
      { $set: { status: 'deleted', deletedAt: now } },
    );
    report.viewOnceOpenedPurged = opened.modifiedCount;

    // 1c. view-once photos never opened within N days → message flagged expired, media deleted
    const unopenedCutoff = new Date(now.getTime() - (this.opts.viewOnceUnopenedDays ?? 7) * 24 * 3600 * 1000);
    const stale = await this.db.messages
      .find({
        viewOnce: true,
        openedAt: null,
        viewOnceExpiredAt: null,
        deletedAt: null,
        createdAt: { $lt: unopenedCutoff },
      })
      .limit(200)
      .toArray();
    for (const msg of stale) {
      const res = await this.db.messages.updateOne(
        { _id: msg._id, openedAt: null, viewOnceExpiredAt: null },
        { $set: { viewOnceExpiredAt: now } },
      );
      if (res.modifiedCount === 0) continue; // opened meanwhile
      if (msg.mediaId) {
        await this.db.media.updateOne(
          { _id: msg.mediaId, status: { $ne: 'deleted' } },
          { $set: { status: 'deleted', deletedAt: now } },
        );
      }
      report.viewOnceUnopenedExpired++;
    }

    // 1d. couples unlinked within the retention window: re-run the idempotent purge (removes writes
    // that raced the unlink; a purge interrupted by an error finishes here). Media objects are
    // handled by step 2.
    const retentionCutoff = new Date(now.getTime() - UNLINKED_EVENT_RETENTION_MS);
    const unlinked = await this.db.couples
      .find({ status: 'ended', purgedAt: { $gte: retentionCutoff } }, { projection: { memberIds: 1 } })
      .limit(200)
      .toArray();
    for (const c of unlinked) {
      report.unlinkedCoupleDocsPurged += await this.purger.purgeData(c._id, c.memberIds);
      const gone = await this.db.media.deleteMany({ coupleId: c._id, objectDeleted: true });
      report.unlinkedCoupleDocsPurged += gone.deletedCount;
    }

    // 2. deferred storage deletions (media of a purged couple: the document goes too)
    const purged = new Map<string, boolean>();
    const isPurgedCouple = async (coupleId: ObjectId): Promise<boolean> => {
      const key = coupleId.toHexString();
      let v = purged.get(key);
      if (v === undefined) {
        const c = await this.db.couples.findOne(
          { _id: coupleId },
          { projection: { status: 1, purgedAt: 1 } },
        );
        v = c?.status === 'ended' && c.purgedAt instanceof Date;
        purged.set(key, v);
      }
      return v;
    };
    const pendingDeletes = await this.db.media
      .find({ status: 'deleted', objectDeleted: false })
      .limit(200)
      .toArray();
    for (const m of pendingDeletes) {
      try {
        await this.storage.delete(m.storageKey);
        if (await isPurgedCouple(m.coupleId)) await this.db.media.deleteOne({ _id: m._id });
        else await this.db.media.updateOne({ _id: m._id }, { $set: { objectDeleted: true } });
        report.objectsDeleted++;
      } catch (err) {
        this.logger.warn(
          { mediaId: m._id.toHexString(), err: (err as Error).message },
          'deferred object delete failed',
        );
      }
    }

    // 2b. an unlinked couple's final event, once the ex-partner has had 7 days to receive it
    const unlinkedEvents = await this.db.events.deleteMany({
      type: 'couple.unlinked',
      createdAt: { $lt: retentionCutoff },
    });
    report.unlinkedEventsPurged = unlinkedEvents.deletedCount;

    // 3. sessions expired more than 7 days ago, 4. password resets expired more than 1 day ago
    const sessions = await this.db.sessions.deleteMany({
      expiresAt: { $lt: new Date(now.getTime() - 7 * 24 * 3600 * 1000) },
    });
    report.sessionsPurged = sessions.deletedCount;
    const resets = await this.db.passwordResets.deleteMany({
      expiresAt: { $lt: new Date(now.getTime() - 24 * 3600 * 1000) },
    });
    report.passwordResetsPurged = resets.deletedCount;

    return report;
  }
}
