import type { QuizItem, TriviaDifficulty } from '../../db/types.js';

export interface TriviaRequest {
  /** Questions needed (5 for a duel). */
  count: number;
  /** A curated OpenTDB category id, or null for "any" (the source picks a curated one). */
  category: number | null;
  difficulty: TriviaDifficulty | null;
  /** Question ids from the couple's recent duels: avoided when there are enough others. */
  exclude: ReadonlySet<string>;
}

export interface TriviaResult {
  source: 'opentdb' | 'builtin';
  /** The category every question came from, or null when the set mixes categories. */
  category: { id: number; name: string } | null;
  items: QuizItem[];
}

/** Where trivia duel questions come from. Never throws: a source that fails falls back. */
export interface TriviaSource {
  pick(req: TriviaRequest): Promise<TriviaResult>;
}
