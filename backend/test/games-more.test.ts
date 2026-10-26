import { readFileSync } from 'node:fs';
import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { GameDoc } from '../src/db/types.js';
import { VALIDATORS } from '../src/db/validators.js';
import type { TriviaRequest, TriviaResult, TriviaSource } from '../src/services/trivia/types.js';
import { API, bearer, createTestApp, pair, register, type Session, type TestApp } from './helpers/app.js';

/**
 * Deterministic questions: the right answer is always choice 0, and every batch has new ids and
 * texts, so tests can answer right or wrong on purpose and spot any leaked text.
 */
class FakeTrivia implements TriviaSource {
  readonly calls: TriviaRequest[] = [];
  private batch = 0;
  async pick(req: TriviaRequest): Promise<TriviaResult> {
    this.calls.push(req);
    const b = ++this.batch;
    return {
      source: 'opentdb',
      // Like Open Trivia DB: "any" (null) is served from one curated category, here General knowledge.
      category:
        req.category === null ? { id: 9, name: 'General knowledge' } : { id: req.category, name: 'History' },
      items: Array.from({ length: req.count }, (_, i) => ({
        id: `fake_${b}_${i}`,
        text: `Secret question ${b}.${i}?`,
        choices: [`Right ${b}.${i}`, `Wrong A ${b}.${i}`, `Wrong B ${b}.${i}`, `Wrong C ${b}.${i}`],
        correct: 0,
        category: 'History',
        difficulty: 'easy' as const,
      })),
    };
  }
}

let t: TestApp;
let trivia: FakeTrivia;
let a: Session;
let b: Session;
let coupleId: ObjectId;
/** Copies of real documents at interesting moments, inserted under the frozen live validator at the end. */
const snapshots: GameDoc[] = [];

beforeAll(async () => {
  trivia = new FakeTrivia();
  t = await createTestApp({ trivia, envOverrides: { RATE_LIMIT_GAME_WRITES_PER_MIN: '1000' } });
  a = await register(t.app, 'Ari');
  b = await register(t.app, 'Bea');
  coupleId = new ObjectId(await pair(t.app, a, b));
});
afterAll(async () => {
  await t.close();
});

const post = (s: Session, url: string, payload?: Record<string, unknown>) =>
  t.app.inject({ method: 'POST', url: `${API}${url}`, headers: bearer(s), ...(payload ? { payload } : {}) });
const get = (s: Session, url: string) =>
  t.app.inject({ method: 'GET', url: `${API}${url}`, headers: bearer(s) });
const view = async (s: Session, id: string) => (await get(s, `/games/${id}`)).json().game;
const lastEvent = () => t.db.events.findOne({ type: 'game.updated', coupleId }, { sort: { seq: -1 } });
const raw = (id: string) => t.db.games.findOne({ _id: new ObjectId(id) });
async function snap(id: string) {
  const doc = await raw(id);
  snapshots.push({ ...doc!, _id: new ObjectId() });
}
const ttt = (s: Session, id: string, cell: number) =>
  post(s, `/games/${id}/play`, { kind: 'tictactoe', cell });
const answer = (s: Session, id: string, item: number, choice: number) =>
  post(s, `/games/${id}/play`, { kind: 'trivia', item, choice });
async function cancelOpen() {
  const open = await t.db.games.findOne({ coupleId, open: true });
  if (open) expect((await post(a, `/games/${open._id}/cancel`)).statusCode).toBe(200);
}

