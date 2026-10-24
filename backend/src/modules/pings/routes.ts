import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams } from '../shared/schemas.js';
import { CreatePingBody, PingListQuery, PingListResponse, PingResponse } from './schemas.js';
import { PingService } from './service.js';

const TAG = 'Pings';

export const pingRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new PingService(ctx);
  const guarded = { preValidation: [app.authenticate], preHandler: [app.requireCouple] };
  const security = [{ bearerAuth: [] }];

  r.post(
    '/pings',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Send a Touch Ping (idempotent; cooldown + hourly cap enforced)',
        security,
        body: CreatePingBody,
        response: {
          201: PingResponse,
          200: PingResponse,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          409: ErrorEnvelope,
          429: ErrorEnvelope,
        },
      },
    },
    async (req, reply) => {
      const { ping, created } = await service.create(req.couple!, req.user!.id, req.partnerId!, req.body);
      return reply.code(created ? 201 : 200).send({ ping });
    },
  );

  r.get(
    '/pings',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Ping history (newest first)',
        security,
        querystring: PingListQuery,
        response: { 200: PingListResponse, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req) => service.list(req.couple!, req.query.limit, req.query.before),
  );

  r.post(
    '/pings/:id/delivered',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Recipient acknowledges delivery (idempotent)',
        security,
        params: IdParams,
        response: {
          200: PingResponse,
          401: ErrorEnvelope,
          403: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
        },
      },
    },
    async (req) => service.markDelivered(req.couple!, req.user!.id, parseId(req.params.id, 'Ping')),
  );

  r.post(
    '/pings/:id/seen',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Recipient marks the ping as seen (idempotent)',
        security,
        params: IdParams,
        response: {
          200: PingResponse,
          401: ErrorEnvelope,
          403: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
        },
      },
    },
    async (req) => service.markSeen(req.couple!, req.user!.id, parseId(req.params.id, 'Ping')),
  );
};
