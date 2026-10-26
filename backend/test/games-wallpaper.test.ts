import { randomUUID } from 'node:crypto';
import { ObjectId } from 'mongodb';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  API,
  bearer,
  createTestApp,
  FakePushService,
  pair,
  register,
  type Session,
  type TestApp,
  uploadPhoto,
} from './helpers/app.js';

let t: TestApp;
let push: FakePushService;
let a: Session;
let b: Session;
let c: Session;
let d: Session;

beforeAll(async () => {
  push = new FakePushService();
  t = await createTestApp({ push, envOverrides: { RATE_LIMIT_GAME_WRITES_PER_MIN: '1000' } });
  a = await register(t.app, 'Ari');
  b = await register(t.app, 'Bea');
  c = await register(t.app, 'Cyd');
  d = await register(t.app, 'Dax');
  abCoupleId = new ObjectId(await pair(t.app, a, b));
  await pair(t.app, c, d);
  for (const [s, token] of [
    [a, 'tok-a'],
    [b, 'tok-b'],
  ] as const) {
    const res = await t.app.inject({
      method: 'PUT',
      url: `${API}/devices`,
      headers: bearer(s),
      payload: { deviceId: `dev-${token}`, platform: 'android', token, appVersion: '1.0.0', model: 'Pixel' },
    });
    expect(res.statusCode).toBe(204);
  }
  await t.outbox.runOnce();
  push.calls.length = 0;
});
afterAll(async () => {
  await t.close();
});

const post = (s: Session, url: string, payload?: Record<string, unknown>) =>
  t.app.inject({ method: 'POST', url: `${API}${url}`, headers: bearer(s), ...(payload ? { payload } : {}) });
const get = (s: Session, url: string) =>
  t.app.inject({ method: 'GET', url: `${API}${url}`, headers: bearer(s) });
let abCoupleId: ObjectId;
/** Latest game.updated of the A/B couple (seq is per couple). */
const lastGameEvent = () =>
  t.db.events.findOne({ type: 'game.updated', coupleId: abCoupleId }, { sort: { seq: -1 } });

