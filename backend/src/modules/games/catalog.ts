import { randomInt } from 'node:crypto';
import { TRIVIA_DIFFICULTIES } from '../../db/types.js';

/** How many questions a trivia duel has, and how many prompts a This or That round has. */
export const TRIVIA_QUESTION_COUNT = 5;
export const THIS_OR_THAT_PROMPT_COUNT = 7;

/**
 * Open Trivia Database data is CC BY-SA 4.0. Wherever its questions are shown (the trivia card and
 * Settings → About) the app shows this credit, linking the source and the licence (§3(a)(1)(C)).
 * Question and answer text is never edited beyond decoding. The per-question category labels are
 * shortened ("Entertainment: Film" becomes "Film", see shortCategoryLabel), and the licence asks
 * for changes to be indicated (§3(a)(1)(B)), so the credit says so.
 */
export const OPENTDB_ATTRIBUTION =
  'Questions from Open Trivia Database (opentdb.com), CC BY-SA 4.0. Category names shortened.';
export const OPENTDB_URL = 'https://opentdb.com';
export const OPENTDB_LICENSE_URL = 'https://creativecommons.org/licenses/by-sa/4.0/';

/**
 * Curated OpenTDB categories, each with plenty of verified multiple-choice questions (live counts
 * on 2026-10-26: 469, 301, 495, 196, 1186, 299, 176, 383, 411, 99). Politics, celebrities and
 * similar are left out on purpose. "Any" is resolved to one of these on the server, never to an
 * uncurated category.
 */
export const TRIVIA_CATEGORIES = [
  { id: 9, name: 'General knowledge' },
  { id: 11, name: 'Film' },
  { id: 12, name: 'Music' },
  { id: 14, name: 'Television' },
  { id: 15, name: 'Video games' },
  { id: 17, name: 'Science & nature' },
  { id: 21, name: 'Sports' },
  { id: 22, name: 'Geography' },
  { id: 23, name: 'History' },
  { id: 27, name: 'Animals' },
] as const;
export const TRIVIA_CATEGORY_IDS = [9, 11, 12, 14, 15, 17, 21, 22, 23, 27] as const;
export type TriviaCategoryId = (typeof TRIVIA_CATEGORY_IDS)[number];

export function triviaCategory(id: number): { id: number; name: string } | null {
  const c = TRIVIA_CATEGORIES.find((x) => x.id === id);
  return c ? { id: c.id, name: c.name } : null;
}

/** "Any" category: a random curated one (an OpenTDB call without a category would mix in all 24). */
export function randomTriviaCategory(random: (n: number) => number = randomInt): {
  id: number;
  name: string;
} {
  const c = TRIVIA_CATEGORIES[random(TRIVIA_CATEGORIES.length)]!;
  return { id: c.id, name: c.name };
}

export const THIS_OR_THAT_DECKS = [
  { id: 'mix', name: 'A bit of everything', emoji: '🎲' },
  { id: 'everyday', name: 'Everyday', emoji: '☕' },
  { id: 'us', name: 'Us', emoji: '💞' },
  { id: 'travel_food', name: 'Travel & food', emoji: '🌍' },
  { id: 'dreams', name: 'Dreams', emoji: '✨' },
] as const;
export const DECK_IDS = ['mix', 'everyday', 'us', 'travel_food', 'dreams'] as const;
export type DeckId = (typeof DECK_IDS)[number];

export const GAME_CATALOG_KINDS = [
  {
    kind: 'rps',
    title: 'Stone Paper Scissors Thread',
    emoji: '✊',
    blurb: 'One secret move each. Thread beats stone and paper; scissors cut thread.',
    competitive: true,
  },
  {
    kind: 'tictactoe',
    title: 'Tic-tac-toe',
    emoji: '❌',
    blurb: 'Take turns on a 3×3 board. Three in a row wins.',
    competitive: true,
  },
  {
    kind: 'trivia',
    title: 'Trivia duel',
    emoji: '🧠',
    blurb: `The same ${TRIVIA_QUESTION_COUNT} questions for both of you. Most right answers wins.`,
    competitive: true,
  },
  {
    kind: 'this_or_that',
    title: 'This or That',
    emoji: '💞',
    blurb: `${THIS_OR_THAT_PROMPT_COUNT} quick picks, answered blind. See how in sync you are.`,
    competitive: false,
  },
] as const;

/** Body of GET /games/catalog: everything the picker needs, so the app hard-codes no options. */
export function gameCatalog() {
  return {
    kinds: GAME_CATALOG_KINDS.map((k) => ({ ...k })),
    trivia: {
      questionCount: TRIVIA_QUESTION_COUNT,
      difficulties: [...TRIVIA_DIFFICULTIES],
      categories: TRIVIA_CATEGORIES.map((c) => ({ ...c })),
      attribution: OPENTDB_ATTRIBUTION,
      attributionUrl: OPENTDB_URL,
      licenseUrl: OPENTDB_LICENSE_URL,
    },
    thisOrThat: {
      promptCount: THIS_OR_THAT_PROMPT_COUNT,
      decks: THIS_OR_THAT_DECKS.map((d) => ({ ...d })),
    },
  };
}
