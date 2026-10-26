import type { ObjectId } from 'mongodb';
import type { QuizPlayer, QuizState } from '../../../db/types.js';

export function newPlayers(memberIds: readonly ObjectId[]): QuizPlayer[] {
  return memberIds.map((userId) => ({ userId, picks: [], score: 0, finishedAt: null }));
}

/** Both players answered every item. Only then are picks, scores and matches revealed. */
export function bothFinished(q: Pick<QuizState, 'players'>): boolean {
  return q.players.length === 2 && q.players.every((p) => p.finishedAt !== null);
}

/** Trivia result once both finished: the higher score wins; equal scores are a draw. */
export function triviaOutcome(q: Pick<QuizState, 'players'>): { winner: ObjectId; loser: ObjectId } | 'draw' {
  const [p1, p2] = q.players as [QuizPlayer, QuizPlayer];
  if (p1.score === p2.score) return 'draw';
  return p1.score > p2.score
    ? { winner: p1.userId, loser: p2.userId }
    : { winner: p2.userId, loser: p1.userId };
}

/**
 * The trivia category a new round asks for: what the players picked, so "any" stays "any" (a fresh
 * random curated category) rather than repeating the one it resolved to. Older docs: the served one.
 */
export function requestedCategory(q: Pick<QuizState, 'category' | 'requestedCategory'>): number | null {
  return q.requestedCategory !== undefined ? q.requestedCategory : (q.category?.id ?? null);
}

/** This or That: how many prompts both picked the same side of. */
export function matchesCount(q: Pick<QuizState, 'players'>): number {
  const [p1, p2] = q.players as [QuizPlayer, QuizPlayer];
  return p1.picks.filter((pick, i) => p2.picks[i] === pick).length;
}