describe('rock–paper–scissors with truth or dare', () => {
  let gameId: string;

  it('creates a game (viewer-specific shape) and emits game.updated', async () => {
    const res = await post(a, '/games', { kind: 'rps' });
    expect(res.statusCode).toBe(201);
    const game = res.json().game;
    gameId = game.id;
    expect(game).toEqual({
      id: expect.any(String),
      coupleId: expect.any(String),
      kind: 'rps',
      createdBy: a.user.id,
      createdAt: expect.any(String),
      updatedAt: expect.any(String),
      status: 'waiting',
      myMove: null,
      partnerMoved: false,
      partnerMove: null,
      winnerId: null,
      loserId: null,
      challenge: null,
      answer: null,
      completedAt: null,
      round: 1,
    });
    const ev = await lastGameEvent();
    expect(ev!.actorId.toHexString()).toBe(a.user.id);
    expect(ev!.payload).toEqual({
      action: 'created',
      game: {
        id: gameId,
        coupleId: game.coupleId,
        kind: 'rps',
        createdBy: a.user.id,
        createdAt: game.createdAt,
        updatedAt: game.updatedAt,
        status: 'waiting',
        movedBy: [],
        moves: null,
        winnerId: null,
        loserId: null,
        challenge: null,
        completedAt: null,
        round: 1,
      },
    });
    expect(ev!.payload.game).not.toHaveProperty('answer');
  });

  it('only one open game per couple: 409 game_in_progress with details.game', async () => {
    const res = await post(b, '/games', { kind: 'rps' });
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('game_in_progress');
    expect(res.json().error.details.game.id).toBe(gameId);
    // the other couple is independent
    const other = await post(c, '/games', { kind: 'rps' });
    expect(other.statusCode).toBe(201);
    expect((await post(c, `/games/${other.json().game.id}/cancel`)).statusCode).toBe(200);
  });

  it('validates bodies', async () => {
    expect((await post(a, '/games', { kind: 'chess' })).statusCode).toBe(400);
    expect((await post(a, '/games', {})).statusCode).toBe(400);
    expect((await post(a, `/games/${gameId}/move`, { move: 'lizard' })).statusCode).toBe(400);
    expect((await post(a, `/games/${gameId}/move`, { move: 'rock', extra: 1 })).statusCode).toBe(400);
  });

  it('first move is hidden from the partner in GET and in the event', async () => {
    const res = await post(a, `/games/${gameId}/move`, { move: 'rock' });
    expect(res.statusCode).toBe(200);
    expect(res.json().game).toMatchObject({
      status: 'waiting',
      myMove: 'rock',
      partnerMoved: false,
      partnerMove: null,
    });

    const forB = (await get(b, `/games/${gameId}`)).json().game;
    expect(forB).toMatchObject({ status: 'waiting', myMove: null, partnerMoved: true, partnerMove: null });
    expect(JSON.stringify(forB)).not.toContain('rock');

    const ev = await lastGameEvent();
    expect(ev!.payload.action).toBe('moved');
    expect(ev!.payload.game).toMatchObject({ movedBy: [a.user.id], moves: null, status: 'waiting' });
    expect(JSON.stringify(ev!.payload)).not.toContain('rock');

    // also through GET /events (what WS replay / sync serve)
    const events = await get(b, '/events?since=0&limit=100');
    expect(JSON.stringify(events.json())).not.toContain('rock');
  });

  it('double move → 409 already_moved', async () => {
    const res = await post(a, `/games/${gameId}/move`, { move: 'paper' });
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('already_moved');
    expect(res.json().error.details.game.myMove).toBe('rock');
  });

  it('third user (other couple) gets 404 everywhere', async () => {
    expect((await get(c, `/games/${gameId}`)).statusCode).toBe(404);
    const bodies: Record<string, Record<string, unknown> | undefined> = {
      move: { move: 'rock' },
      challenge: { truth: 't', dare: 'd' },
      choose: { kind: 'truth' },
      complete: { answer: 'x' },
      reject: { note: 'n' },
    };
    for (const path of [
      'move',
      'rematch',
      'challenge',
      'choose',
      'complete',
      'confirm',
      'reject',
      'cancel',
    ]) {
      const body = bodies[path];
      const res = await post(c, `/games/${gameId}/${path}`, body);
      expect(res.statusCode, `${path} ${res.body}`).toBe(404);
      expect(res.json().error.code).toBe('not_found');
    }
    expect(JSON.stringify((await get(c, '/games')).json())).not.toContain(gameId);
  });

  it('draw → rematch clears moves and bumps the round', async () => {
    const res = await post(b, `/games/${gameId}/move`, { move: 'rock' });
    expect(res.statusCode).toBe(200);
    expect(res.json().game).toMatchObject({
      status: 'draw',
      myMove: 'rock',
      partnerMoved: true,
      partnerMove: 'rock',
      winnerId: null,
      loserId: null,
    });
    const ev = await lastGameEvent();
    expect(ev!.payload.action).toBe('draw');
    expect(ev!.payload.game).toMatchObject({ status: 'draw', movedBy: [a.user.id, b.user.id] });
    expect((ev!.payload.game as { moves: unknown }).moves).toEqual([
      { userId: a.user.id, move: 'rock' },
      { userId: b.user.id, move: 'rock' },
    ]);

    // challenge on a draw is a state error
    const ch = await post(a, `/games/${gameId}/challenge`, { truth: 'hi', dare: 'ho' });
    expect(ch.statusCode).toBe(409);
    expect(ch.json().error.code).toBe('invalid_game_state');

    const re = await post(b, `/games/${gameId}/rematch`);
    expect(re.statusCode).toBe(200);
    expect(re.json().game).toMatchObject({
      status: 'waiting',
      round: 2,
      myMove: null,
      partnerMoved: false,
      partnerMove: null,
    });
    expect((await lastGameEvent())!.payload).toMatchObject({
      action: 'rematch',
      game: { round: 2, movedBy: [], moves: null },
    });
    // rematch outside a draw
    const again = await post(a, `/games/${gameId}/rematch`);
    expect(again.statusCode).toBe(409);
    expect(again.json().error.code).toBe('invalid_game_state');
  });

  it('a winner is revealed with both moves', async () => {
    await post(b, `/games/${gameId}/move`, { move: 'scissors' });
    expect((await get(a, `/games/${gameId}`)).json().game).toMatchObject({
      myMove: null,
      partnerMoved: true,
      partnerMove: null,
    });
    const res = await post(a, `/games/${gameId}/move`, { move: 'rock' });
    expect(res.json().game).toMatchObject({
      status: 'revealed',
      myMove: 'rock',
      partnerMove: 'scissors',
      winnerId: a.user.id,
      loserId: b.user.id,
      round: 2,
    });
    expect((await get(b, `/games/${gameId}`)).json().game).toMatchObject({
      myMove: 'scissors',
      partnerMove: 'rock',
    });
    const ev = await lastGameEvent();
    expect(ev!.payload.action).toBe('revealed');
    expect((ev!.payload.game as { moves: unknown }).moves).toEqual([
      { userId: b.user.id, move: 'scissors' },
      { userId: a.user.id, move: 'rock' },
    ]);
  });

  const TRUTH = 'What did you dream about last night, honestly?';
  const DARE = 'Sing the chorus of our song on a video call.';
  const ANSWER = 'You, obviously. We were on a beach.';
  /** Every stored game.updated payload of this game (what WS live/replay, GET /events and push see). */
  const gameEvents = async () =>
    t.db.events.find({ type: 'game.updated', 'payload.game.id': gameId }).sort({ seq: 1 }).toArray();
  const expectNoSecrets = (value: unknown, secrets: string[]) => {
    const json = JSON.stringify(value);
    for (const secret of secrets) expect(json).not.toContain(secret);
  };

  it('challenge: winner writes BOTH a truth and a dare (validated, trimmed); others get 403/409', async () => {
    const loserCh = await post(b, `/games/${gameId}/challenge`, { truth: 't', dare: 'd' });
    expect(loserCh.statusCode).toBe(403);
    expect(loserCh.json().error.code).toBe('forbidden');

    const early = await post(b, `/games/${gameId}/choose`, { kind: 'truth' });
    expect(early.statusCode).toBe(409);
    expect(early.json().error.code).toBe('invalid_game_state');
    expect((await post(b, `/games/${gameId}/complete`, { answer: 'x' })).statusCode).toBe(409);
    expect((await post(a, `/games/${gameId}/confirm`)).statusCode).toBe(409);
    expect((await post(a, `/games/${gameId}/reject`)).statusCode).toBe(409);

    for (const bad of [
      { truth: 'only a truth' },
      { dare: 'only a dare' },
      { truth: '', dare: 'x' },
      { truth: 'x', dare: '   ' },
      { truth: 'x'.repeat(201), dare: 'x' },
      { truth: 'x', dare: 'x'.repeat(201) },
      { kind: 'truth', text: 'the old single-challenge shape' },
      { truth: 'x', dare: 'y', extra: true },
    ]) {
      const res = await post(a, `/games/${gameId}/challenge`, bad);
      expect(res.statusCode, JSON.stringify(bad)).toBe(400);
      expect(res.json().error.code).toBe('validation_failed');
    }

    const ch = await post(a, `/games/${gameId}/challenge`, { truth: `  ${TRUTH}  `, dare: ` ${DARE} ` });
    expect(ch.statusCode).toBe(200);
    expect(ch.json().game).toMatchObject({ status: 'challenged', answer: null });
    expect(ch.json().game.challenge).toEqual({
      truth: TRUTH,
      dare: DARE,
      chosen: null,
      by: a.user.id,
      at: expect.any(String),
      chosenAt: null,
      doneAt: null,
      confirmedAt: null,
      rejectNote: null,
    });

    const twice = await post(a, `/games/${gameId}/challenge`, { truth: 'x', dare: 'y' });
    expect(twice.statusCode).toBe(409);
    expect(twice.json().error.code).toBe('invalid_game_state');
  });

  it('blind: before choosing, the loser sees neither text (GET, list, errors, events); the winner sees both', async () => {
    const forB = (await get(b, `/games/${gameId}`)).json().game;
    expect(forB.status).toBe('challenged');
    expect(forB.challenge).toEqual({
      truth: null,
      dare: null,
      chosen: null,
      by: a.user.id,
      at: expect.any(String),
      chosenAt: null,
      doneAt: null,
      confirmedAt: null,
      rejectNote: null,
    });
    expectNoSecrets((await get(b, '/games')).json(), [TRUTH, DARE]);
    expectNoSecrets((await get(b, '/me/export')).json(), [TRUTH, DARE]);
    // the viewer-specific game inside error details is blind too
    const err = await post(b, '/games', { kind: 'rps' });
    expect(err.json().error.code).toBe('game_in_progress');
    expectNoSecrets(err.json(), [TRUTH, DARE]);

    const forA = (await get(a, `/games/${gameId}`)).json().game;
    expect(forA.challenge).toMatchObject({ truth: TRUTH, dare: DARE, chosen: null });

    const ev = await lastGameEvent();
    expect(ev!.payload.action).toBe('challenged');
    expect((ev!.payload.game as { challenge: unknown }).challenge).toEqual({
      chosen: null,
      hasChallenge: true,
      chosenAt: null,
      doneAt: null,
      confirmedAt: null,
    });
    expect(ev!.payload.game).not.toHaveProperty('answer');
    expectNoSecrets(await gameEvents(), [TRUTH, DARE]);
    expectNoSecrets((await get(b, '/events?since=0&limit=500')).json(), [TRUTH, DARE]);
  });

  it('choose: only the loser picks (blind); afterwards the loser sees only the chosen text', async () => {
    const winnerChoose = await post(a, `/games/${gameId}/choose`, { kind: 'truth' });
    expect(winnerChoose.statusCode).toBe(403);
    expect(winnerChoose.json().error.code).toBe('forbidden');
    expect((await post(b, `/games/${gameId}/choose`, { kind: 'kiss' })).statusCode).toBe(400);
    expect((await post(b, `/games/${gameId}/choose`, {})).statusCode).toBe(400);
    expect((await post(b, `/games/${gameId}/choose`, { kind: 'truth', extra: 1 })).statusCode).toBe(400);
    // complete before choosing is a state error
    expect((await post(b, `/games/${gameId}/complete`, { answer: 'x' })).json().error.code).toBe(
      'invalid_game_state',
    );

    const res = await post(b, `/games/${gameId}/choose`, { kind: 'truth' });
    expect(res.statusCode).toBe(200);
    expect(res.json().game).toMatchObject({ status: 'chosen', answer: null });
    expect(res.json().game.challenge).toMatchObject({
      truth: TRUTH,
      dare: null,
      chosen: 'truth',
      chosenAt: expect.any(String),
      doneAt: null,
    });
    expectNoSecrets(res.json(), [DARE]);
    expectNoSecrets((await get(b, `/games/${gameId}`)).json(), [DARE]);
    expectNoSecrets((await get(b, '/games')).json(), [DARE]);
    expect((await get(a, `/games/${gameId}`)).json().game.challenge).toMatchObject({
      truth: TRUTH,
      dare: DARE,
      chosen: 'truth',
    });

    const ev = await lastGameEvent();
    expect(ev!.actorId.toHexString()).toBe(b.user.id);
    expect(ev!.payload).toMatchObject({
      action: 'chosen',
      game: {
        status: 'chosen',
        challenge: { chosen: 'truth', hasChallenge: true, chosenAt: expect.any(String) },
      },
    });
    expectNoSecrets(await gameEvents(), [TRUTH, DARE]);

    const again = await post(b, `/games/${gameId}/choose`, { kind: 'dare' });
    expect(again.statusCode).toBe(409);
    expect(again.json().error.code).toBe('invalid_game_state');
  });

  it('complete: loser only; a truth needs a non-empty answer; then visible to both', async () => {
    const winnerDone = await post(a, `/games/${gameId}/complete`, { answer: 'x' });
    expect(winnerDone.statusCode).toBe(403);
    expect(winnerDone.json().error.code).toBe('forbidden');

    for (const body of [undefined, {}, { answer: '' }, { answer: '    ' }]) {
      const res = await post(b, `/games/${gameId}/complete`, body);
      expect(res.statusCode, JSON.stringify(body)).toBe(400);
      expect(res.json().error.code).toBe('validation_failed');
    }
    expect((await post(b, `/games/${gameId}/complete`, { answer: 'x'.repeat(501) })).statusCode).toBe(400);
    expect((await post(b, `/games/${gameId}/complete`, { answer: 'x', extra: 1 })).statusCode).toBe(400);

    const done = await post(b, `/games/${gameId}/complete`, { answer: ` ${ANSWER} ` });
    expect(done.statusCode).toBe(200);
    expect(done.json().game).toMatchObject({
      status: 'awaiting_confirmation',
      answer: ANSWER,
      completedAt: null,
    });
    expect(done.json().game.challenge).toMatchObject({
      doneAt: expect.any(String),
      confirmedAt: null,
      dare: null,
    });
    expect((await get(a, `/games/${gameId}`)).json().game).toMatchObject({
      status: 'awaiting_confirmation',
      answer: ANSWER,
      challenge: { truth: TRUTH, dare: DARE },
    });

    const ev = await lastGameEvent();
    expect(ev!.payload).toMatchObject({
      action: 'done',
      game: { status: 'awaiting_confirmation', challenge: { chosen: 'truth', doneAt: expect.any(String) } },
    });
    expect(ev!.payload.game).not.toHaveProperty('answer');
    expectNoSecrets(await gameEvents(), [TRUTH, DARE, ANSWER]);
    expectNoSecrets((await get(a, '/events?since=0&limit=500')).json(), [TRUTH, DARE, ANSWER]);

    expect((await post(b, `/games/${gameId}/complete`, { answer: 'again' })).statusCode).toBe(409);
  });

  it('reject: winner only; back to chosen with a note, answer cleared; the next complete clears the note', async () => {
    const loserReject = await post(b, `/games/${gameId}/reject`, { note: 'nope' });
    expect(loserReject.statusCode).toBe(403);
    expect(loserReject.json().error.code).toBe('forbidden');
    const loserConfirm = await post(b, `/games/${gameId}/confirm`);
    expect(loserConfirm.statusCode).toBe(403);
    expect(loserConfirm.json().error.code).toBe('forbidden');
    expect((await post(a, `/games/${gameId}/reject`, { note: 'x'.repeat(201) })).statusCode).toBe(400);
    expect((await post(a, `/games/${gameId}/reject`, { reason: 'x' })).statusCode).toBe(400);

    const rej = await post(a, `/games/${gameId}/reject`, { note: '  Be honest this time!  ' });
    expect(rej.statusCode).toBe(200);
    expect(rej.json().game).toMatchObject({ status: 'chosen', answer: null });
    expect(rej.json().game.challenge).toMatchObject({
      chosen: 'truth',
      doneAt: null,
      confirmedAt: null,
      rejectNote: 'Be honest this time!',
    });
    const forB = (await get(b, `/games/${gameId}`)).json().game;
    expect(forB).toMatchObject({ status: 'chosen', answer: null });
    expect(forB.challenge).toMatchObject({ truth: TRUTH, dare: null, rejectNote: 'Be honest this time!' });
    expect((await t.db.games.findOne({ _id: new ObjectId(gameId) }))!.answer).toBeNull();
    expect((await lastGameEvent())!.payload).toMatchObject({
      action: 'rejected',
      game: { status: 'chosen', challenge: { chosen: 'truth', doneAt: null } },
    });
    expect((await post(a, `/games/${gameId}/confirm`)).json().error.code).toBe('invalid_game_state');

    // second attempt: the truth still needs an answer; the reject note is cleared
    expect((await post(b, `/games/${gameId}/complete`)).statusCode).toBe(400);
    const again = await post(b, `/games/${gameId}/complete`, { answer: 'Fine: a beach in Goa.' });
    expect(again.statusCode).toBe(200);
    expect(again.json().game).toMatchObject({
      status: 'awaiting_confirmation',
      answer: 'Fine: a beach in Goa.',
    });
    expect(again.json().game.challenge.rejectNote).toBeNull();

    // a note-less reject (no body) works too
    const bare = await post(a, `/games/${gameId}/reject`);
    expect(bare.statusCode).toBe(200);
    expect(bare.json().game.challenge.rejectNote).toBeNull();
    expect(
      (await post(b, `/games/${gameId}/complete`, { answer: 'A beach in Goa, at sunset.' })).statusCode,
    ).toBe(200);
  });

  it('confirm: winner only; completes the game; nothing can follow; texts never leaked', async () => {
    const ok = await post(a, `/games/${gameId}/confirm`);
    expect(ok.statusCode).toBe(200);
    expect(ok.json().game).toMatchObject({
      status: 'completed',
      answer: 'A beach in Goa, at sunset.',
      completedAt: expect.any(String),
    });
    expect(ok.json().game.challenge).toMatchObject({
      truth: TRUTH,
      dare: DARE,
      chosen: 'truth',
      confirmedAt: expect.any(String),
      rejectNote: null,
    });
    // the loser never sees the dare, even after completion
    const forB = (await get(b, `/games/${gameId}`)).json().game;
    expect(forB).toMatchObject({ status: 'completed', answer: 'A beach in Goa, at sunset.' });
    expect(forB.challenge).toMatchObject({ truth: TRUTH, dare: null, confirmedAt: expect.any(String) });

    expect((await lastGameEvent())!.payload).toMatchObject({
      action: 'confirmed',
      game: {
        status: 'completed',
        completedAt: expect.any(String),
        challenge: { confirmedAt: expect.any(String) },
      },
    });
    expect((await gameEvents()).map((e) => e.payload.action)).toEqual([
      'created',
      'moved',
      'draw',
      'rematch',
      'moved',
      'revealed',
      'challenged',
      'chosen',
      'done',
      'rejected',
      'done',
      'rejected',
      'done',
      'confirmed',
    ]);
    expectNoSecrets(await gameEvents(), [TRUTH, DARE, ANSWER, 'Goa', 'Be honest']);

    for (const [s, path] of [
      [a, 'confirm'],
      [a, 'reject'],
      [b, 'complete'],
      [b, 'choose'],
      [a, 'cancel'],
    ] as const) {
      const res = await post(s, `/games/${gameId}/${path}`, path === 'choose' ? { kind: 'dare' } : undefined);
      expect(res.statusCode, path).toBe(409);
      expect(res.json().error.code).toBe('invalid_game_state');
    }
  });

  it('a new game can start once the previous one completed; cancel works and is idempotent', async () => {
    const res = await post(b, '/games', { kind: 'rps' });
    expect(res.statusCode).toBe(201);
    const id = res.json().game.id;
    await post(b, `/games/${id}/move`, { move: 'paper' });
    const cancel = await post(a, `/games/${id}/cancel`);
    expect(cancel.statusCode).toBe(200);
    // B's move is never revealed by cancelling
    expect(cancel.json().game).toMatchObject({ status: 'cancelled', partnerMoved: true, partnerMove: null });
    const ev = await lastGameEvent();
    expect(ev!.payload).toMatchObject({ action: 'cancelled', game: { status: 'cancelled', moves: null } });
    expect(JSON.stringify(ev!.payload)).not.toContain('paper');
    expect((await post(b, `/games/${id}/cancel`)).statusCode).toBe(200);
    expect(await t.db.events.countDocuments({ type: 'game.updated', 'payload.game.id': id })).toBe(3);
    const move = await post(a, `/games/${id}/move`, { move: 'rock' });
    expect(move.statusCode).toBe(409);
    expect(move.json().error.code).toBe('invalid_game_state');
  });

  it('GET /games lists newest first, viewer-specific; limit validated', async () => {
    const res = await get(a, '/games?limit=20');
    expect(res.statusCode).toBe(200);
    const items = res.json().items;
    expect(items).toHaveLength(2);
    expect(items[0].status).toBe('cancelled');
    expect(items[1]).toMatchObject({ id: gameId, myMove: 'rock', partnerMove: 'scissors' });
    expect((await get(a, '/games?limit=1')).json().items).toHaveLength(1);
    expect((await get(a, '/games?limit=0')).statusCode).toBe(400);
    expect((await get(a, '/games/nope')).statusCode).toBe(400);
    expect((await get(a, `/games/${new ObjectId().toHexString()}`)).statusCode).toBe(404);
  });

  it('pushes game.updated to the other member and respects notification prefs', async () => {
    push.calls.length = 0;
    await t.outbox.runOnce();
    push.calls.length = 0;
    const g = await post(a, '/games', { kind: 'rps' });
    await t.outbox.runOnce();
    const call = push.calls.find((x) => x.data.type === 'game.updated');
    expect(call!.tokens).toEqual(['tok-b']);

    await t.app.inject({
      method: 'PUT',
      url: `${API}/me/preferences`,
      headers: bearer(b),
      payload: { notificationsEnabled: false },
    });
    await post(a, `/games/${g.json().game.id}/move`, { move: 'rock' });
    await t.outbox.runOnce();
    const ev = await lastGameEvent();
    // Notifications off: still pushed, silently at normal priority (widgets/home refresh).
    expect(ev!.push.state).toBe('sent');
    expect(push.calls.at(-1)!.opts.priority).toBe('normal');
    await t.app.inject({
      method: 'PUT',
      url: `${API}/me/preferences`,
      headers: bearer(b),
      payload: { notificationsEnabled: true },
    });
    await post(a, `/games/${g.json().game.id}/cancel`);
  });

  it('concurrent creates and moves stay consistent', async () => {
    const [r1, r2] = await Promise.all([
      post(a, '/games', { kind: 'rps' }),
      post(b, '/games', { kind: 'rps' }),
    ]);
    expect([r1.statusCode, r2.statusCode].sort()).toEqual([201, 409]);
    const id = (r1.statusCode === 201 ? r1 : r2).json().game.id;
    const [m1, m2] = await Promise.all([
      post(a, `/games/${id}/move`, { move: 'paper' }),
      post(b, `/games/${id}/move`, { move: 'rock' }),
    ]);
    expect([m1.statusCode, m2.statusCode]).toEqual([200, 200]);
    const game = (await get(a, `/games/${id}`)).json().game;
    expect(game).toMatchObject({
      status: 'revealed',
      winnerId: a.user.id,
      loserId: b.user.id,
      partnerMove: 'rock',
    });
    expect((await post(b, `/games/${id}/cancel`)).statusCode).toBe(200);
  });

  it('suggestions: 20 truths and 20 dares', async () => {
    const res = await get(a, '/games/suggestions');
    expect(res.statusCode).toBe(200);
    const { truths, dares } = res.json();
    expect(truths).toHaveLength(20);
    expect(dares).toHaveLength(20);
    for (const s of [...truths, ...dares]) {
      expect(typeof s).toBe('string');
      expect(s.length).toBeGreaterThan(0);
      expect(s.length).toBeLessThanOrEqual(200); // usable as a challenge text as-is
    }
    expect(new Set(truths).size).toBe(20);
    expect(new Set(dares).size).toBe(20);
  });

  it('export includes games (viewer-specific)', async () => {
    const exp = await get(b, '/me/export');
    expect(exp.statusCode).toBe(200);
    const games = exp.json().games;
    expect(games.length).toBe(4);
    expect(games.find((x: { id: string }) => x.id === gameId)).toMatchObject({
      myMove: 'scissors',
      partnerMove: 'rock',
    });
  });

  it('requires an active couple', async () => {
    const solo = await register(t.app, 'Solo');
    const res = await post(solo, '/games', { kind: 'rps' });
    expect(res.statusCode).toBe(409);
    expect(res.json().error.code).toBe('not_paired');
  });
});

