import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type {
  ChallengeKind,
  CoupleDoc,
  GameChallenge,
  GameDoc,
  GameStatus,
  RpsMove,
} from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { isDuplicateKey } from '../auth/service.js';
import { toGameDto, toGameEventView } from '../shared/dto.js';
import type { GameDto } from '../shared/schemas.js';
import { GameRepo } from './repo.js';
import { DARES, TRUTHS } from './suggestions.js';

/** What happened, carried next to the neutral game in `game.updated` (handy for notification text). */
export type GameAction =
  | 'created'
  | 'moved'
  | 'revealed'
  | 'draw'
  | 'rematch'
  | 'challenged'
  | 'chosen'
  | 'done'
  | 'confirmed'
  | 'rejected'
  | 'cancelled';

/** What each move beats. Every pair of different moves has exactly one winner. */
export const BEATS: Record<RpsMove, readonly RpsMove[]> = {
  rock: ['scissors'],
  paper: ['rock'],
  scissors: ['paper', 'thread'],
  thread: ['rock', 'paper'],
};
const OPEN_STATUSES: GameStatus[] = [
  'waiting',
  'draw',
  'revealed',
  'challenged',
  'chosen',
  'awaiting_confirmation',
];

/**
 * Rock–paper–scissors with a blind truth or dare. A game is couple-scoped; at most one open game
 * per couple (unique partial index). HTTP answers are viewer-specific; events carry a viewer-neutral
 * view that never reveals a move before both players moved and never carries truth/dare texts or
 * the answer.
 *
 * waiting → (both moved) → draw | revealed; draw → rematch → waiting.
 * revealed → challenge (winner writes a truth AND a dare) → challenged
 *          → choose (loser picks blind) → chosen
 *          → complete (loser; answer required for a truth) → awaiting_confirmation
 *          → confirm (winner) → completed   |   reject (winner, optional note) → chosen.
 * Any status except completed → cancelled.
 *
 * Each action checks the status first (409 invalid_game_state), then the caller's role (403).
 */
