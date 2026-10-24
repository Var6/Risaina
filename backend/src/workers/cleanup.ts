import type { Logger } from 'pino';
import type { Collections } from '../db/collections.js';
import type { Clock } from '../lib/time.js';
import type { StorageService } from '../services/storage/types.js';

export interface CleanupOptions {
  intervalMs: number;
  abandonedUploadHours: number;
}

export interface CleanupReport {
  abandonedUploads: number;
  objectsDeleted: number;
  sessionsPurged: number;
  passwordResetsPurged: number;
}

/**
 * Periodic housekeeping (D-06): deletes abandoned `pending` uploads older than N hours, removes
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