describe('catalog and create validation', () => {
  it('GET /games/catalog lists the four games and their options', async () => {
    const res = await get(a, '/games/catalog');
    expect(res.statusCode).toBe(200);
    const c = res.json();
    expect(c.kinds.map((k: { kind: string }) => k.kind)).toEqual([
      'rps',
      'tictactoe',
      'trivia',
      'this_or_that',
    ]);
    expect(c.trivia.categories).toHaveLength(10);
    expect(c.trivia.attribution).toBe(
      'Questions from Open Trivia Database (opentdb.com), CC BY-SA 4.0. Category names shortened.',
    );
    expect(c.trivia.attributionUrl).toBe('https://opentdb.com');
    expect(c.trivia.licenseUrl).toBe('https://creativecommons.org/licenses/by-sa/4.0/');
    expect(c.thisOrThat.promptCount).toBe(7);
  });

  it('rejects unknown kinds, options on the wrong kind, an explicit default prize and extra keys', async () => {
    for (const body of [
      { kind: 'chess' },
      {},
      { kind: 'trivia', category: 24 },
      { kind: 'trivia', difficulty: 'insane' },
      { kind: 'this_or_that', prize: 'none' },
      { kind: 'this_or_that', deck: 'spicy' },
      { kind: 'rps', deck: 'us' },
      { kind: 'tictactoe', prize: 'truth_or_dare' },
      { kind: 'tictactoe', extra: 1 },
    ]) {
      const res = await post(a, '/games', body);
      expect(res.statusCode, JSON.stringify(body)).toBe(400);
    }
    expect(await t.db.games.countDocuments({ coupleId })).toBe(0);
  });

  it('{kind:"rps"} from older apps still works; the DTO says the prize is the truth or dare', async () => {
    const res = await post(a, '/games', { kind: 'rps' });
    expect(res.statusCode).toBe(201);
    expect(res.json().game).toMatchObject({ kind: 'rps', prize: 'truth_or_dare', ttt: null, quiz: null });
    await cancelOpen();
  });
});

