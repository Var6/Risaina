import type { QuizItem } from '../../db/types.js';
import { triviaCategory } from '../../modules/games/catalog.js';
import { cryptoRandom, type RandomInt, sampleAvoiding, shuffled } from '../../modules/games/random.js';
import { type FallbackQuestion, TRIVIA_FALLBACK } from '../../modules/games/trivia-fallback.js';
import type { TriviaRequest, TriviaResult, TriviaSource } from './types.js';

/** Shuffles the four answers and records where the right one landed. */
export function toQuizItem(q: FallbackQuestion, random: RandomInt = cryptoRandom): QuizItem {
  const choices = shuffled([q.correct, ...q.wrong], random);
  return {
    id: q.id,
    text: q.text,
    choices,
    correct: choices.indexOf(q.correct),
    category: q.category,
    difficulty: q.difficulty,
  };
}

/**
 * The offline question set. Narrows by category, then difficulty, only while enough questions
 * remain, so a duel always gets its full count: a category the set does not cover (Television,
 * Video games) falls back to a mix of every category.
 */
export class BuiltinTrivia implements TriviaSource {
  constructor(
    private readonly bank: readonly FallbackQuestion[] = TRIVIA_FALLBACK,
    private readonly random: RandomInt = cryptoRandom,
  ) {}

  async pick(req: TriviaRequest): Promise<TriviaResult> {
    let pool = this.bank;
    let category: TriviaResult['category'] = null;
    if (req.category !== null) {
      const inCategory = pool.filter((q) => q.categoryId === req.category);
      if (inCategory.length >= req.count) {
        pool = inCategory;
        category = triviaCategory(req.category);
      }
    }
    if (req.difficulty !== null) {
      const atLevel = pool.filter((q) => q.difficulty === req.difficulty);
      if (atLevel.length >= req.count) pool = atLevel;
    }
    const picked = sampleAvoiding(pool, req.count, req.exclude, (q) => q.id, this.random);
    return { source: 'builtin', category, items: picked.map((q) => toQuizItem(q, this.random)) };
  }
}
