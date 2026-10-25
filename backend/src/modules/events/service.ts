import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import { errors } from '../../lib/errors.js';
import { UNLINKED_EVENT_RETENTION_MS } from '../couples/purge.js';
import { toEventDto } from '../shared/dto.js';
import type { EventDto } from '../shared/schemas.js';
import { EventRepo } from './repo.js';

/**
 * Which event feed a user reads. Normally the active couple's. A user whose couple was unlinked
 * (and who has not paired again) keeps reading that ended couple for UNLINKED_EVENT_RETENTION_DAYS,
 * restricted to its `couple.unlinked` event — the only event the unlink purge keeps — so an app
 * that was offline still learns about the unlink through `GET /events` or the WebSocket sync.
 */
export interface EventFeed {
  coupleId: ObjectId;
  unlinkedOnly: boolean;
}

export class EventQueryService {
  private readonly repo: EventRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new EventRepo(ctx.db);
  }

  /** The active couple's feed, else the recently unlinked couple's feed, else null. */
  async feedFor(userId: string): Promise<EventFeed | null> {
    const uid = new ObjectId(userId);
    const user = await this.repo.activeCoupleIdOf(uid);
    if (!user) throw errors.unauthorized('Account no longer exists');
    if (user.activeCoupleId) {
      const active = await this.repo.findActiveCouple(user.activeCoupleId, uid);
      if (active) return { coupleId: active._id, unlinkedOnly: false };
    }
    return this.unlinkedFeedFor(uid);
  }

  /** Feed of the couple this user was unlinked from within the retention window (or null). */
  async unlinkedFeedFor(userId: ObjectId): Promise<EventFeed | null> {
    const since = new Date(this.ctx.now().getTime() - UNLINKED_EVENT_RETENTION_MS);
    const ended = await this.repo.findRecentlyEnded(userId, since);
    return ended ? { coupleId: ended._id, unlinkedOnly: true } : null;
  }

  async since(
    feed: EventFeed,
    since: number,
    limit: number,
  ): Promise<{ items: EventDto[]; latestSeq: number }> {
    const onlyType = feed.unlinkedOnly ? ('couple.unlinked' as const) : undefined;
    const [docs, latestSeq] = await Promise.all([
      this.repo.since(feed.coupleId, since, limit, onlyType),
      this.repo.latestSeq(feed.coupleId),
    ]);
    return { items: docs.map(toEventDto), latestSeq };
  }

  /** Async iterator used by the WebSocket `sync` replay (batches of `batch`). */
  async *replay(feed: EventFeed, since: number, batch = 200): AsyncGenerator<EventDto> {
    const onlyType = feed.unlinkedOnly ? ('couple.unlinked' as const) : undefined;
    let cursor = since;
    for (;;) {
      const docs = await this.repo.since(feed.coupleId, cursor, batch, onlyType);
      for (const d of docs) {
        yield toEventDto(d);
        cursor = d.seq;
      }
      if (docs.length < batch) return;
    }
  }
}