describe('tic-tac-toe', () => {
  let id: string;

  it('the invitee plays X and moves first; every required field is written', async () => {
    const res = await post(a, '/games', { kind: 'tictactoe' });
    expect(res.statusCode).toBe(201);
    const g = res.json().game;
    id = g.id;
    expect(g).toMatchObject({
      kind: 'tictactoe',
      status: 'waiting',
      prize: 'truth_or_dare',
      quiz: null,
      round: 1,
    });
    expect(g.ttt).toEqual({
      board: [null, null, null, null, null, null, null, null, null],
      xId: b.user.id,
      turnId: b.user.id,
      lastCell: null,
      line: null,
      moveCount: 0,
      myMark: 'o',
      myTurn: false,
    });
    expect((await view(b, id)).ttt).toMatchObject({ myMark: 'x', myTurn: true });
    const doc = await raw(id);
    expect(doc).toMatchObject({
      moves: [],
      challenge: null,
      answer: null,
      winnerId: null,
      round: 1,
      open: true,
    });
    const ev = await lastEvent();
    expect(ev!.payload.action).toBe('created');
    expect((ev!.payload.game as { ttt: unknown }).ttt).toEqual({
      board: Array(9).fill(null),
      xId: b.user.id,
      turnId: b.user.id,
      lastCell: null,
      line: null,
      moveCount: 0,
    });
    await snap(id);
  });

  it('one open game per couple, across kinds', async () => {
    const res = await post(b, '/games', { kind: 'trivia' });
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('game_in_progress');
    expect(res.json().error.details.game).toMatchObject({ id, kind: 'tictactoe' });
    expect(trivia.calls).toHaveLength(0); // the fast check runs before fetching any questions
  });

  it('turn, cell and kind checks', async () => {
    const notYours = await ttt(a, id, 0);
    expect(notYours.statusCode).toBe(409);
    expect(notYours.json().error.code).toBe('not_your_turn');
    expect(notYours.json().error.details.game.id).toBe(id);

    const first = await ttt(b, id, 4);
    expect(first.statusCode).toBe(200);
    expect(first.json().game.ttt).toMatchObject({
      myTurn: false,
      lastCell: 4,
      moveCount: 1,
      turnId: a.user.id,
    });
    expect(first.json().game.ttt.board[4]).toBe('x');
    expect((await lastEvent())!.payload.action).toBe('moved');

    const taken = await ttt(a, id, 4);
    expect(taken.statusCode).toBe(409);
    expect(taken.json().error.code).toBe('invalid_move');

    expect((await ttt(a, id, 9)).statusCode).toBe(400);
    const mismatch = await post(a, `/games/${id}/play`, { kind: 'trivia', item: 0, choice: 0 });
    expect(mismatch.statusCode).toBe(400);
    expect(mismatch.json().error.details[0].path).toBe('/kind');
    const oldApp = await post(a, `/games/${id}/move`, { move: 'rock' });
    expect(oldApp.statusCode).toBe(400);
    expect(oldApp.json().error.message).toBe('Update Risaina to play this game');
  });

  it('three in a row wins: revealed, line set, turn over; later moves are refused', async () => {
    expect((await ttt(a, id, 0)).statusCode).toBe(200);
    expect((await ttt(b, id, 2)).statusCode).toBe(200);
    expect((await ttt(a, id, 1)).statusCode).toBe(200);
    const win = await ttt(b, id, 6);
    expect(win.statusCode).toBe(200);
    expect(win.json().game).toMatchObject({ status: 'revealed', winnerId: b.user.id, loserId: a.user.id });
    expect(win.json().game.ttt).toMatchObject({ line: [2, 4, 6], turnId: null, myTurn: false, moveCount: 5 });
    expect((await lastEvent())!.payload.action).toBe('revealed');
    await snap(id);

    const late = await ttt(a, id, 3);
    expect(late.statusCode).toBe(409);
    expect(late.json().error.code).toBe('invalid_game_state');
    // An old app tapping a move on a finished tic-tac-toe game is told to update (kind before status).
    expect((await post(a, `/games/${id}/move`, { move: 'rock' })).statusCode).toBe(400);
  });

  it('the winner gets the same blind truth or dare', async () => {
    expect((await post(a, `/games/${id}/challenge`, { truth: 'T?', dare: 'D!' })).statusCode).toBe(403);
    expect((await post(b, `/games/${id}/challenge`, { truth: 'T?', dare: 'D!' })).statusCode).toBe(200);
    await snap(id);
    expect((await post(a, `/games/${id}/choose`, { kind: 'dare' })).statusCode).toBe(200);
    expect((await post(a, `/games/${id}/complete`)).statusCode).toBe(200);
    const done = await post(b, `/games/${id}/confirm`);
    expect(done.statusCode).toBe(200);
    expect(done.json().game).toMatchObject({ status: 'completed', winnerId: b.user.id });
    await snap(id);
  });

  it('two concurrent taps by the same player: exactly one lands', async () => {
    const g = (await post(b, '/games', { kind: 'tictactoe' })).json().game; // A is invited: A plays X
    expect(g.ttt.xId).toBe(a.user.id);
    const [r1, r2] = await Promise.all([ttt(a, g.id, 0), ttt(a, g.id, 1)]);
    expect([r1.statusCode, r2.statusCode].sort()).toEqual([200, 409]);
    const after = await raw(g.id);
    expect(after!.ttt!.moveCount).toBe(1);
    expect(after!.ttt!.board.filter((c) => c !== null)).toHaveLength(1);
    await cancelOpen();
  });

  it('a full board is a draw; rematch swaps X; /end calls the next draw off', async () => {
    const g = (await post(a, '/games', { kind: 'tictactoe' })).json().game;
    expect((await post(a, `/games/${g.id}/end`)).statusCode).toBe(409); // only after a draw
    // X = B: 0, 2, 3, 7, 8; O = A: 1, 4, 5, 6.
    for (const [s, cell] of [
      [b, 0],
      [a, 1],
      [b, 2],
      [a, 4],
      [b, 3],
      [a, 5],
      [b, 7],
      [a, 6],
    ] as const) {
      expect((await ttt(s, g.id, cell)).statusCode).toBe(200);
    }
    const draw = await ttt(b, g.id, 8);
    expect(draw.json().game).toMatchObject({ status: 'draw', winnerId: null });
    expect(draw.json().game.ttt.turnId).toBeNull();
    expect((await lastEvent())!.payload.action).toBe('draw');
    await snap(g.id);

    const re = await post(a, `/games/${g.id}/rematch`);
    expect(re.statusCode).toBe(200);
    expect(re.json().game).toMatchObject({ status: 'waiting', round: 2 });
    expect(re.json().game.ttt).toMatchObject({
      xId: a.user.id,
      turnId: a.user.id,
      moveCount: 0,
      myTurn: true,
    });
    expect(re.json().game.ttt.board.every((c: unknown) => c === null)).toBe(true);

    // Now X = A.
    for (const [s, cell] of [
      [a, 0],
      [b, 1],
      [a, 2],
      [b, 4],
      [a, 3],
      [b, 5],
      [a, 7],
      [b, 6],
      [a, 8],
    ] as const) {
      expect((await ttt(s, g.id, cell)).statusCode).toBe(200);
    }
    const ended = await post(b, `/games/${g.id}/end`);
    expect(ended.statusCode).toBe(200);
    expect(ended.json().game).toMatchObject({ status: 'completed', winnerId: null, loserId: null });
    expect((await raw(g.id))!.open).toBe(false);
    expect((await lastEvent())!.payload.action).toBe('ended');
    await snap(g.id);
  });

  it('"just for fun": a win completes the game straight away, with no truth or dare', async () => {
    const g = (await post(a, '/games', { kind: 'tictactoe', prize: 'none' })).json().game;
    expect(g.prize).toBe('none');
    for (const [s, cell] of [
      [b, 0],
      [a, 3],
      [b, 1],
      [a, 4],
    ] as const) {
      expect((await ttt(s, g.id, cell)).statusCode).toBe(200);
    }
    const win = await ttt(b, g.id, 2);
    expect(win.json().game).toMatchObject({ status: 'completed', winnerId: b.user.id, challenge: null });
    expect((await raw(g.id))!.open).toBe(false);
    expect((await post(b, `/games/${g.id}/challenge`, { truth: 'T?', dare: 'D!' })).statusCode).toBe(409);
    await snap(g.id);
  });
});

