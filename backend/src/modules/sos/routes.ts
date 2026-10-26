import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { errors } from '../../lib/errors.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams } from '../shared/schemas.js';
import {
  CreateSosBody,
  SosDeliveredBody,
  SosListQuery,
  SosListResponse,
  SosResponse,
  SosTestBody,
  SosTestResponse,
} from './schemas.js';
import { SosService } from './service.js';

const TAG = 'SOS';

/** Server test alarms per user per 10 minutes (they ring the caller's own phone). */
export const SOS_TESTS_PER_WINDOW = 3;

export const sosRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new SosService(ctx);
  const guarded = { preValidation: [app.authenticate], preHandler: [app.requireCouple] };
  const security = [{ bearerAuth: [] }];

  const testLimiter = app.createRateLimit({
    max: SOS_TESTS_PER_WINDOW,
    timeWindow: 10 * 60_000,
    keyGenerator: (req) => `sos-test:${req.user?.id ?? req.ip}`,
  });
  const limitTests = async (req: FastifyRequest) => {
    const res = await testLimiter(req);
    if (!res.isAllowed && res.isExceeded) throw errors.rateLimited(res.ttlInSeconds, 'Too many test alarms');
  };

  r.post(
    '/sos',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Raise an SOS to the partner (always pushed, even in quiet hours)',
        description:
          'At most 3 per sender per 10 minutes (429 rate_limited with Retry-After). A repeat with the same ' +
          'idempotencyKey returns the first SOS (no new event, not counted).',
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

  r.post(
    '/sos/:id/delivered',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: "Recipient's phone confirms the alarm is ringing (idempotent)",
        description:
          'Sets deliveredAt once, emits sos.delivered to the sender and stops the re-pushes. The optional ' +
          'body carries device state (alarm muted, ringer mode, Do Not Disturb), never content.',
        security,
        params: IdParams,
        body: SosDeliveredBody.nullish(), // Fastify passes null when there is no body
        response: {
          200: SosResponse,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          403: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
        },
      },
    },
    async (req) =>
      service.delivered(req.couple!, req.user!.id, parseId(req.params.id, 'SOS'), req.body ?? undefined),
  );

  r.post(
    '/sos/:id/resolve',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Sender says "I\'m safe now" (idempotent)',
        description:
          "Sets resolvedAt, emits sos.resolved once (stops the alarm on the partner's phone) and ends the " +
          'live location shared for this SOS (location.stopped, reason resolved).',
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
    async (req) => service.resolve(req.couple!, req.user!.id, parseId(req.params.id, 'SOS')),
  );

  r.post(
    '/sos/test',
    {
      preValidation: [app.authenticate],
      preHandler: [limitTests],
      schema: {
        tags: [TAG],
        summary: 'Ring a test alarm on my own phones through the server',
        description:
          `Pushes {type: sos.test} after delaySeconds (default 15). ${SOS_TESTS_PER_WINDOW} per user per 10 ` +
          'minutes. No database write, no event. queued is false when push is off or I have no devices.',
        security,
        body: SosTestBody.nullish(),
        response: { 202: SosTestResponse, 400: ErrorEnvelope, 401: ErrorEnvelope, 429: ErrorEnvelope },
      },
    },
    async (req, reply) =>
      reply.code(202).send(await service.test(req.user!.id, req.body?.delaySeconds ?? 15)),
  );
};
