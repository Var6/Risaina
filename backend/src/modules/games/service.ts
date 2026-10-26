import { ObjectId, type UpdateFilter } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import type {
  ChallengeKind,
  CoupleDoc,
  GameChallenge,
  GameDoc,
  GamePrize,
  GameStatus,
  QuizState,
  RpsMove,
  TriviaDifficulty,
} from '../../db/types.js';
import { errors } from '../../lib/errors.js';
import { newId } from '../../lib/ids.js';
import { isDuplicateKey } from '../auth/service.js';
import { toGameDto, toGameEventView } from '../shared/dto.js';
import type { GameDto } from '../shared/schemas.js';
import { gameCatalog, THIS_OR_THAT_PROMPT_COUNT, TRIVIA_QUESTION_COUNT } from './catalog.js';
import { GameRepo } from './repo.js';
import { bothFinished, matchesCount, newPlayers, requestedCategory, triviaOutcome } from './rules/quiz.js';
import { applyMove, markOf, newRound } from './rules/tictactoe.js';
import type { CreateGameInput, PlayInput } from './schemas.js';
import { DARES, TRUTHS } from './suggestions.js';
import { pickPrompts } from './this-or-that.js';

/**
 * What happened, carried next to the neutral game in `game.updated` (handy for notification text).
 * Push delivery per action is decided by the outbox push policy, not here: 'answered' (one trivia
 * answer), 'cancelled' and 'ended' are not worth a push; everything else is.
 */
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
  | 'cancelled'
  | 'answered' // trivia: one answer that did not finish the player's set
  | 'finished' // trivia / this_or_that: the player is done, the partner is not yet
  | 'matched' // this_or_that: both done, the match count is in
  | 'ended'; // a draw called off (completed, no winner)

/** What each move beats. Every pair of different moves has exactly one winner. */
export const BEATS: Record<RpsMove, readonly RpsMove[]> = {
  rock: ['scissors'],
  paper: ['rock'],
  scissors: ['paper', 'thread'],
  thread: ['rock', 'paper'],
};
/** 400 for a request meant for another kind of game (typically an older app on a newer game). */
function kindMismatch(kind: string) {
  const message = 'Update Risaina to play this game';
  return errors.validation(message, [{ path: '/kind', message: `This game is ${kind}` }]);
}

const OPEN_STATUSES: GameStatus[] = [
  'waiting',
  'draw',
  'revealed',
  'challenged',
  'chosen',
  'awaiting_confirmation',
];

/** Recent games whose questions / prompts a new game avoids repeating. */
const RECENT_TRIVIA_GAMES = 30;
const RECENT_THIS_OR_THAT_GAMES = 5;

/**
 * A decided round: with the truth-or-dare prize it goes to `revealed` (the winner writes the
 * challenge); "just for fun" ends right there.
 */
function winSet(game: GameDoc, winnerId: ObjectId, loserId: ObjectId, now: Date): Partial<GameDoc> {
  return (game.prize ?? 'truth_or_dare') === 'truth_or_dare'
    ? { status: 'revealed', winnerId, loserId }
    : { status: 'completed', open: false, completedAt: now, winnerId, loserId };
}

/** A dotted-key `$set` (e.g. `ttt.board`, `quiz.players.$.picks`) for GameRepo.update. */
type DottedSet = Record<string, unknown>;

