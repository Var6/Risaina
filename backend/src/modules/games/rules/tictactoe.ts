import type { ObjectId } from 'mongodb';
import type { TttMark, TttState } from '../../../db/types.js';

/** The eight lines of three (rows, columns, diagonals); cells are row * 3 + col. */
export const LINES: readonly (readonly [number, number, number])[] = [
  [0, 1, 2],
  [3, 4, 5],
  [6, 7, 8],
  [0, 3, 6],
  [1, 4, 7],
  [2, 5, 8],
  [0, 4, 8],
  [2, 4, 6],
];

export function winningLine(board: readonly (TttMark | null)[]): number[] | null {
  for (const [a, b, c] of LINES) {
    const m = board[a];
    if (m && m === board[b] && m === board[c]) return [a, b, c];
  }
  return null;
}

/** A fresh round: empty board, `xId` plays X and moves first. */
export function newRound(xId: ObjectId): TttState {
  return { board: Array(9).fill(null), xId, turnId: xId, moveCount: 0, lastCell: null, line: null };
}

export function markOf(state: TttState, userId: ObjectId): TttMark {
  return state.xId.equals(userId) ? 'x' : 'o';
}

export interface TttMoveResult {
  board: (TttMark | null)[];
  line: number[] | null;
  /** Board full with no line: a draw. */
  full: boolean;
}

/** Places `mark` on an empty `cell` (the caller has checked turn and emptiness). */
export function applyMove(board: readonly (TttMark | null)[], cell: number, mark: TttMark): TttMoveResult {
  const next = [...board];
  next[cell] = mark;
  const line = winningLine(next);
  return { board: next, line, full: line === null && next.every((c) => c !== null) };
}
