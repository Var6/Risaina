import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { ErrorEnvelope } from '../shared/schemas.js';
import { EventsQuery, EventsResponse } from './schemas.js';
import { EventQueryService } from './service.js';

export const eventRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new EventQueryService(ctx);

  r.get(
    '/events',
    {
      preValidation: [app.authenticate],
      preHandler: [app.requireCouple],
      schema: {
        tags: ['Events'],
        summary: 'Events newer than `since` (missed-event recovery)',
        security: [{ bearerAuth: [] }],
        querystring: EventsQuery,
        response: { 200: EventsResponse, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req) => service.since(req.couple!._id, req.query.since, req.query.limit),
  );
};