/**
 * Short two-player games in the chat, all server-authoritative, with an optional blind truth or
 * dare for the winner. A game is couple-scoped; at most one open game per couple, of any kind
 * (unique partial index). HTTP answers are viewer-specific; events carry a viewer-neutral view that
 * never reveals a secret (an rps move before both moved, trivia questions or answers, unrevealed
 * picks, truth/dare texts, the answer).
 *
 * Kinds: rps (stone, paper, scissors, thread), tictactoe, trivia (5 questions, server-fetched),
 * this_or_that (7 blind picks, no winner).
 *
 * waiting → (rps: both moved | tictactoe: a line or a full board | trivia: both answered all)
 *         → draw | revealed (prize 'truth_or_dare') | completed (prize 'none').
 * this_or_that: waiting → (both picked) → completed with the match count.
 * draw → rematch → waiting (next round)   |   draw → end → completed (no winner).
 * revealed → challenge (winner writes a truth AND a dare) → challenged
 *          → choose (loser picks blind) → chosen
 *          → complete (loser; answer required for a truth) → awaiting_confirmation
 *          → confirm (winner) → completed   |   reject (winner, optional note) → chosen.
 * Any status except completed → cancelled.
 *
 * Order of checks: the kind (400 "update Risaina"), then the status (409 invalid_game_state), then
 * the caller's turn or role (409 not_your_turn / 403), then the move itself (409 invalid_move,
 * 409 already_moved, 400).
 */
export class GameService {
  private readonly repo: GameRepo;

  constructor(private readonly ctx: AppContext) {
    this.repo = new GameRepo(ctx.db);
  }

  suggestions(): { truths: string[]; dares: string[] } {
    return { truths: [...TRUTHS], dares: [...DARES] };
  }

  catalog() {
    return gameCatalog();
  }