export class GameService {
  private readonly repo: GameRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new GameRepo(ctx.db);
  }

  suggestions(): { truths: string[]; dares: string[] } {
    return { truths: [...TRUTHS], dares: [...DARES] };
  }

  async create(couple: CoupleDoc, userId: string, partnerId: string, input: { kind: 'rps' }) {
    const uid = new ObjectId(userId);
    const existing = await this.repo.findOpen(couple._id);
    if (existing) throw errors.gameInProgress(toGameDto(existing, userId));
    const now = this.ctx.now();
    const doc: GameDoc = {
      _id: newId(),
      coupleId: couple._id,
      kind: input.kind,
      createdBy: uid,
      memberIds: [uid, new ObjectId(partnerId)],
      status: 'waiting',
      open: true,
      round: 1,
      moves: [],
      winnerId: null,
      loserId: null,
      challenge: null,
      answer: null,
      createdAt: now,
      updatedAt: now,
      completedAt: null,
      cancelledAt: null,
      cancelledBy: null,
    };
    try {
      await this.repo.insert(doc);
    } catch (err) {
      if (!isDuplicateKey(err)) throw err;
      // the partner created one concurrently
      const open = await this.repo.findOpen(couple._id);
      throw errors.gameInProgress(open ? toGameDto(open, userId) : null);
    }
    return this.done(doc, uid, 'created');
  }

  async list(couple: CoupleDoc, userId: string, limit: number): Promise<{ items: GameDto[] }> {
    return { items: (await this.repo.list(couple._id, limit)).map((g) => toGameDto(g, userId)) };
  }

  async get(couple: CoupleDoc, userId: string, id: ObjectId): Promise<{ game: GameDto }> {
    return { game: toGameDto(await this.mustFind(couple, id), userId) };
  }

  async move(couple: CoupleDoc, userId: string, id: ObjectId, move: RpsMove) {
    const uid = new ObjectId(userId);
    const game = await this.mustFind(couple, id);
    const hasMoved = (g: GameDoc) => g.moves.some((m) => m.userId.equals(uid));
    if (game.status !== 'waiting') {
      throw errors.invalidGameState(`Cannot move while the game is ${game.status}`, toGameDto(game, userId));
    }
    if (hasMoved(game)) throw errors.alreadyMoved(toGameDto(game, userId));

    const now = this.ctx.now();
    const moved = await this.repo.addMove(game._id, game.round, uid, move, now);
    if (!moved) {
      const fresh = await this.mustFind(couple, id);
      if (fresh.status === 'waiting' && hasMoved(fresh)) throw errors.alreadyMoved(toGameDto(fresh, userId));
      throw errors.invalidGameState(
        `Cannot move while the game is ${fresh.status}`,
        toGameDto(fresh, userId),
      );
    }
    if (moved.moves.length < 2) return this.done(moved, uid, 'moved');

    const [m1, m2] = moved.moves as [GameDoc['moves'][0], GameDoc['moves'][0]];
    let set: Partial<GameDoc>;
    if (m1.move === m2.move) {
      set = { status: 'draw', winnerId: null, loserId: null, updatedAt: now };
    } else {
      const [winner, loser] = BEATS[m1.move].includes(m2.move) ? [m1, m2] : [m2, m1];
      set = { status: 'revealed', winnerId: winner.userId, loserId: loser.userId, updatedAt: now };
    }
    const resolved = await this.repo.transition(
      moved._id,
      'waiting',
      { round: moved.round, 'moves.1': { $exists: true } },
      set,
    );
    if (!resolved) return this.done(await this.mustFind(couple, id), uid, 'moved'); // resolved concurrently
    return this.done(resolved, uid, resolved.status === 'draw' ? 'draw' : 'revealed');
  }

  async rematch(couple: CoupleDoc, userId: string, id: ObjectId) {
    const game = await this.mustFind(couple, id);
    if (game.status !== 'draw') {
      throw errors.invalidGameState('A rematch is only possible after a draw', toGameDto(game, userId));
    }
    const updated = await this.repo.transition(
      game._id,
      'draw',
      { round: game.round },
      { status: 'waiting', moves: [], winnerId: null, loserId: null, updatedAt: this.ctx.now() },
      { round: 1 },
    );
    if (!updated) return this.raceLost(couple, userId, id, 'A rematch is only possible after a draw');
    return this.done(updated, new ObjectId(userId), 'rematch');
  }

  async challenge(couple: CoupleDoc, userId: string, id: ObjectId, input: { truth: string; dare: string }) {
    const uid = new ObjectId(userId);
    const game = await this.mustFind(couple, id);
    this.expectStatus(
      game,
      'revealed',
      userId,
      'The truth and dare can only be set once the round is revealed',
    );
    if (!game.winnerId?.equals(uid)) throw errors.forbidden('Only the winner writes the truth and the dare');
    const now = this.ctx.now();
    const challenge: GameChallenge = {
      truth: input.truth,
      dare: input.dare,
      by: uid,
      at: now,
      chosen: null,
      chosenAt: null,
      doneAt: null,
      confirmedAt: null,
      rejectNote: null,
    };
    const updated = await this.repo.transition(
      game._id,
      'revealed',
      {},
      { status: 'challenged', challenge, updatedAt: now },
    );
    if (!updated) return this.raceLost(couple, userId, id, 'The game changed; reload it');
    return this.done(updated, uid, 'challenged');
  }

  async choose(couple: CoupleDoc, userId: string, id: ObjectId, kind: ChallengeKind) {
    const uid = new ObjectId(userId);
    const game = await this.mustFind(couple, id);
    const c = this.expectStatus(
      game,
      'challenged',
      userId,
      'Truth or dare can only be chosen once it is set',
    );
    if (!game.loserId?.equals(uid)) throw errors.forbidden('Only the loser chooses truth or dare');
    const now = this.ctx.now();
    const updated = await this.repo.transition(
      game._id,
      'challenged',
      { updatedAt: game.updatedAt },
      { status: 'chosen', challenge: { ...c, chosen: kind, chosenAt: now }, updatedAt: now },
    );
    if (!updated) return this.raceLost(couple, userId, id, 'The game changed; reload it');
    return this.done(updated, uid, 'chosen');
  }

  async complete(couple: CoupleDoc, userId: string, id: ObjectId, input: { answer?: string | undefined }) {
    const uid = new ObjectId(userId);
    const game = await this.mustFind(couple, id);
    const c = this.expectStatus(game, 'chosen', userId, 'Only a chosen truth or dare can be marked done');
    if (!game.loserId?.equals(uid)) throw errors.forbidden('Only the loser marks the truth or dare done');
    const answer = input.answer && input.answer.length > 0 ? input.answer : null;
    if (c.chosen === 'truth' && answer === null) {
      const message = 'An answer is required for a truth';
      throw errors.validation(message, [{ path: '/answer', message }]);
    }
    const now = this.ctx.now();
    const updated = await this.repo.transition(
      game._id,
      'chosen',
      { updatedAt: game.updatedAt },
      {
        status: 'awaiting_confirmation',
        answer,
        challenge: { ...c, doneAt: now, rejectNote: null },
        updatedAt: now,
      },
    );
    if (!updated) return this.raceLost(couple, userId, id, 'The game changed; reload it');
    return this.done(updated, uid, 'done');
  }

  async confirm(couple: CoupleDoc, userId: string, id: ObjectId) {
    const uid = new ObjectId(userId);
    const game = await this.mustFind(couple, id);
    const c = this.expectStatus(game, 'awaiting_confirmation', userId, 'Nothing is waiting for confirmation');
    if (!game.winnerId?.equals(uid)) throw errors.forbidden('Only the winner confirms the truth or dare');
    const now = this.ctx.now();
    const updated = await this.repo.transition(
      game._id,
      'awaiting_confirmation',
      { updatedAt: game.updatedAt },
      {
        status: 'completed',
        open: false,
        challenge: { ...c, confirmedAt: now },
        completedAt: now,
        updatedAt: now,
      },
    );
    if (!updated) return this.raceLost(couple, userId, id, 'The game changed; reload it');
    return this.done(updated, uid, 'confirmed');
  }

  async reject(couple: CoupleDoc, userId: string, id: ObjectId, input: { note?: string | undefined }) {
    const uid = new ObjectId(userId);
    const game = await this.mustFind(couple, id);
    const c = this.expectStatus(game, 'awaiting_confirmation', userId, 'Nothing is waiting for confirmation');
    if (!game.winnerId?.equals(uid)) throw errors.forbidden('Only the winner rejects the truth or dare');
    const now = this.ctx.now();
    const note = input.note && input.note.length > 0 ? input.note : null;
    const updated = await this.repo.transition(
      game._id,
      'awaiting_confirmation',
      { updatedAt: game.updatedAt },
      {
        status: 'chosen',
        answer: null,
        challenge: { ...c, doneAt: null, rejectNote: note },
        updatedAt: now,
      },
    );
    if (!updated) return this.raceLost(couple, userId, id, 'The game changed; reload it');
    return this.done(updated, uid, 'rejected');
  }

  async cancel(couple: CoupleDoc, userId: string, id: ObjectId) {
    const uid = new ObjectId(userId);
    const game = await this.mustFind(couple, id);
    if (game.status === 'cancelled') return { game: toGameDto(game, userId) }; // idempotent, no event
    if (game.status === 'completed') {
      throw errors.invalidGameState('A completed game cannot be cancelled', toGameDto(game, userId));
    }
    const now = this.ctx.now();
    const updated = await this.repo.transition(
      game._id,
      OPEN_STATUSES,
      {},
      { status: 'cancelled', open: false, cancelledAt: now, cancelledBy: uid, updatedAt: now },
    );
    if (!updated) {
      const fresh = await this.mustFind(couple, id);
      if (fresh.status === 'cancelled') return { game: toGameDto(fresh, userId) };
      throw errors.invalidGameState('A completed game cannot be cancelled', toGameDto(fresh, userId));
    }
    return this.done(updated, uid, 'cancelled');
  }

  /**
   * 409 invalid_game_state unless the game is in `status`. Returns the challenge (non-null in every
   * status from `challenged` on; null before).
   */
  private expectStatus(game: GameDoc, status: GameStatus, userId: string, message: string): GameChallenge {
    if (game.status !== status) throw errors.invalidGameState(message, toGameDto(game, userId));
    return game.challenge as GameChallenge;
  }

  private async mustFind(couple: CoupleDoc, id: ObjectId): Promise<GameDoc> {
    const game = await this.repo.findInCouple(id, couple._id);
    if (!game) throw errors.notFound('Game');
    return game;
  }

  private async raceLost(couple: CoupleDoc, userId: string, id: ObjectId, message: string): Promise<never> {
    const fresh = await this.mustFind(couple, id);
    throw errors.invalidGameState(message, toGameDto(fresh, userId));
  }

  private async done(game: GameDoc, actor: ObjectId, action: GameAction): Promise<{ game: GameDto }> {
    await this.ctx.events.emit({
      coupleId: game.coupleId,
      type: 'game.updated',
      actorId: actor,
      payload: { game: toGameEventView(game), action },
    });
    return { game: toGameDto(game, actor.toHexString()) };
  }
}
