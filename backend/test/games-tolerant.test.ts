import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { GameDoc } from '../src/db/types.js';
import { API, bearer, createTestApp, pair, register, type Session, type TestApp } from './helpers/app.js';

/**
 * Rollback safety: responses are serialized through the Zod response schemas, so a stored game
 * kind this build does not know must never turn GET /games, GET /games/:id or /me/export into a
 * 500 (which is what an enum in the response schema did).
 */
let t: TestApp;
let a: Session;
let b: Session;
let coupleId: ObjectId;

beforeAll(async () => {
  t = await createTestApp({ envOverrides: { RATE_LIMIT_GAME_WRITES_PER_MIN: '1000' } });
  a = await register(t.app, 'Ari');
  b = await register(t.app, 'Bea');
  coupleId = new ObjectId(await pair(t.app, a, b));
});
afterAll(async () => {
  await t.close();
});

describe('games: tolerant reader for unknown kinds', () => {
  it('lists, gets and exports a game of an unknown kind; rps moves on it get 400 "update"', async () => {
    const now = new Date();
    const id = new ObjectId();
    const doc = {
      _id: id,
      coupleId,
      kind: 'from_the_future',
      createdBy: new ObjectId(a.user.id),
      memberIds: [new ObjectId(a.user.id), new ObjectId(b.user.id)],
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
    } as unknown as GameDoc;
    await t.db.games.insertOne(doc);

    const list = await t.app.inject({ method: 'GET', url: `${API}/games`, headers: bearer(a) });
    expect(list.statusCode).toBe(200);
    expect(list.json().items[0]).toMatchObject({ id: id.toHexString(), kind: 'from_the_future' });

    const one = await t.app.inject({ method: 'GET', url: `${API}/games/${id}`, headers: bearer(b) });
    expect(one.statusCode).toBe(200);

    const exp = await t.app.inject({ method: 'GET', url: `${API}/me/export`, headers: bearer(a) });
    expect(exp.statusCode).toBe(200);

    // The kind check comes before the status check: an old app always learns it must update.
    const move = await t.app.inject({
      method: 'POST',
      url: `${API}/games/${id}/move`,
      headers: bearer(a),
      payload: { move: 'rock' },
    });
    expect(move.statusCode).toBe(400);
    expect(move.json().error).toMatchObject({
      code: 'validation_failed',
      message: 'Update Risaina to play this game',
    });
    expect(move.json().error.details[0].path).toBe('/kind');

    const cancel = await t.app.inject({
      method: 'POST',
      url: `${API}/games/${id}/cancel`,
      headers: bearer(a),
    });
    expect(cancel.statusCode).toBe(200);
    expect(cancel.json().game.status).toBe('cancelled');
  });
});