  async create(couple: CoupleDoc, userId: string, partnerId: string, input: CreateGameInput) {
    const uid = new ObjectId(userId);
    const pid = new ObjectId(partnerId);
    // Fast 409 before any slow work (trivia waits up to TRIVIA_TIMEOUT_MS for questions); the
    // unique index still settles a race at insert time.
    const existing = await this.repo.findOpen(couple._id);
    if (existing) throw errors.gameInProgress(toGameDto(existing, userId));
    const memberIds = [uid, pid];
    const prize: GamePrize =
      input.kind !== 'this_or_that' && input.prize === 'none' ? 'none' : 'truth_or_dare';
    const extra: Pick<GameDoc, 'prize' | 'ttt' | 'quiz'> = { prize };
    switch (input.kind) {
      case 'rps':
        break;
      case 'tictactoe':
        // The invitee plays X and moves first: one notification, one action, and the creator gives
        // up the first-move advantage.
        extra.ttt = newRound(pid);
        extra.quiz = null;
        break;
      case 'trivia':
        extra.ttt = null;
        extra.quiz = await this.triviaQuiz(
          couple._id,
          memberIds,
          input.category ?? null,
          input.difficulty ?? null,
          new Set(),
        );
        break;
      case 'this_or_that': {
        const deck = input.deck ?? 'mix';
        const avoid = await this.repo.recentItemIds(couple._id, 'this_or_that', RECENT_THIS_OR_THAT_GAMES);
        extra.prize = 'none'; // no winner, so no truth or dare
        extra.ttt = null;
        extra.quiz = {
          source: 'curated',
          category: null,
          difficulty: null,
          deck,
          items: pickPrompts(deck, THIS_OR_THAT_PROMPT_COUNT, avoid),
          players: newPlayers(memberIds),
          matches: null,
        };
        break;
      }
    }
    const now = this.ctx.now();
    // Every field the live validator requires is written for every kind (moves: [], challenge: null…).
    const doc: GameDoc = {
      _id: newId(),
      coupleId: couple._id,
      kind: input.kind,
      createdBy: uid,
      memberIds,
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
      ...extra,
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
    // The kind check comes before the status check so an older app tapping on a newer kind of game
    // always gets the "update Risaina" answer, never a confusing state error.
    if ((game.kind as string) !== 'rps') throw kindMismatch(game.kind);
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
      set = { ...winSet(moved, winner.userId, loser.userId, now), updatedAt: now };
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

  /**
   * The next round after a draw (either player). Tic-tac-toe swaps who is X (and so who starts);
   * trivia gets 5 new questions, fetched BEFORE the conditional write so a slow fetch can't race it.
   */
  async rematch(couple: CoupleDoc, userId: string, id: ObjectId) {
    const game = await this.mustFind(couple, id);
    if (game.status !== 'draw') {
      throw errors.invalidGameState('A rematch is only possible after a draw', toGameDto(game, userId));
    }
    const set: Partial<GameDoc> = {
      status: 'waiting',
      moves: [],
      winnerId: null,
      loserId: null,
      updatedAt: this.ctx.now(),
    };
    if (game.kind === 'tictactoe' && game.ttt) {
      const xId = game.ttt.xId;
      set.ttt = newRound(game.memberIds.find((m) => !m.equals(xId)) ?? xId);
    } else if (game.kind === 'trivia' && game.quiz) {
      const q = game.quiz;
      const avoid = new Set(q.items.map((i) => i.id));
      set.quiz = await this.triviaQuiz(couple._id, game.memberIds, requestedCategory(q), q.difficulty, avoid);
    }
    const updated = await this.repo.transition(game._id, 'draw', { round: game.round }, set, { round: 1 });
    if (!updated) return this.raceLost(couple, userId, id, 'A rematch is only possible after a draw');
    return this.done(updated, new ObjectId(userId), 'rematch');
  }

  /** A draw called off by either player: completed with no winner (and so no truth or dare). */
  async end(couple: CoupleDoc, userId: string, id: ObjectId) {
    const game = await this.mustFind(couple, id);
    if (game.status !== 'draw') {
      throw errors.invalidGameState('Only a draw can be ended this way', toGameDto(game, userId));
    }
    const now = this.ctx.now();
    const updated = await this.repo.transition(
      game._id,
      'draw',
      { round: game.round },
      { status: 'completed', open: false, winnerId: null, loserId: null, completedAt: now, updatedAt: now },
    );
    if (!updated) return this.raceLost(couple, userId, id, 'Only a draw can be ended this way');
    return this.done(updated, new ObjectId(userId), 'ended');
  }

  /** POST /games/:id/play: one endpoint for every kind; the body's kind must match the game's. */
  async play(couple: CoupleDoc, userId: string, id: ObjectId, input: PlayInput) {
    const game = await this.mustFind(couple, id);
    if (input.kind !== game.kind) throw kindMismatch(game.kind);
    switch (input.kind) {
      case 'rps':
        return this.move(couple, userId, id, input.move);
      case 'tictactoe':
        return this.playTicTacToe(couple, userId, game, input.cell);
      case 'trivia':
        return this.answerTrivia(couple, userId, game, input.item, input.choice);
      case 'this_or_that':
        return this.submitPicks(couple, userId, game, input.picks);
    }
  }

  private async playTicTacToe(couple: CoupleDoc, userId: string, game: GameDoc, cell: number) {
    const uid = new ObjectId(userId);
    const t = game.ttt;
    if (game.status !== 'waiting' || !t) {
      throw errors.invalidGameState(`Cannot move while the game is ${game.status}`, toGameDto(game, userId));
    }
    if (!t.turnId?.equals(uid)) throw errors.notYourTurn(toGameDto(game, userId));
    if (t.board[cell] !== null) throw errors.invalidMove('That square is taken', toGameDto(game, userId));

    const other = game.memberIds.find((m) => !m.equals(uid)) ?? uid;
    const r = applyMove(t.board, cell, markOf(t, uid));
    const now = this.ctx.now();
    const set: DottedSet = {
      'ttt.board': r.board,
      'ttt.moveCount': t.moveCount + 1,
      'ttt.lastCell': cell,
      'ttt.line': r.line,
      'ttt.turnId': r.line || r.full ? null : other,
      updatedAt: now,
      ...(r.line ? winSet(game, uid, other, now) : r.full ? { status: 'draw' } : {}),
    };
    // moveCount + turn in the filter: of two concurrent taps only one lands; the other re-reads.
    const updated = await this.repo.update(
      game._id,
      { status: 'waiting', round: game.round, 'ttt.moveCount': t.moveCount, 'ttt.turnId': uid },
      { $set: set } as UpdateFilter<GameDoc>,
    );
    if (!updated) {
      const fresh = await this.mustFind(couple, game._id);
      const dto = toGameDto(fresh, userId);
      if (fresh.status === 'waiting' && fresh.ttt) {
        if (!fresh.ttt.turnId?.equals(uid)) throw errors.notYourTurn(dto);
        if (fresh.ttt.board[cell] !== null) throw errors.invalidMove('That square is taken', dto);
      }
      throw errors.invalidGameState(`Cannot move while the game is ${fresh.status}`, dto);
    }
    return this.done(updated, uid, r.line ? 'revealed' : r.full ? 'draw' : 'moved');
  }

  /**
   * One trivia answer. Answers are sequential and final: `item` must be the next unanswered one, so
   * a retried request gets 409 already_moved instead of answering the following question.
   */
  private async answerTrivia(couple: CoupleDoc, userId: string, game: GameDoc, item: number, choice: number) {
    const uid = new ObjectId(userId);
    const q = game.quiz;
    if (game.status !== 'waiting' || !q) {
      throw errors.invalidGameState('This duel is over', toGameDto(game, userId));
    }
    const me = q.players.find((p) => p.userId.equals(uid));
    if (!me) throw errors.forbidden('Only the two players answer');
    this.checkNextItem(game, userId, me.picks.length, item);
    const question = q.items[item];
    if (!question) {
      throw errors.validation('There is no such question', [{ path: '/item', message: 'out of range' }]);
    }
    if (choice >= question.choices.length) {
      throw errors.validation('There is no such answer', [{ path: '/choice', message: 'out of range' }]);
    }
    const now = this.ctx.now();
    const last = item + 1 === q.items.length;
    // `picks: { $size: item }` makes the write conditional on this being the next answer.
    const updated = await this.repo.update(
      game._id,
      {
        status: 'waiting',
        round: game.round,
        'quiz.players': { $elemMatch: { userId: uid, picks: { $size: item } } },
      },
      {
        $push: { 'quiz.players.$.picks': choice },
        $inc: { 'quiz.players.$.score': question.correct === choice ? 1 : 0 },
        $set: { updatedAt: now, ...(last ? { 'quiz.players.$.finishedAt': now } : {}) },
      } as UpdateFilter<GameDoc>,
    );
    if (!updated) {
      const fresh = await this.mustFind(couple, game._id);
      const mine = fresh.quiz?.players.find((p) => p.userId.equals(uid));
      if (fresh.status === 'waiting' && mine) this.checkNextItem(fresh, userId, mine.picks.length, item);
      throw errors.invalidGameState('This duel is over', toGameDto(fresh, userId));
    }
    return this.afterPick(couple, updated, uid, now);
  }

  /**
   * This or That: all picks in one request. The prompts are not secret (the app shows them all and
   * collects the picks locally), the picks are: one write, one event and one push per player
   * instead of seven of each.
   */
  private async submitPicks(couple: CoupleDoc, userId: string, game: GameDoc, picks: number[]) {
    const uid = new ObjectId(userId);
    const q = game.quiz;
    if (game.status !== 'waiting' || !q) {
      throw errors.invalidGameState('This round is over', toGameDto(game, userId));
    }
    const me = q.players.find((p) => p.userId.equals(uid));
    if (!me) throw errors.forbidden('Only the two players pick');
    if (me.finishedAt !== null) {
      throw errors.alreadyMoved(toGameDto(game, userId), 'You already made your picks');
    }
    if (picks.length !== q.items.length || picks.some((p, i) => p >= (q.items[i]?.choices.length ?? 0))) {
      const message = `Send one pick for each of the ${q.items.length} prompts`;
      throw errors.validation(message, [{ path: '/picks', message }]);
    }
    const now = this.ctx.now();
    const updated = await this.repo.update(
      game._id,
      {
        status: 'waiting',
        round: game.round,
        'quiz.players': { $elemMatch: { userId: uid, finishedAt: null } },
      },
      {
        $set: { 'quiz.players.$.picks': picks, 'quiz.players.$.finishedAt': now, updatedAt: now },
      } as UpdateFilter<GameDoc>,
    );
    if (!updated) {
      const fresh = await this.mustFind(couple, game._id);
      const dto = toGameDto(fresh, userId);
      const mine = fresh.quiz?.players.find((p) => p.userId.equals(uid));
      if (fresh.status === 'waiting' && mine?.finishedAt) {
        throw errors.alreadyMoved(dto, 'You already made your picks');
      }
      throw errors.invalidGameState('This round is over', dto);
    }
    return this.afterPick(couple, updated, uid, now);
  }

  /** 409 unless `item` is the next one: already_moved for a replay, invalid_move for a skip. */
  private checkNextItem(game: GameDoc, userId: string, answered: number, item: number): void {
    if (item < answered) {
      throw errors.alreadyMoved(toGameDto(game, userId), 'You already answered that one');
    }
    if (item > answered) {
      throw errors.invalidMove(`Answer question ${answered + 1} first`, toGameDto(game, userId));
    }
  }

  /**
   * After a quiz write: still answering → 'answered'; this player done → 'finished'; both done →
   * resolve. findOneAndUpdate returns the state right after its own write, so only the second
   * finisher sees both done and resolves; the filter makes a concurrent resolve or cancel a no-op.
   */
  private async afterPick(couple: CoupleDoc, updated: GameDoc, uid: ObjectId, now: Date) {
    const q = updated.quiz as QuizState;
    const me = q.players.find((p) => p.userId.equals(uid));
    if (!me?.finishedAt) return this.done(updated, uid, 'answered');
    if (!bothFinished(q)) return this.done(updated, uid, 'finished');

    let set: DottedSet;
    let action: GameAction;
    if (updated.kind === 'this_or_that') {
      set = { status: 'completed', open: false, completedAt: now, 'quiz.matches': matchesCount(q) };
      action = 'matched';
    } else {
      const outcome = triviaOutcome(q);
      set = outcome === 'draw' ? { status: 'draw' } : winSet(updated, outcome.winner, outcome.loser, now);
      action = outcome === 'draw' ? 'draw' : 'revealed';
    }
    const resolved = await this.repo.update(
      updated._id,
      {
        status: 'waiting',
        round: updated.round,
        'quiz.players': { $not: { $elemMatch: { finishedAt: null } } },
      },
      { $set: { ...set, updatedAt: now } } as UpdateFilter<GameDoc>,
    );
    if (!resolved) return this.done(await this.mustFind(couple, updated._id), uid, 'finished');
    return this.done(resolved, uid, action);
  }

  /**
   * Five questions for a duel, avoiding the couple's recent ones. Never throws: the source falls
   * back to the built-in set when Open Trivia DB can't serve.
   */
  private async triviaQuiz(
    coupleId: ObjectId,
    memberIds: ObjectId[],
    category: number | null,
    difficulty: TriviaDifficulty | null,
    alsoAvoid: ReadonlySet<string>,
  ): Promise<QuizState> {
    const recent = await this.repo.recentItemIds(coupleId, 'trivia', RECENT_TRIVIA_GAMES);
    const exclude = new Set([...recent, ...alsoAvoid]);
    const r = await this.ctx.trivia.pick({ count: TRIVIA_QUESTION_COUNT, category, difficulty, exclude });
    return {
      source: r.source,
      category: r.category,
      requestedCategory: category,
      difficulty,
      deck: null,
      items: r.items,
      players: newPlayers(memberIds),
      matches: null,
    };
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