describe('trivia duel', () => {
  let id: string;
  const leaks = (json: string, batch: number, from: number) =>
    Array.from({ length: 5 - from }, (_, k) => k + from).some(
      (i) => json.includes(`Secret question ${batch}.${i}?`) || json.includes(`Right ${batch}.${i}`),
    );

  it('fetches 5 questions with the chosen options; only the first one is visible, with no answer', async () => {
    const res = await post(a, '/games', { kind: 'trivia', category: 23, difficulty: 'easy' });
    expect(res.statusCode).toBe(201);
    const g = res.json().game;
    id = g.id;
    expect(trivia.calls.at(-1)).toMatchObject({ count: 5, category: 23, difficulty: 'easy' });
    expect(g.quiz).toMatchObject({
      source: 'opentdb',
      attribution:
        'Questions from Open Trivia Database (opentdb.com), CC BY-SA 4.0. Category names shortened.',
      category: { id: 23, name: 'History' },
      requestedCategoryId: 23,
      difficulty: 'easy',
      total: 5,
      myAnswered: 0,
      partnerAnswered: 0,
      myScore: 0,
      partnerScore: null,
      matches: null,
    });
    expect(g.quiz.items).toEqual([
      {
        index: 0,
        text: 'Secret question 1.0?',
        choices: ['Right 1.0', 'Wrong A 1.0', 'Wrong B 1.0', 'Wrong C 1.0'],
        category: 'History',
        difficulty: 'easy',
        myChoice: null,
        correct: null,
        partnerChoice: null,
      },
    ]);
    expect(leaks(JSON.stringify(g), 1, 1)).toBe(false);
    const ev = await lastEvent();
    expect(JSON.stringify(ev!.payload)).not.toContain('Secret');
    expect(JSON.stringify(ev!.payload)).not.toContain('Right');
    expect((ev!.payload.game as { quiz: unknown }).quiz).toEqual({
      source: 'opentdb',
      categoryName: 'History',
      deck: null,
      total: 5,
      progress: [
        { userId: a.user.id, answered: 0, finished: false },
        { userId: b.user.id, answered: 0, finished: false },
      ],
      scores: null,
      matches: null,
    });
    const doc = await raw(id);
    expect(doc).toMatchObject({ moves: [], challenge: null, ttt: null, round: 1 });
    await snap(id);
  });

  it('answers are sequential and final; the answer and the next question appear only after answering', async () => {
    const r0 = await answer(a, id, 0, 0);
    expect(r0.statusCode).toBe(200);
    expect((await lastEvent())!.payload.action).toBe('answered');
    const mine = r0.json().game.quiz;
    expect(mine.items).toHaveLength(2);
    expect(mine.items[0]).toMatchObject({ myChoice: 0, correct: 0 });
    expect(mine.items[1]).toMatchObject({ myChoice: null, correct: null });
    expect(mine.myScore).toBe(1);

    const skip = await answer(a, id, 2, 0);
    expect(skip.statusCode).toBe(409);
    expect(skip.json().error.code).toBe('invalid_move');
    const again = await answer(a, id, 0, 1);
    expect(again.statusCode).toBe(409);
    expect(again.json().error.code).toBe('already_moved');
    expect((await answer(a, id, 1, 4)).statusCode).toBe(400);

    // The partner sees progress only: still just their own first question, no picks, no score.
    const theirs = (await view(b, id)).quiz;
    expect(theirs).toMatchObject({
      partnerAnswered: 1,
      partnerFinished: false,
      partnerScore: null,
      myAnswered: 0,
    });
    expect(theirs.items).toHaveLength(1);
    expect(theirs.items[0]).toMatchObject({ myChoice: null, correct: null, partnerChoice: null });
    expect(leaks(JSON.stringify(theirs), 1, 1)).toBe(false);
    await snap(id);
  });

  it('finishing: action finished; nothing about my answers reaches the partner before they finish', async () => {
    for (const [item, choice] of [
      [1, 0],
      [2, 0],
      [3, 1],
    ]) {
      expect((await answer(a, id, item!, choice!)).statusCode).toBe(200);
    }
    const last = await answer(a, id, 4, 0);
    expect(last.statusCode).toBe(200);
    expect((await lastEvent())!.payload.action).toBe('finished');
    const mine = last.json().game.quiz;
    expect(mine).toMatchObject({ myFinished: true, myAnswered: 5, myScore: 4, partnerScore: null });
    expect(mine.items).toHaveLength(5);
    expect(mine.items.every((i: { partnerChoice: unknown }) => i.partnerChoice === null)).toBe(true);
    await snap(id);

    const theirs = await get(b, `/games/${id}`);
    expect(theirs.json().game.quiz).toMatchObject({
      partnerFinished: true,
      partnerAnswered: 5,
      partnerScore: null,
    });
    expect(leaks(theirs.body, 1, 1)).toBe(false);
    expect(
      JSON.stringify(theirs.json().game.quiz.items.map((i: { partnerChoice: unknown }) => i.partnerChoice)),
    ).toBe('[null]');
    const events = await get(b, '/events?since=0&limit=200');
    expect(events.body).not.toContain('Secret question');
    expect(events.body).not.toContain('Right 1.');
    const exported = await get(b, '/me/export');
    expect(leaks(exported.body, 1, 1)).toBe(false);
    const list = await get(b, '/games');
    expect(leaks(list.body, 1, 1)).toBe(false);
  });

  it('both done: the higher score wins the truth or dare; everything is revealed to both', async () => {
    for (const [item, choice] of [
      [0, 0],
      [1, 1],
      [2, 2],
      [3, 0],
    ]) {
      expect((await answer(b, id, item!, choice!)).statusCode).toBe(200);
    }
    const last = await answer(b, id, 4, 0);
    expect(last.statusCode).toBe(200);
    expect(last.json().game).toMatchObject({ status: 'revealed', winnerId: a.user.id, loserId: b.user.id });
    expect((await lastEvent())!.payload.action).toBe('revealed');
    const ev = (await lastEvent())!.payload.game as { quiz: { scores: unknown } };
    expect(ev.quiz.scores).toEqual([
      { userId: a.user.id, score: 4 },
      { userId: b.user.id, score: 3 },
    ]);
    const bq = last.json().game.quiz;
    expect(bq).toMatchObject({ myScore: 3, partnerScore: 4 });
    expect(bq.items.map((i: { partnerChoice: number }) => i.partnerChoice)).toEqual([0, 0, 0, 1, 0]);
    expect(bq.items.map((i: { correct: number }) => i.correct)).toEqual([0, 0, 0, 0, 0]);
    expect((await view(a, id)).quiz.items.map((i: { partnerChoice: number }) => i.partnerChoice)).toEqual([
      0, 1, 2, 0, 0,
    ]);
    await snap(id);
    await cancelOpen();
    await snap(id);
  });

  it('a tie is a draw; rematch brings 5 new questions and resets both players', async () => {
    const g = (await post(b, '/games', { kind: 'trivia' })).json().game;
    expect(trivia.calls.at(-1)).toMatchObject({ category: null, difficulty: null });
    // "Any" was served from one category, but it stays "any" for a rematch and for "Play again".
    expect(g.quiz).toMatchObject({ category: { id: 9 }, requestedCategoryId: null });
    const firstIds = (await raw(g.id))!.quiz!.items.map((i) => i.id);
    expect(trivia.calls.at(-1)!.exclude.has('fake_1_0')).toBe(true); // recent duel's questions avoided
    for (const s of [a, b])
      for (let i = 0; i < 5; i++) expect((await answer(s, g.id, i, 0)).statusCode).toBe(200);
    const after = await view(a, g.id);
    expect(after).toMatchObject({ status: 'draw', winnerId: null });
    expect(after.quiz).toMatchObject({ myScore: 5, partnerScore: 5 });
    await snap(g.id);

    const re = await post(b, `/games/${g.id}/rematch`);
    expect(re.statusCode).toBe(200);
    expect(re.json().game).toMatchObject({ status: 'waiting', round: 2 });
    expect(re.json().game.quiz).toMatchObject({
      myAnswered: 0,
      partnerAnswered: 0,
      myScore: 0,
      requestedCategoryId: null,
    });
    expect(trivia.calls.at(-1)).toMatchObject({ category: null, difficulty: null });
    const newIds = (await raw(g.id))!.quiz!.items.map((i) => i.id);
    expect(newIds.some((x) => firstIds.includes(x))).toBe(false);
    for (const x of firstIds) expect(trivia.calls.at(-1)!.exclude.has(x)).toBe(true);
    await snap(g.id);
    await cancelOpen();
  });

  it('cancelling midway never reveals the partner’s picks or score to either player', async () => {
    const g = (await post(a, '/games', { kind: 'trivia', prize: 'none' })).json().game;
    for (let i = 0; i < 5; i++) await answer(a, g.id, i, 0);
    await answer(b, g.id, 0, 1);
    await answer(b, g.id, 1, 0);
    expect((await post(b, `/games/${g.id}/cancel`)).statusCode).toBe(200);
    const bq = (await view(b, g.id)).quiz;
    expect(bq.items).toHaveLength(2); // only what B answered
    expect(bq).toMatchObject({ partnerScore: null, matches: null });
    expect(bq.items.every((i: { partnerChoice: unknown }) => i.partnerChoice === null)).toBe(true);
    const aq = (await view(a, g.id)).quiz;
    expect(aq.items.every((i: { partnerChoice: unknown }) => i.partnerChoice === null)).toBe(true);
    expect(aq.partnerScore).toBeNull();
    await snap(g.id);
  });

  it('"just for fun" trivia: the winner is decided and the game completes', async () => {
    const g = (await post(a, '/games', { kind: 'trivia', prize: 'none' })).json().game;
    for (let i = 0; i < 5; i++) await answer(a, g.id, i, 0);
    for (let i = 0; i < 5; i++) await answer(b, g.id, i, 1);
    const end = await view(b, g.id);
    expect(end).toMatchObject({ status: 'completed', winnerId: a.user.id, prize: 'none' });
    expect((await raw(g.id))!.open).toBe(false);
  });
});

