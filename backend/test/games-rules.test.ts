import { ObjectId } from 'mongodb';
import { describe, expect, it } from 'vitest';
import type { QuizPlayer, TttMark } from '../src/db/types.js';
import {
  DECK_IDS,
  gameCatalog,
  randomTriviaCategory,
  THIS_OR_THAT_PROMPT_COUNT,
  TRIVIA_CATEGORY_IDS,
  TRIVIA_QUESTION_COUNT,
} from '../src/modules/games/catalog.js';
import { sampleAvoiding, shuffled } from '../src/modules/games/random.js';
import {
  bothFinished,
  matchesCount,
  newPlayers,
  requestedCategory,
  triviaOutcome,
} from '../src/modules/games/rules/quiz.js';
import { applyMove, LINES, markOf, newRound, winningLine } from '../src/modules/games/rules/tictactoe.js';
import { pickPrompts, THIS_OR_THAT_PROMPTS } from '../src/modules/games/this-or-that.js';
import { TRIVIA_FALLBACK } from '../src/modules/games/trivia-fallback.js';
import { BuiltinTrivia } from '../src/services/trivia/builtin.js';

const empty = (): (TttMark | null)[] => Array(9).fill(null);

describe('tic-tac-toe rules', () => {
  it('detects every one of the eight lines, for both marks', () => {
    expect(LINES).toHaveLength(8);
    for (const line of LINES) {
      for (const mark of ['x', 'o'] as const) {
        const b = empty();
        for (const c of line) b[c] = mark;
        expect(winningLine(b)).toEqual([...line]);
      }
    }
  });

  it('no line on an empty board, a mixed line, or a full drawn board', () => {
    expect(winningLine(empty())).toBeNull();
    const mixed = empty();
    mixed[0] = 'x';
    mixed[1] = 'o';
    mixed[2] = 'x';
    expect(winningLine(mixed)).toBeNull();
    const draw: TttMark[] = ['x', 'o', 'x', 'x', 'o', 'o', 'o', 'x', 'x'];
    expect(winningLine(draw)).toBeNull();
  });

  it('applyMove places the mark, reports a line or a full board, and never mutates the input', () => {
    const b = empty();
    const r = applyMove(b, 4, 'x');
    expect(b[4]).toBeNull();
    expect(r).toEqual({
      board: [null, null, null, null, 'x', null, null, null, null],
      line: null,
      full: false,
    });

    const almost: (TttMark | null)[] = ['x', 'x', null, 'o', 'o', null, null, null, null];
    expect(applyMove(almost, 2, 'x')).toMatchObject({ line: [0, 1, 2], full: false });

    const lastCell: (TttMark | null)[] = ['x', 'o', 'x', 'x', 'o', 'o', 'o', 'x', null];
    expect(applyMove(lastCell, 8, 'x')).toMatchObject({ line: null, full: true });
    // A line on the very last cell is a win, not a draw.
    const winLast: (TttMark | null)[] = ['x', 'o', 'x', 'o', 'x', 'o', 'o', 'x', null];
    expect(applyMove(winLast, 8, 'x')).toMatchObject({ line: [0, 4, 8], full: false });
  });

  it('a new round: empty board, X moves first; marks follow xId', () => {
    const x = new ObjectId();
    const o = new ObjectId();
    const t = newRound(x);
    expect(t).toEqual({ board: empty(), xId: x, turnId: x, moveCount: 0, lastCell: null, line: null });
    expect(markOf(t, x)).toBe('x');
    expect(markOf(t, o)).toBe('o');
  });
});

describe('quiz rules', () => {
  const p = (score: number, picks: number[], finished: boolean): QuizPlayer => ({
    userId: new ObjectId(),
    picks,
    score,
    finishedAt: finished ? new Date() : null,
  });

  it('bothFinished only when both players finished', () => {
    expect(bothFinished({ players: newPlayers([new ObjectId(), new ObjectId()]) })).toBe(false);
    expect(bothFinished({ players: [p(1, [0], true), p(0, [], false)] })).toBe(false);
    expect(bothFinished({ players: [p(1, [0], true), p(0, [1], true)] })).toBe(true);
  });

  it('trivia: the higher score wins; equal is a draw', () => {
    const a = p(4, [], true);
    const b = p(3, [], true);
    expect(triviaOutcome({ players: [a, b] })).toEqual({ winner: a.userId, loser: b.userId });
    expect(triviaOutcome({ players: [b, a] })).toEqual({ winner: a.userId, loser: b.userId });
    expect(triviaOutcome({ players: [p(2, [], true), p(2, [], true)] })).toBe('draw');
  });

  it('this or that: counts equal picks', () => {
    expect(
      matchesCount({ players: [p(0, [0, 1, 1, 0, 0, 1, 1], true), p(0, [0, 1, 0, 0, 1, 1, 0], true)] }),
    ).toBe(4);
    expect(matchesCount({ players: [p(0, [1, 1], true), p(0, [1, 1], true)] })).toBe(2);
  });

  it('a new trivia round asks for what was picked: "any" stays any, older docs use the served category', () => {
    const history = { id: 23, name: 'History' };
    expect(requestedCategory({ category: history, requestedCategory: null })).toBeNull();
    expect(requestedCategory({ category: history, requestedCategory: 23 })).toBe(23);
    expect(requestedCategory({ category: history })).toBe(23);
    expect(requestedCategory({ category: null })).toBeNull();
  });
});

