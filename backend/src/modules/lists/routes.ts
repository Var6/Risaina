import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { errors } from '../../lib/errors.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams, NoContent } from '../shared/schemas.js';
import { ConvertListBody, CreateListBody, ListListResponse, ListResponse, PatchListBody } from './schemas.js';
import { ListService } from './service.js';

const TAG = 'Lists';

export const listRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new ListService(ctx);
  const security = [{ bearerAuth: [] }];
  const errs = { 401: ErrorEnvelope, 404: ErrorEnvelope, 409: ErrorEnvelope };

  // One per-user budget shared by POST/PATCH/DELETE and convert (the global per-IP limit still applies first).
  // createRateLimit is used instead of route config because route limits run onRequest, before
  // authentication, so they could only key by IP.
  const writeLimiter = app.createRateLimit({
    max: ctx.env.rateLimit.listWritesPerMinute,
    timeWindow: 60_000,
    keyGenerator: (req) => `lists:${req.user?.id ?? req.ip}`,
  });
  const limitWrites = async (req: FastifyRequest) => {
    const res = await writeLimiter(req);
    if (!res.isAllowed && res.isExceeded) {
      throw errors.rateLimited(res.ttlInSeconds, 'Too many list changes; slow down');
    }
  };
  const guarded = { preValidation: [app.authenticate], preHandler: [app.requireCouple] };
  const guardedWrite = { preValidation: [app.authenticate], preHandler: [app.requireCouple, limitWrites] };

  r.get(
    '/lists',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Shared lists of the couple (pinned first, then most recently updated; max 200)',
        security,
        response: { 200: ListListResponse, ...errs },
      },
    },
    async (req) => service.list(req.couple!),
  );

  r.post(
    '/lists',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: 'Create a note or checklist',
        description:
          'With clientKey, a repeat with the same key answers 200 with the list the first request created ' +
          '(404 if it has been deleted since) and emits nothing.',
        security,
        body: CreateListBody,
        response: { 201: ListResponse, 200: ListResponse, 400: ErrorEnvelope, 429: ErrorEnvelope, ...errs },
      },
    },
    async (req, reply) => {
      const { list, created } = await service.create(req.couple!, req.user!.id, req.body);
      return reply.code(created ? 201 : 200).send({ list });
    },
  );

  r.get(
    '/lists/:id',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'One shared list (404 when deleted or not in the couple)',
        security,
        params: IdParams,
        response: { 200: ListResponse, ...errs },
      },
    },
    async (req) => service.get(req.couple!, parseId(req.params.id, 'List')),
  );

  r.post(
    '/lists/:id/convert',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: 'Turn a note into a checklist or a checklist into a note',
        description:
          'note → checklist: each non-blank body line becomes an item (bullets, numbers and [ ] / [x] are ' +
          'stripped; [x] ticks it; lines over 300 characters become several items). Once 200 items are ' +
          'reached, the remaining lines stay in the body. checklist → note: the items are written as lines ' +
          'below the body (numbers, bullets, ☐ / ☑ as the list was drawn); items that would take the body ' +
          'over 10,000 characters stay items. Converting to the current kind only applies marker/checkboxes. ' +
          'Increments version and emits list.updated; expectedVersion as for PATCH.',
        security,
        params: IdParams,
        body: ConvertListBody,
        response: { 200: ListResponse, 400: ErrorEnvelope, 429: ErrorEnvelope, ...errs },
      },
    },
    async (req) => service.convert(req.couple!, req.user!.id, parseId(req.params.id, 'List'), req.body),
  );

  r.patch(
    '/lists/:id',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: 'Update a list (items replaces the whole array; optional expectedVersion)',
        description:
          'Increments version. When expectedVersion is given and differs from the current version: ' +
          '409 { error: { code: "version_conflict", details: { list: List } } } with the current list.',
        security,
        params: IdParams,
        body: PatchListBody,
        response: { 200: ListResponse, 400: ErrorEnvelope, 429: ErrorEnvelope, ...errs },
      },
    },
    async (req) => service.update(req.couple!, req.user!.id, parseId(req.params.id, 'List'), req.body),
  );

  r.delete(
    '/lists/:id',
    {
      ...guardedWrite,
      schema: {
        tags: [TAG],
        summary: 'Delete a list (soft delete)',
        security,
        params: IdParams,
        response: { 204: NoContent, 429: ErrorEnvelope, ...errs },
      },
    },
    async (req, reply) => {
      await service.delete(req.couple!, req.user!.id, parseId(req.params.id, 'List'));
      return reply.code(204).send(null);
    },
  );
};