describe('truth or dare: the dare path and cancelling mid-challenge', () => {
  const T2 = 'Which of my habits secretly annoys you the most?';
  const D2 = 'Send me a voice note of your best evil laugh.';

  /** Plays one round to `revealed` with the given winner (rock beats scissors). */
  async function revealed(winner: Session, loser: Session): Promise<string> {
    const created = await post(winner, '/games', { kind: 'rps' });
    expect(created.statusCode).toBe(201);
    const id = created.json().game.id as string;
    expect((await post(winner, `/games/${id}/move`, { move: 'rock' })).statusCode).toBe(200);
    const res = await post(loser, `/games/${id}/move`, { move: 'scissors' });
    expect(res.json().game).toMatchObject({
      status: 'revealed',
      winnerId: winner.user.id,
      loserId: loser.user.id,
    });
    return id;
  }

  it('choosing dare: the loser never sees the truth (GET, list, export, events); no answer needed', async () => {
    const id = await revealed(b, a);
    expect((await post(b, `/games/${id}/challenge`, { truth: T2, dare: D2 })).statusCode).toBe(200);
    expect((await get(a, `/games/${id}`)).json().game.challenge).toMatchObject({ truth: null, dare: null });

    const chosen = await post(a, `/games/${id}/choose`, { kind: 'dare' });
    expect(chosen.statusCode).toBe(200);
    expect(chosen.json().game.challenge).toMatchObject({ truth: null, dare: D2, chosen: 'dare' });

    const done = await post(a, `/games/${id}/complete`); // no body: fine for a dare
    expect(done.statusCode).toBe(200);
    expect(done.json().game).toMatchObject({ status: 'awaiting_confirmation', answer: null });

    const ok = await post(b, `/games/${id}/confirm`);
    expect(ok.statusCode).toBe(200);
    expect(ok.json().game).toMatchObject({
      status: 'completed',
      challenge: { truth: T2, dare: D2, chosen: 'dare' },
    });

    const forA = (await get(a, `/games/${id}`)).json().game;
    expect(forA.challenge).toMatchObject({ truth: null, dare: D2, confirmedAt: expect.any(String) });
    for (const url of [`/games/${id}`, '/games', '/me/export', '/events?since=0&limit=500']) {
      expect(JSON.stringify((await get(a, url)).json()), url).not.toContain(T2);
    }
    const events = await t.db.events.find({ type: 'game.updated', 'payload.game.id': id }).toArray();
    expect(events.length).toBe(7); // created, moved, revealed, challenged, chosen, done, confirmed
    expect(JSON.stringify(events)).not.toContain(T2);
    expect(JSON.stringify(events)).not.toContain(D2);
  });

  it('a dare may carry an optional answer; cancel works from challenged, chosen and awaiting_confirmation', async () => {
    // cancelled before choosing: the loser stays blind for good
    const g1 = await revealed(a, b);
    await post(a, `/games/${g1}/challenge`, { truth: T2, dare: D2 });
    const c1 = await post(b, `/games/${g1}/cancel`);
    expect(c1.statusCode).toBe(200);
    expect(c1.json().game).toMatchObject({
      status: 'cancelled',
      challenge: { truth: null, dare: null, chosen: null },
    });
    expect((await lastGameEvent())!.payload).toMatchObject({
      action: 'cancelled',
      game: { status: 'cancelled', challenge: { hasChallenge: true, chosen: null } },
    });
    expect((await post(b, `/games/${g1}/choose`, { kind: 'truth' })).json().error.code).toBe(
      'invalid_game_state',
    );

    // cancelled from chosen
    const g2 = await revealed(a, b);
    await post(a, `/games/${g2}/challenge`, { truth: T2, dare: D2 });
    await post(b, `/games/${g2}/choose`, { kind: 'dare' });
    const c2 = await post(a, `/games/${g2}/cancel`);
    expect(c2.statusCode).toBe(200);
    expect((await get(b, `/games/${g2}`)).json().game.challenge).toMatchObject({ truth: null, dare: D2 });

    // cancelled from awaiting_confirmation; the optional dare answer stays visible to both
    const g3 = await revealed(a, b);
    await post(a, `/games/${g3}/challenge`, { truth: T2, dare: D2 });
    await post(b, `/games/${g3}/choose`, { kind: 'dare' });
    const done = await post(b, `/games/${g3}/complete`, { answer: ' Sent it! ' });
    expect(done.json().game).toMatchObject({ status: 'awaiting_confirmation', answer: 'Sent it!' });
    const c3 = await post(b, `/games/${g3}/cancel`);
    expect(c3.statusCode).toBe(200);
    expect(c3.json().game).toMatchObject({ status: 'cancelled', answer: 'Sent it!' });
    expect((await get(a, `/games/${g3}`)).json().game.answer).toBe('Sent it!');
    expect((await post(a, `/games/${g3}/cancel`)).statusCode).toBe(200); // idempotent
    expect((await post(a, `/games/${g3}/confirm`)).json().error.code).toBe('invalid_game_state');
  });
});

