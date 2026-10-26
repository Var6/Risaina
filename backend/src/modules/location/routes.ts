import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { errors } from '../../lib/errors.js';
import { ErrorEnvelope, NoContent } from '../shared/schemas.js';
import {
  PutFixBody,
  PutFixResponse,
  SharingResponse,
  SharingStateResponse,
  StartSharingBody,
  StopSharingQuery,
} from './schemas.js';
import {
  LIVE_LOCATION_FIX_WRITES_PER_MIN,
  LIVE_LOCATION_PANIC_STARTS_PER_10_MIN,
  LIVE_LOCATION_STARTS_PER_HOUR,
  LocationService,
} from './service.js';

const TAG = 'Location';

export const locationRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new LocationService(ctx);
  const security = [{ bearerAuth: [] }];
  const guarded = { preValidation: [app.authenticate] };

  // Per-user budgets, applied after authentication (route-level limits could only key by IP).
  const fixLimiter = app.createRateLimit({
    max: LIVE_LOCATION_FIX_WRITES_PER_MIN,
    timeWindow: 60_000,
    keyGenerator: (req) => `loc-fix:${req.user?.id ?? req.ip}`,
  });
  const startLimiter = app.createRateLimit({
    max: LIVE_LOCATION_STARTS_PER_HOUR,
    timeWindow: 60 * 60_000,
    keyGenerator: (req) => `loc-start:${req.user?.id ?? req.ip}`,
  });
  const panicLimiter = app.createRateLimit({
    max: LIVE_LOCATION_PANIC_STARTS_PER_10_MIN,
    timeWindow: 10 * 60_000,
    keyGenerator: (req) => `loc-panic:${req.user?.id ?? req.ip}`,
  });
  const limitFixes = async (req: FastifyRequest) => {
    const res = await fixLimiter(req);
    if (!res.isAllowed && res.isExceeded)
      throw errors.rateLimited(res.ttlInSeconds, 'Too many location updates');
  };
  const limitStarts = async (req: FastifyRequest) => {
    // A panic start never uses up (or waits for) the manual budget. It has its own generous one:
    // sosId is optional for panic, so the SOS limit does not bound these, and each can push.
    const panic = (req.body as { reason?: string } | undefined)?.reason === 'panic';
    const res = await (panic ? panicLimiter : startLimiter)(req);
    if (!res.isAllowed && res.isExceeded)
      throw errors.rateLimited(res.ttlInSeconds, 'Too many sharing starts');
  };

  r.get(
    '/location/sharing',
    {
      ...guarded,
      preHandler: [app.requireCouple],
      schema: {
        tags: [TAG],
        summary: 'Live location: my session and my partner’s (expired sessions are null)',
        security,
        response: { 200: SharingStateResponse, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req) => service.get(req.couple!, req.user!.id),
  );

  r.post(
    '/location/sharing',
    {
      ...guarded,
      preHandler: [app.requireCouple, limitStarts],
      schema: {
        tags: [TAG],
        summary: 'Start (201) or extend (200) sharing my live location with my partner',
        description:
          'Emits location.started (no coordinates; pushed to the partner) when the session is new or its ' +
          'reason, expiry or SOS link changed. A stopped sessionId answers 409 location_not_sharing. ' +
          `Manual starts: ${LIVE_LOCATION_STARTS_PER_HOUR} per user per hour; panic starts have their own ` +
          `${LIVE_LOCATION_PANIC_STARTS_PER_10_MIN} per user per 10 minutes. A panic start linked to its SOS ` +
          'is pushed at normal priority (the SOS alarm is the alert).',
        security,
        body: StartSharingBody,
        response: {
          200: SharingResponse,
          201: SharingResponse,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
          429: ErrorEnvelope,
        },
      },
    },
    async (req, reply) => {
      const res = await service.start(req.couple!, req.user!.id, req.partnerId!, req.body);
      return reply.code(res.created ? 201 : 200).send({ sharing: res.sharing });
    },
  );

  r.put(
    '/location/sharing/fix',
    {
      ...guarded,
      preHandler: [app.requireCouple, limitFixes],
      schema: {
        tags: [TAG],
        summary: 'Upload my latest position (sent to my partner live; never stored as history)',
        description:
          'Overwrites the stored fix when it is newer and always refreshes lastSeenAt. No event, no push. ' +
          `${LIVE_LOCATION_FIX_WRITES_PER_MIN} per user per minute.`,
        security,
        body: PutFixBody,
        response: {
          200: PutFixResponse,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          409: ErrorEnvelope,
          429: ErrorEnvelope,
        },
      },
    },
    async (req) => service.putFix(req.couple!, req.user!.id, req.body),
  );

  r.delete(
    '/location/sharing',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Stop sharing (always 204; works without a couple)',
        description:
          'With sessionId: stops only that session (a stale retry never ends a newer one). Without: stops ' +
          'all of mine. Coordinates are removed at once. Emits location.stopped while the couple is active.',
        security,
        querystring: StopSharingQuery,
        response: { 204: NoContent, 400: ErrorEnvelope, 401: ErrorEnvelope },
      },
    },
    async (req, reply) => {
      await service.stop(req.user!.id, req.query);
      return reply.code(204).send(null);
    },
  );
};
