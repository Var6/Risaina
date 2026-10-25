import { MongoServerError, type ObjectId } from 'mongodb';
import type { Logger } from 'pino';
import type { Collections } from '../../db/collections.js';
import type { EventDoc, EventType } from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import type { Clock } from '../../lib/time.js';
import { toEventDto } from '../../modules/shared/dto.js';
import type { WsHub } from './hub.js';

export interface EmitInput {
  coupleId: ObjectId;
  type: EventType;
  actorId: ObjectId;
  payload: Record<string, unknown>;
}

/**
 * Durable event log + fan-out (D-04). `emit()` allocates the per-couple `seq` by atomically
 * incrementing `couples.eventSeq`, inserts the event (push outbox state `pending`), publishes it
 * to connected sockets and nudges the outbox worker.
 */
export class EventPublisher {
  private wakeOutbox: (() => void) | null = null;

  constructor(
    private readonly db: Collections,
    private readonly hub: WsHub,
    private readonly now: Clock,
    private readonly logger?: Logger,
  ) {}

  onEmitted(wake: () => void): void {
    this.wakeOutbox = wake;
  }

  async emit(input: EmitInput): Promise<EventDoc> {
    const couple = await this.db.couples.findOneAndUpdate(
      { _id: input.coupleId },
      { $inc: { eventSeq: 1 } },
      { returnDocument: 'after', projection: { eventSeq: 1, memberIds: 1 } },
    );
    if (!couple) throw errors.notFound('Couple');

    const doc: EventDoc = {
      _id: newId(),
      coupleId: input.coupleId,
      seq: couple.eventSeq,
      type: input.type,
      actorId: input.actorId,
      payload: input.payload,
      createdAt: this.now(),
      push: {
        state: 'pending',
        attempts: 0,
        leaseUntil: null,
        lastError: null,
        skipReason: null,
        sentAt: null,
      },
    };
    try {
      await this.db.events.insertOne(doc);
    } catch (err) {
      if (!(err instanceof MongoServerError && err.code === 121)) throw err;
      // DocumentValidationFailure: the database still has an older `events` validator whose strict
      // `type` enum lacks this type, and the readWrite-only DB user cannot update it (no collMod).
      // The caller's write already succeeded, so failing the request would invite duplicate retries.
      // Degrade loudly instead: deliver live to connected sockets, but the event is NOT durable
      // (no GET /events / sync replay, no push). Fixed by granting dbAdmin and restarting once.
      this.logger?.error(
        { eventType: doc.type, coupleId: doc.coupleId.toHexString(), seq: doc.seq },
        'event rejected by the events collection validator (stale validator; grant dbAdmin and restart): delivered live only',
      );
      this.hub.publish(
        toEventDto(doc),
        couple.memberIds.map((m) => m.toHexString()),
      );
      return doc;
    }

    this.hub.publish(
      toEventDto(doc),
      couple.memberIds.map((m) => m.toHexString()),
    );
    this.wakeOutbox?.();
    return doc;
  }
}
