import type { ObjectId } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { GameDoc, GameStatus, RpsMove } from '../../db/types.js';

/** Writes are conditional on the expected status so concurrent requests cannot skip a state. */
export class GameRepo {
  constructor(private readonly db: Collections) {}

  async insert(doc: GameDoc): Promise<void> {
    await this.db.games.insertOne(doc);
  }

  findInCouple(id: ObjectId, coupleId: ObjectId): Promise<GameDoc | null> {
    return this.db.games.findOne({ _id: id, coupleId });
  }

  findOpen(coupleId: ObjectId): Promise<GameDoc | null> {
    return this.db.games.findOne({ coupleId, open: true });
  }

  list(coupleId: ObjectId, limit: number): Promise<GameDoc[]> {
    return this.db.games.find({ coupleId }).sort({ createdAt: -1, _id: -1 }).limit(limit).toArray();
  }

  /** Adds the player's move for the current round unless they already moved (or the round is full). */
  addMove(id: ObjectId, round: number, userId: ObjectId, move: RpsMove, now: Date): Promise<GameDoc | null> {
    return this.db.games.findOneAndUpdate(
      { _id: id, status: 'waiting', round, 'moves.userId': { $ne: userId }, 'moves.1': { $exists: false } },
      { $push: { moves: { userId, move, at: now } }, $set: { updatedAt: now } },
      { returnDocument: 'after' },
    );
  }

  /** Conditional state transition; null when the game is no longer in `from` (lost a race). */
  transition(
    id: ObjectId,
    from: GameStatus | GameStatus[],
    extraFilter: Record<string, unknown>,
    set: Partial<GameDoc>,
    inc?: Partial<Record<'round', number>>,
  ): Promise<GameDoc | null> {
    const status = Array.isArray(from) ? { $in: from } : from;
    const update: Record<string, unknown> = { $set: set };
    if (inc) update.$inc = inc;
    return this.db.games.findOneAndUpdate({ _id: id, status, ...extraFilter }, update, {
      returnDocument: 'after',
    });
  }
}