describe('game write rate limit', () => {
  it('is per user: 429 rate_limited past the budget', async () => {
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
      const g = await p(x, '/games', { kind: 'rps' });
      expect(g.statusCode).toBe(201);
      expect((await p(x, `/games/${g.json().game.id}/move`, { move: 'rock' })).statusCode).toBe(200);
      const third = await p(x, `/games/${g.json().game.id}/cancel`);
      expect(third.statusCode).toBe(429);
      expect(third.json().error.code).toBe('rate_limited');
      expect(third.headers['retry-after']).toBeDefined();
      expect((await p(y, `/games/${g.json().game.id}/move`, { move: 'paper' })).statusCode).toBe(200);
    } finally {
      await t2.close();
    }
  });
});

describe('shared chat wallpaper', () => {
  const patch = (s: Session, payload: Record<string, unknown>) =>
    t.app.inject({ method: 'PATCH', url: `${API}/couple`, headers: bearer(s), payload });

  it('defaults to null in the couple DTO', async () => {
    const res = await get(c, '/couple');
    expect(res.statusCode).toBe(200);
    expect(res.json().couple.sharedPreferences).toEqual({ chatWallpaper: null });
  });

  it('either member sets a style; both see it; couple.updated is emitted', async () => {
    const res = await patch(b, { chatWallpaper: { style: 'HEARTS' } });
    expect(res.statusCode).toBe(200);
    const wp = res.json().couple.sharedPreferences.chatWallpaper;
    expect(wp).toEqual({ style: 'HEARTS', photoMediaId: null, setBy: b.user.id, setAt: expect.any(String) });
    expect((await get(a, '/couple')).json().couple.sharedPreferences.chatWallpaper).toEqual(wp);
    expect((await get(a, '/me')).json().couple.sharedPreferences.chatWallpaper).toEqual(wp);
    const ev = await t.db.events.findOne({ type: 'couple.updated' }, { sort: { seq: -1 } });
    expect(ev!.actorId.toHexString()).toBe(b.user.id);
    expect(
      (ev!.payload.couple as { sharedPreferences: { chatWallpaper: unknown } }).sharedPreferences
        .chatWallpaper,
    ).toEqual(wp);
  });

  it('flat sharedPreferences PATCH keeps the wallpaper; an echoed chatWallpaper key is ignored', async () => {
    const res = await patch(a, { sharedPreferences: { theme: 'dusk', chatWallpaper: { style: 'NIGHT' } } });
    expect(res.statusCode).toBe(200);
    expect(res.json().couple.sharedPreferences).toEqual({
      theme: 'dusk',
      chatWallpaper: expect.objectContaining({ style: 'HEARTS', setBy: b.user.id }),
    });
  });

  it('PHOTO requires a ready photo/wallpaper media of the couple; both can download it', async () => {
    expect((await patch(a, { chatWallpaper: { style: 'PHOTO' } })).statusCode).toBe(400);
    expect((await patch(a, { chatWallpaper: { style: 'PHOTO', photoMediaId: null } })).statusCode).toBe(400);
    const photo = await uploadPhoto(t, a, undefined, 'wallpaper');
    expect((await patch(a, { chatWallpaper: { style: 'GLOW', photoMediaId: photo } })).statusCode).toBe(400);
    expect((await patch(a, { chatWallpaper: { style: 'SPACE' } })).statusCode).toBe(400);
    expect((await patch(a, { chatWallpaper: { style: 'PAPER', extra: true } })).statusCode).toBe(400);

    const unknown = await patch(a, {
      chatWallpaper: { style: 'PHOTO', photoMediaId: new ObjectId().toHexString() },
    });
    expect(unknown.statusCode).toBe(400);
    expect(unknown.json().error.code).toBe('validation_failed');

    const foreign = await uploadPhoto(t, c);
    const r1 = await patch(a, { chatWallpaper: { style: 'PHOTO', photoMediaId: foreign } });
    expect(r1.statusCode).toBe(403);
    expect(r1.json().error.code).toBe('forbidden');

    const avatar = await uploadPhoto(t, a, undefined, 'avatar');
    expect((await patch(a, { chatWallpaper: { style: 'PHOTO', photoMediaId: avatar } })).statusCode).toBe(
      400,
    );

    const pending = await t.app.inject({
      method: 'POST',
      url: `${API}/media/uploads`,
      headers: bearer(a),
      payload: { category: 'photo', mimeType: 'image/png', sizeBytes: 10 },
    });
    expect(
      (await patch(a, { chatWallpaper: { style: 'PHOTO', photoMediaId: pending.json().media.id } }))
        .statusCode,
    ).toBe(400);

    const vo = await uploadPhoto(t, a);
    const sent = await t.app.inject({
      method: 'POST',
      url: `${API}/messages`,
      headers: bearer(a),
      payload: { type: 'photo', mediaId: vo, viewOnce: true, idempotencyKey: randomUUID() },
    });
    expect(sent.statusCode).toBe(201);
    const r2 = await patch(a, { chatWallpaper: { style: 'PHOTO', photoMediaId: vo } });
    expect(r2.statusCode).toBe(400);
    expect(r2.json().error.message).toMatch(/view-once/);

    const ok = await patch(a, { chatWallpaper: { style: 'PHOTO', photoMediaId: photo } });
    expect(ok.statusCode).toBe(200);
    expect(ok.json().couple.sharedPreferences.chatWallpaper).toMatchObject({
      style: 'PHOTO',
      photoMediaId: photo,
      setBy: a.user.id,
    });
    for (const s of [a, b]) {
      const url = await get(s, `/media/${photo}/url`);
      expect(url.statusCode).toBe(200);
      expect((await t.app.inject({ method: 'GET', url: url.json().url })).statusCode).toBe(200);
    }
    // a chat photo of the partner works too
    const bPhoto = await uploadPhoto(t, b);
    expect((await patch(a, { chatWallpaper: { style: 'PHOTO', photoMediaId: bPhoto } })).statusCode).toBe(
      200,
    );
  });

  it('null clears back to the default', async () => {
    const res = await patch(b, { chatWallpaper: null });
    expect(res.statusCode).toBe(200);
    expect(res.json().couple.sharedPreferences).toEqual({ theme: 'dusk', chatWallpaper: null });
    const ev = await t.db.events.findOne({ type: 'couple.updated' }, { sort: { seq: -1 } });
    expect(ev!.actorId.toHexString()).toBe(b.user.id);
    expect(
      (ev!.payload.couple as { sharedPreferences: { chatWallpaper: unknown } }).sharedPreferences
        .chatWallpaper,
    ).toBeNull();
  });
});

describe('rps with thread: rule table', () => {
  it('every pair of different moves has exactly one winner, with the agreed rules', async () => {
    const { BEATS } = await import('../src/modules/games/service.js');
    const moves = ['rock', 'paper', 'scissors', 'thread'] as const;
    for (const a of moves)
      for (const b of moves) {
        if (a === b) continue;
        const aWins = BEATS[a].includes(b),
          bWins = BEATS[b].includes(a);
        expect(aWins !== bWins).toBe(true);
      }
    expect(BEATS.thread).toEqual(['rock', 'paper']);
    expect(BEATS.scissors).toContain('thread');
  });
});
