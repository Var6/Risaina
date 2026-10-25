import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { errors } from '../../lib/errors.js';
import { ErrorEnvelope } from '../shared/schemas.js';
import { PutWidgetBoardBody, WidgetBoardResponse, WidgetBoardsResponse } from './schemas.js';
import { WidgetBoardService } from './service.js';

const TAG = 'Widget';

export const widgetBoardRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new WidgetBoardService(ctx);
  const security = [{ bearerAuth: [] }];

  // Per-user budget (route-level limits run before authentication and could only key by IP).
  const writeLimiter = app.createRateLimit({
    max: ctx.env.rateLimit.widgetWritesPerMinute,
    timeWindow: 60_000,
    keyGenerator: (req) => `widget:${req.user?.id ?? req.ip}`,
  });
  const limitWrites = async (req: FastifyRequest) => {
    const res = await writeLimiter(req);
    if (!res.isAllowed && res.isExceeded) {
      throw errors.rateLimited(res.ttlInSeconds, 'Too many widget updates; slow down');
    }
  };

  r.get(
    '/widget-board',
    {
      preValidation: [app.authenticate],
      preHandler: [app.requireCouple],
      schema: {
        tags: [TAG],
        summary: 'Widget boards: mine (what I show my partner) and forMe (what my partner shows me)',
        security,
        response: { 200: WidgetBoardsResponse, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req) => service.get(req.couple!, req.user!.id),
  );

  r.put(
    '/widget-board',
    {
      preValidation: [app.authenticate],
      preHandler: [app.requireCouple, limitWrites],
      schema: {
        tags: [TAG],
        summary: "Set (part of) what my partner's widgets show",
        description:
          'Any subset of { note, emoji, mood, photoMediaId }; omitted fields keep their value, null clears. ' +
          'Emits widget.updated { board } (pushed to the partner). 30 writes per user per minute.',
        security,
        body: PutWidgetBoardBody,
        response: {
          200: WidgetBoardResponse,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          403: ErrorEnvelope,
          409: ErrorEnvelope,
          429: ErrorEnvelope,
        },
      },
    },
    async (req) => service.put(req.couple!, req.user!.id, req.body),
  );
};