describe('this or that', () => {
  let id: string;
  let deck: string;

  it('7 prompts from the chosen deck, all visible from the start, no prize', async () => {
    const res = await post(b, '/games', { kind: 'this_or_that', deck: 'us' });
    expect(res.statusCode).toBe(201);
    const g = res.json().game;
    id = g.id;
    deck = g.quiz.deck;
    expect(g).toMatchObject({ prize: 'none', status: 'waiting', ttt: null });
    expect(g.quiz).toMatchObject({
      source: 'curated',
      attribution: null,
      deck: 'us',
      total: 7,
      myScore: null,
    });
    expect(g.quiz.items).toHaveLength(7);
    for (const it of g.quiz.items) {
      expect(it).toMatchObject({ text: null, correct: null, myChoice: null, partnerChoice: null });
      expect(it.choices).toHaveLength(2);
    }
    expect((await raw(id))!.quiz!.items.every((i) => i.id.startsWith('us-'))).toBe(true);
    await snap(id);
  });

  it('all picks in one go; the wrong count is a 400; picks stay sealed until both finished', async () => {
    const bad = await post(a, `/games/${id}/play`, { kind: 'this_or_that', picks: [0, 1, 0] });
    expect(bad.statusCode).toBe(400);
    expect(
      (await post(a, `/games/${id}/play`, { kind: 'this_or_that', picks: [0, 1, 2, 0, 1, 0, 1] })).statusCode,
    ).toBe(400);

    const picks = [0, 1, 1, 0, 0, 1, 0];
    const mine = await post(a, `/games/${id}/play`, { kind: 'this_or_that', picks });
    expect(mine.statusCode).toBe(200);
    expect((await lastEvent())!.payload.action).toBe('finished');
    expect(mine.json().game.quiz.items.map((i: { myChoice: number }) => i.myChoice)).toEqual(picks);
    const ev = (await lastEvent())!.payload.game as { quiz: { progress: unknown[]; matches: unknown } };
    expect(JSON.stringify(ev)).not.toContain('picks');
    expect(ev.quiz.matches).toBeNull();
    await snap(id);

    const again = await post(a, `/games/${id}/play`, { kind: 'this_or_that', picks });
    expect(again.statusCode).toBe(409);
    expect(again.json().error.code).toBe('already_moved');

    const theirs = (await view(b, id)).quiz;
    expect(theirs).toMatchObject({ partnerFinished: true, partnerAnswered: 7, matches: null });
    expect(theirs.items.every((i: { partnerChoice: unknown }) => i.partnerChoice === null)).toBe(true);
  });

  it('both done: completed with the match count and both sides shown; no rematch, no prize', async () => {
    const res = await post(b, `/games/${id}/play`, { kind: 'this_or_that', picks: [0, 1, 0, 0, 1, 1, 1] });
    expect(res.statusCode).toBe(200);
    expect(res.json().game).toMatchObject({ status: 'completed', winnerId: null, loserId: null });
    expect(res.json().game.quiz).toMatchObject({ matches: 4, partnerFinished: true });
    expect(res.json().game.quiz.items.map((i: { partnerChoice: number }) => i.partnerChoice)).toEqual([
      0, 1, 1, 0, 0, 1, 0,
    ]);
    expect((await lastEvent())!.payload.action).toBe('matched');
    expect(((await lastEvent())!.payload.game as { quiz: { matches: number } }).quiz.matches).toBe(4);
    expect((await raw(id))!.open).toBe(false);
    expect((await post(a, `/games/${id}/rematch`)).statusCode).toBe(409);
    expect((await post(a, `/games/${id}/challenge`, { truth: 'T?', dare: 'D!' })).statusCode).toBe(409);
    await snap(id);
  });

  it('"Play again" from a finished This or That card is a valid create, with fresh prompts', async () => {
    // What the app sends when replaying: the same kind and deck, never a prize.
    const res = await post(a, '/games', { kind: 'this_or_that', deck });
    expect(res.statusCode).toBe(201);
    const before = new Set((await raw(id))!.quiz!.items.map((i) => i.id));
    const now = (await raw(res.json().game.id))!.quiz!.items.map((i) => i.id);
    expect(now.some((x) => before.has(x))).toBe(false);
    await cancelOpen();
  });
});

