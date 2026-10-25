import type { Logger } from 'pino';
import type { Collections } from '../db/collections.js';
import type { Clock } from '../lib/time.js';
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
  sessionsPurged: number;
  passwordResetsPurged: number;
}

/**
 * Periodic housekeeping (D-06): deletes abandoned `pending` uploads older than N hours, retires
 * view-once photos (opened ones once their open URL expired, unopened ones after N days), removes
 * storage objects for media already marked `deleted` (deferred deletions), and purges long-expired
 * sessions and password-reset tokens.
 */
export class CleanupWorker {
  private timer: NodeJS.Timeout | null = null;
  private running = false;
  private stopped = true;

  constructor(
    private readonly db: Collections,
    private readonly storage: StorageService,
    private readonly now: Clock,
    private readonly logger: Logger,
    private readonly opts: CleanupOptions,
  ) {}

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

    // 2. deferred storage deletions
    const pendingDeletes = await this.db.media
      .find({ status: 'deleted', objectDeleted: false })
      .limit(200)
      .toArray();
    for (const m of pendingDeletes) {
      try {
        await this.storage.delete(m.storageKey);
        await this.db.media.updateOne({ _id: m._id }, { $set: { objectDeleted: true } });
        report.objectsDeleted++;
      } catch (err) {
        this.logger.warn(
          { mediaId: m._id.toHexString(), err: (err as Error).message },
          'deferred object delete failed',
        );
      }
    }

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
