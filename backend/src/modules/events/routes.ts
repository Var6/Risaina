import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { errors } from '../../lib/errors.js';
import { ErrorEnvelope } from '../shared/schemas.js';
import { EventsQuery, EventsResponse } from './schemas.js';
import { EventQueryService } from './service.js';

export const eventRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new EventQueryService(ctx);

  r.get(
    '/events',
    {
      // No requireCouple: a user whose couple was just unlinked still reads its final event.
      preValidation: [app.authenticate],
      schema: {
        tags: ['Events'],
        summary: 'Events newer than `since` (missed-event recovery)',
        description:
          "Active couple: all its events. After an unlink (and until paired again) the ended couple's " +
          '`couple.unlinked` event stays readable for 7 days; otherwise 409 not_paired.',
        security: [{ bearerAuth: [] }],
        querystring: EventsQuery,
        response: { 200: EventsResponse, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req) => {
      const feed = await service.feedFor(req.user!.id);
      if (!feed) throw errors.notPaired();
      return service.since(feed, req.query.since, req.query.limit);
    },
  );
};
