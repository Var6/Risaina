import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { errors } from '../../lib/errors.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams } from '../shared/schemas.js';
import {
  ChallengeBody,
  ChooseBody,
  CompleteBody,
  CreateGameBody,
  GameListQuery,
  GameListResponse,
  GameResponse,
  MoveBody,
  RejectBody,
  SuggestionsResponse,
} from './schemas.js';
import { GameService } from './service.js';

const TAG = 'Games';

export const gameRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new GameService(ctx);
  const security = [{ bearerAuth: [] }];

  // Per-user budget shared by every game write (route-level limits run before auth and key by IP).
  const writeLimiter = app.createRateLimit({
    max: ctx.env.rateLimit.gameWritesPerMinute,
    timeWindow: 60_000,
    keyGenerator: (req) => `game:${req.user?.id ?? req.ip}`,
  });
  const limitWrites = async (req: FastifyRequest) => {
    const res = await writeLimiter(req);
    if (!res.isAllowed && res.isExceeded) {
      throw errors.rateLimited(res.ttlInSeconds, 'Too many game actions; slow down');
    }
  };
  const read = { preValidation: [app.authenticate], preHandler: [app.requireCouple] };
  const write = { preValidation: [app.authenticate], preHandler: [app.requireCouple, limitWrites] };
  const writeErrors = {
    400: ErrorEnvelope,
    401: ErrorEnvelope,
    403: ErrorEnvelope,
    404: ErrorEnvelope,
    409: ErrorEnvelope,
    429: ErrorEnvelope,
  };
  const gameId = (id: string) => parseId(id, 'Game');

  r.get(
    '/games/suggestions',
    {
      ...read,
      schema: {
        tags: [TAG],
        summary: 'Built-in truth and dare ideas (20 each)',
        security,
        response: { 200: SuggestionsResponse, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async () => service.suggestions(),
  );

  r.post(
    '/games',
    {
      ...write,
      schema: {
        tags: [TAG],
        summary: 'Start a rock–paper–scissors game (one open game per couple)',
        description: '409 game_in_progress with details.game when an open game exists.',
        security,
        body: CreateGameBody,
        response: { 201: GameResponse, ...writeErrors },
      },
    },
    async (req, reply) =>
      reply.code(201).send(await service.create(req.couple!, req.user!.id, req.partnerId!, req.body)),
  );

  r.get(
    '/games',
    {
      ...read,
      schema: {
        tags: [TAG],
        summary: 'Recent games of the couple, newest first (viewer-specific)',
        security,
        querystring: GameListQuery,
        response: { 200: GameListResponse, 400: ErrorEnvelope, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req) => service.list(req.couple!, req.user!.id, req.query.limit),
  );

  r.get(
    '/games/:id',
    {
      ...read,
      schema: {
        tags: [TAG],
        summary: 'One game (viewer-specific: the partner move is hidden until both moved)',
        security,
        params: IdParams,
        response: { 200: GameResponse, 401: ErrorEnvelope, 404: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req) => service.get(req.couple!, req.user!.id, gameId(req.params.id)),
  );

  r.post(
    '/games/:id/move',
    {
      ...write,
      schema: {
        tags: [TAG],
        summary: 'Play rock, paper, scissors or thread (once per player per round)',
        security,
        params: IdParams,
        body: MoveBody,
        response: { 200: GameResponse, ...writeErrors },
      },
    },
    async (req) => service.move(req.couple!, req.user!.id, gameId(req.params.id), req.body.move),
  );

  r.post(
    '/games/:id/rematch',
    {
      ...write,
      schema: {
        tags: [TAG],
        summary: 'After a draw: start the next round (either player)',
        security,
        params: IdParams,
        response: { 200: GameResponse, ...writeErrors },
      },
    },
    async (req) => service.rematch(req.couple!, req.user!.id, gameId(req.params.id)),
  );

  r.post(
    '/games/:id/challenge',
    {
      ...write,
      schema: {
        tags: [TAG],
        summary: 'Winner writes BOTH a truth and a dare (status revealed → challenged)',
        description: 'The loser sees neither text until they choose, then only the chosen one.',
        security,
        params: IdParams,
        body: ChallengeBody,
        response: { 200: GameResponse, ...writeErrors },
      },
    },
    async (req) => service.challenge(req.couple!, req.user!.id, gameId(req.params.id), req.body),
  );

  r.post(
    '/games/:id/choose',
    {
      ...write,
      schema: {
        tags: [TAG],
        summary: 'Loser picks truth or dare blind (status challenged → chosen)',
        security,
        params: IdParams,
        body: ChooseBody,
        response: { 200: GameResponse, ...writeErrors },
      },
    },
    async (req) => service.choose(req.couple!, req.user!.id, gameId(req.params.id), req.body.kind),
  );

  r.post(
    '/games/:id/complete',
    {
      ...write,
      schema: {
        tags: [TAG],
        summary: 'Loser marks the chosen truth or dare done (status chosen → awaiting_confirmation)',
        description: 'A truth needs a non-empty `answer` (400 otherwise); for a dare it is optional.',
        security,
        params: IdParams,
        body: CompleteBody.nullish(), // body optional: no body, null or {}
        response: { 200: GameResponse, ...writeErrors },
      },
    },
    async (req) => service.complete(req.couple!, req.user!.id, gameId(req.params.id), req.body ?? {}),
  );

  r.post(
    '/games/:id/confirm',
    {
      ...write,
      schema: {
        tags: [TAG],
        summary: 'Winner accepts it (status awaiting_confirmation → completed)',
        security,
        params: IdParams,
        response: { 200: GameResponse, ...writeErrors },
      },
    },
    async (req) => service.confirm(req.couple!, req.user!.id, gameId(req.params.id)),
  );

  r.post(
    '/games/:id/reject',
    {
      ...write,
      schema: {
        tags: [TAG],
        summary: 'Winner sends it back with an optional note (status awaiting_confirmation → chosen)',
        description: 'Clears the answer; the note is cleared by the next complete.',
        security,
        params: IdParams,
        body: RejectBody.nullish(), // body optional: no body, null or {}
        response: { 200: GameResponse, ...writeErrors },
      },
    },
    async (req) => service.reject(req.couple!, req.user!.id, gameId(req.params.id), req.body ?? {}),
  );

  r.post(
    '/games/:id/cancel',
    {
      ...write,
      schema: {
        tags: [TAG],
        summary: 'Cancel an unfinished game (either player; idempotent)',
        security,
        params: IdParams,
        response: { 200: GameResponse, ...writeErrors },
      },
    },
    async (req) => service.cancel(req.couple!, req.user!.id, gameId(req.params.id)),
  );
};
