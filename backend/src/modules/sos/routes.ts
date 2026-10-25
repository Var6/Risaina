import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams } from '../shared/schemas.js';
import { CreateSosBody, SosListQuery, SosListResponse, SosResponse } from './schemas.js';
import { SosService } from './service.js';

const TAG = 'SOS';

export const sosRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new SosService(ctx);
  const guarded = { preValidation: [app.authenticate], preHandler: [app.requireCouple] };
  const security = [{ bearerAuth: [] }];

  r.post(
    '/sos',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Raise an SOS to the partner (always pushed, even in quiet hours)',
        description: 'At most 3 per sender per 10 minutes (429 rate_limited with Retry-After).',
        security,
        body: CreateSosBody,
        response: {
          201: SosResponse,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          409: ErrorEnvelope,
          429: ErrorEnvelope,
        },
      },
    },
    async (req, reply) =>
      reply.code(201).send(await service.create(req.couple!, req.user!.id, req.partnerId!, req.body)),
  );

  r.get(
    '/sos',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Recent SOS alerts of the couple (newest first, max 20)',
        security,
        querystring: SosListQuery,
        response: { 200: SosListResponse, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req) => service.list(req.couple!, req.query.limit),
  );

  r.post(
    '/sos/:id/ack',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Recipient acknowledges an SOS (idempotent)',
        security,
        params: IdParams,
        response: {
          200: SosResponse,
          401: ErrorEnvelope,
          403: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
        },
      },
    },
    async (req) => service.acknowledge(req.couple!, req.user!.id, parseId(req.params.id, 'SOS')),
  );
};