describe('random helpers', () => {
  it('shuffled keeps every item; sampleAvoiding prefers fresh ids and fills up with avoided ones', () => {
    const items = Array.from({ length: 20 }, (_, i) => `id${i}`);
    expect([...shuffled(items)].sort()).toEqual([...items].sort());
    const avoid = new Set(items.slice(0, 15));
    const five = sampleAvoiding(items, 5, avoid, (x) => x);
    expect(five.sort()).toEqual(items.slice(15).sort());
    const eight = sampleAvoiding(items, 8, avoid, (x) => x);
    expect(eight).toHaveLength(8);
    expect(eight.filter((x) => !avoid.has(x))).toHaveLength(5);
  });
});

describe('content', () => {
  it('This or That: unique ids, short labels, every deck has enough prompts', () => {
    const ids = THIS_OR_THAT_PROMPTS.map((p) => p.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const prompt of THIS_OR_THAT_PROMPTS) {
      for (const label of [prompt.a, prompt.b]) {
        expect(label.trim()).toBe(label);
        expect(label.length).toBeGreaterThanOrEqual(1);
        expect(label.length).toBeLessThanOrEqual(40);
        expect(label).not.toContain('—'); // no em-dashes in user-facing copy
      }
      expect(prompt.a).not.toBe(prompt.b);
    }
    for (const deck of DECK_IDS.filter((d) => d !== 'mix')) {
      expect(THIS_OR_THAT_PROMPTS.filter((p) => p.deck === deck).length).toBeGreaterThanOrEqual(20);
    }
  });

  it('pickPrompts: the requested deck, 7 distinct prompts, recent ones avoided', () => {
    const items = pickPrompts('us', THIS_OR_THAT_PROMPT_COUNT, new Set());
    expect(items).toHaveLength(7);
    expect(new Set(items.map((i) => i.id)).size).toBe(7);
    for (const it of items) {
      expect(it.id.startsWith('us-')).toBe(true);
      expect(it).toMatchObject({ text: null, correct: null, category: null, difficulty: null });
      expect(it.choices).toHaveLength(2);
    }
    const next = pickPrompts('us', 7, new Set(items.map((i) => i.id)));
    expect(next.some((i) => items.some((j) => j.id === i.id))).toBe(false);
    const mix = pickPrompts('mix', 7, new Set());
    expect(mix).toHaveLength(7);
  });

  it('the offline trivia set: 60+ unique questions, 4 distinct choices each, valid categories', () => {
    expect(TRIVIA_FALLBACK.length).toBeGreaterThanOrEqual(60);
    const ids = TRIVIA_FALLBACK.map((q) => q.id);
    expect(new Set(ids).size).toBe(ids.length);
    expect(new Set(TRIVIA_FALLBACK.map((q) => q.text)).size).toBe(TRIVIA_FALLBACK.length);
    for (const q of TRIVIA_FALLBACK) {
      expect(new Set([q.correct, ...q.wrong]).size).toBe(4);
      expect(q.text.length).toBeLessThanOrEqual(300);
      expect((TRIVIA_CATEGORY_IDS as readonly number[]).includes(q.categoryId)).toBe(true);
    }
  });

  it('BuiltinTrivia: honours the category when it can, avoids repeats, marks the right answer', async () => {
    const src = new BuiltinTrivia();
    const r = await src.pick({ count: 5, category: 23, difficulty: null, exclude: new Set() });
    expect(r.source).toBe('builtin');
    expect(r.category).toEqual({ id: 23, name: 'History' });
    expect(r.items).toHaveLength(5);
    for (const it of r.items) {
      expect(it.category).toBe('History');
      const q = TRIVIA_FALLBACK.find((x) => x.id === it.id)!;
      expect(it.choices[it.correct!]).toBe(q.correct);
      expect([...it.choices].sort()).toEqual([q.correct, ...q.wrong].sort());
    }
    // Television is not in the offline set: a full mixed set instead, category null.
    const tv = await src.pick({ count: 5, category: 14, difficulty: 'hard', exclude: new Set() });
    expect(tv.items).toHaveLength(5);
    expect(tv.category).toBeNull();
    // Recent questions are avoided while enough others exist.
    const avoid = new Set(
      TRIVIA_FALLBACK.filter((q) => q.categoryId === 23)
        .slice(0, 6)
        .map((q) => q.id),
    );
    const fresh = await src.pick({ count: 5, category: 23, difficulty: null, exclude: avoid });
    expect(fresh.items.some((i) => avoid.has(i.id))).toBe(false);
  });

  it('catalog: four kinds, the curated categories, the attribution and the decks', () => {
    const c = gameCatalog();
    expect(c.kinds.map((k) => k.kind)).toEqual(['rps', 'tictactoe', 'trivia', 'this_or_that']);
    expect(c.kinds.find((k) => k.kind === 'this_or_that')?.competitive).toBe(false);
    expect(c.trivia.questionCount).toBe(TRIVIA_QUESTION_COUNT);
    expect(c.trivia.categories.map((x) => x.id)).toEqual([...TRIVIA_CATEGORY_IDS]);
    expect(c.trivia.attribution).toContain('CC BY-SA 4.0');
    expect(c.thisOrThat.decks.map((d) => d.id)).toEqual([...DECK_IDS]);
    for (const k of c.kinds) expect(k.blurb).not.toContain('—');
  });

  it('"any" category resolves to a curated one', () => {
    for (let i = 0; i < TRIVIA_CATEGORY_IDS.length; i++) {
      const c = randomTriviaCategory(() => i);
      expect(c.id).toBe(TRIVIA_CATEGORY_IDS[i]);
    }
  });
});
