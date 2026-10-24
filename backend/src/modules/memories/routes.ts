import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams, NoContent } from '../shared/schemas.js';
import {
  CreateMemoryBody,
  MemoryListQuery,
  MemoryListResponse,
  MemoryResponse,
  PatchMemoryBody,
} from './schemas.js';
import { MemoryService } from './service.js';

const TAG = 'Memories';

export const memoryRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new MemoryService(ctx);
  const guarded = { preValidation: [app.authenticate], preHandler: [app.requireCouple] };
  const security = [{ bearerAuth: [] }];
  const errs = { 401: ErrorEnvelope, 404: ErrorEnvelope, 409: ErrorEnvelope };

  r.post(
    '/memories',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Create a memory from a ready photo',
        security,
        body: CreateMemoryBody,
        response: { 201: MemoryResponse, 400: ErrorEnvelope, ...errs },
      },
    },
    async (req, reply) => reply.code(201).send(await service.create(req.couple!, req.user!.id, req.body)),
  );

  r.get(
    '/memories',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'List memories (newest first)',
        security,
        querystring: MemoryListQuery,
        response: { 200: MemoryListResponse, ...errs },
      },
    },
    async (req) => service.list(req.couple!, req.query.limit, req.query.before, req.query.pinned),
  );

  r.patch(
    '/memories/:id',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Update caption / pinned',
        security,
        params: IdParams,
        body: PatchMemoryBody,
        response: { 200: MemoryResponse, 400: ErrorEnvelope, ...errs },
      },
    },
    async (req) => service.update(req.couple!, req.user!.id, parseId(req.params.id, 'Memory'), req.body),
  );

  r.delete(
    '/memories/:id',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Delete a memory (soft)',
        security,
        params: IdParams,
        response: { 204: NoContent, ...errs },
      },
    },
    async (req, reply) => {
      await service.delete(req.couple!, req.user!.id, parseId(req.params.id, 'Memory'));
      return reply.code(204).send(null);
    },
  );
};