describe('one open game per couple under races', () => {
  it('concurrent creates of different kinds by both players: exactly one 201', async () => {
    await cancelOpen();
    const tries: [Session, Record<string, unknown>][] = [
      [a, { kind: 'tictactoe' }],
      [b, { kind: 'trivia' }],
      [a, { kind: 'this_or_that' }],
      [b, { kind: 'rps' }],
    ];
    const res = await Promise.all(tries.map(([s, body]) => post(s, '/games', body)));
    const created = res.filter((r) => r.statusCode === 201);
    expect(created).toHaveLength(1);
    const winner = created[0]!.json().game;
    for (const r of res.filter((x) => x.statusCode !== 201)) {
      expect(r.statusCode).toBe(409);
      expect(r.json().error.code).toBe('game_in_progress');
    }
    expect(await t.db.games.countDocuments({ coupleId, open: true })).toBe(1);
    expect((await t.db.games.findOne({ coupleId, open: true }))!._id.toHexString()).toBe(winner.id);
    await cancelOpen();
  });

  it('/play and /end draw on the same per-user write budget as creating a game', async () => {
    const t2 = await createTestApp({ envOverrides: { RATE_LIMIT_GAME_WRITES_PER_MIN: '2' } });
    try {
      const x = await register(t2.app, 'Xo');
      const y = await register(t2.app, 'Yo');
      await pair(t2.app, x, y);
      const p = (s: Session, url: string, payload?: Record<string, unknown>) =>
        t2.app.inject({
          method: 'POST',
          url: `${API}${url}`,
          headers: bearer(s),
          ...(payload ? { payload } : {}),
        });
      const g = await p(x, '/games', { kind: 'tictactoe' }); // x: 1 of 2
      expect(g.statusCode).toBe(201);
      const id = g.json().game.id;
      // y plays X and moves first, so x's move is refused, but it still counts: x: 2 of 2.
      expect((await p(x, `/games/${id}/play`, { kind: 'tictactoe', cell: 0 })).json().error.code).toBe(
        'not_your_turn',
      );
      const xEnd = await p(x, `/games/${id}/end`);
      expect(xEnd.statusCode).toBe(429);
      expect(xEnd.json().error.code).toBe('rate_limited');
      // y has their own budget: /play and /end use it up the same way.
      expect((await p(y, `/games/${id}/play`, { kind: 'tictactoe', cell: 4 })).statusCode).toBe(200);
      expect((await p(y, `/games/${id}/end`)).json().error.code).toBe('invalid_game_state');
      const yPlay = await p(y, `/games/${id}/play`, { kind: 'tictactoe', cell: 0 });
      expect(yPlay.statusCode).toBe(429);
      expect(yPlay.headers['retry-after']).toBeDefined();
    } finally {
      await t2.close();
    }
  });
});

