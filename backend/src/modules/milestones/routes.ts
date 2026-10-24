import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams, NoContent } from '../shared/schemas.js';
import {
  CreateMilestoneBody,
  MilestoneListResponse,
  MilestoneResponse,
  PatchMilestoneBody,
} from './schemas.js';
import { MilestoneService } from './service.js';

const TAG = 'Milestones';

export const milestoneRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new MilestoneService(ctx);
  const guarded = { preValidation: [app.authenticate], preHandler: [app.requireCouple] };
  const security = [{ bearerAuth: [] }];
  const errs = { 401: ErrorEnvelope, 404: ErrorEnvelope, 409: ErrorEnvelope };

  r.post(
    '/milestones',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Create a milestone',
        security,
        body: CreateMilestoneBody,
        response: { 201: MilestoneResponse, 400: ErrorEnvelope, ...errs },
      },
    },
    async (req, reply) => reply.code(201).send(await service.create(req.couple!, req.user!.id, req.body)),
  );

  r.get(
    '/milestones',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'List milestones (by date)',
        security,
        response: { 200: MilestoneListResponse, ...errs },
      },
    },
    async (req) => service.list(req.couple!),
  );

  r.patch(
    '/milestones/:id',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Update a milestone',
        security,
        params: IdParams,
        body: PatchMilestoneBody,
        response: { 200: MilestoneResponse, 400: ErrorEnvelope, ...errs },
      },
    },
    async (req) => service.update(req.couple!, req.user!.id, parseId(req.params.id, 'Milestone'), req.body),
  );

  r.delete(
    '/milestones/:id',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Delete a milestone',
        security,
        params: IdParams,
        response: { 204: NoContent, ...errs },
      },
    },
    async (req, reply) => {
      await service.delete(req.couple!, req.user!.id, parseId(req.params.id, 'Milestone'));
      return reply.code(204).send(null);
    },
  );
};
