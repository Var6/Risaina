import type { Filter, ObjectId, UpdateFilter } from 'mongodb';
import type { Collections } from '../../db/collections.js';
import type { GameDoc, GameKind, GameStatus, RpsMove } from '../../db/types.js';

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

  /**
   * Conditional write with dotted keys and positional operators (tic-tac-toe cells, quiz picks).
   * The filter carries every precondition, so a lost race returns null instead of a wrong state.
   */
  update(id: ObjectId, filter: Filter<GameDoc>, update: UpdateFilter<GameDoc>): Promise<GameDoc | null> {
    return this.db.games.findOneAndUpdate({ ...filter, _id: id }, update, { returnDocument: 'after' });
  }

  /** Item ids (questions or prompts) from the couple's newest games of `kind`, to avoid repeats. */
  async recentItemIds(coupleId: ObjectId, kind: GameKind, games: number): Promise<Set<string>> {
    const docs = await this.db.games
      .find({ coupleId, kind }, { projection: { 'quiz.items.id': 1 } })
      .sort({ createdAt: -1 })
      .limit(games)
      .toArray();
    return new Set(docs.flatMap((d) => d.quiz?.items.map((i) => i.id) ?? []));
  }
}