describe('frozen live validator', () => {
  it('the deployed validator only gains optional fields: required and existing properties are unchanged', () => {
    const live = JSON.parse(
      readFileSync(new URL('./fixtures/games-validator.live.json', import.meta.url), 'utf8'),
    );
    const current = VALIDATORS.games as {
      $jsonSchema: { required: string[]; properties: Record<string, unknown> };
    };
    expect(current.$jsonSchema.required).toEqual(live.$jsonSchema.required);
    for (const [key, schema] of Object.entries(live.$jsonSchema.properties)) {
      expect(current.$jsonSchema.properties[key], key).toEqual(schema);
    }
  });

  it('every document the new games write is accepted by the validator production still has', async () => {
    const live = JSON.parse(
      readFileSync(new URL('./fixtures/games-validator.live.json', import.meta.url), 'utf8'),
    );
    await t.ctx.mongoDb.createCollection('games_frozen', {
      validator: live,
      validationLevel: 'strict',
      validationAction: 'error',
    });
    const frozen = t.ctx.mongoDb.collection('games_frozen');
    const all = [...snapshots, ...(await t.db.games.find({ coupleId }).toArray())];
    const kinds = new Set(all.map((d) => `${d.kind}:${d.status}`));
    for (const k of [
      'tictactoe:waiting',
      'tictactoe:revealed',
      'tictactoe:challenged',
      'tictactoe:draw',
      'tictactoe:completed',
      'trivia:waiting',
      'trivia:revealed',
      'trivia:draw',
      'trivia:cancelled',
      'trivia:completed',
      'this_or_that:waiting',
      'this_or_that:completed',
    ]) {
      expect(kinds.has(k), k).toBe(true);
    }
    for (const doc of all) {
      await expect(
        frozen.insertOne({ ...doc, _id: new ObjectId() }),
        `${doc.kind}:${doc.status}`,
      ).resolves.toBeTruthy();
    }
    // Sanity: the frozen validator does reject a document missing a required field.
    const { moves: _moves, ...missing } = all[0]!;
    await expect(frozen.insertOne({ ...missing, _id: new ObjectId() })).rejects.toThrow();
  });
});
