import type { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import { toEventDto } from '../shared/dto.js';
import type { EventDto } from '../shared/schemas.js';
import { EventRepo } from './repo.js';

export class EventQueryService {
  private readonly repo: EventRepo;

  constructor(ctx: AppContext) {
    this.repo = new EventRepo(ctx.db);
  }

  async since(
    coupleId: ObjectId,
    since: number,
    limit: number,
  ): Promise<{ items: EventDto[]; latestSeq: number }> {
    const [docs, latestSeq] = await Promise.all([
      this.repo.since(coupleId, since, limit),
      this.repo.latestSeq(coupleId),
    ]);
    return { items: docs.map(toEventDto), latestSeq };
  }

  /** Async iterator used by the WebSocket `sync` replay (batches of `batch`). */
  async *replay(coupleId: ObjectId, since: number, batch = 200): AsyncGenerator<EventDto> {
    let cursor = since;
    for (;;) {
      const docs = await this.repo.since(coupleId, cursor, batch);
      for (const d of docs) {
        yield toEventDto(d);
        cursor = d.seq;
      }
      if (docs.length < batch) return;
    }
  }
}
