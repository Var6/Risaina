import type { FastifyPluginAsync, FastifyRequest } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { errors } from '../../lib/errors.js';
import { ErrorEnvelope, NoContent } from '../shared/schemas.js';
import {
  DeviceHealthResponse,
  DeviceParams,
  DeviceUpsertBody,
  TestPushBody,
  TestPushResponse,
} from './schemas.js';
import { DeviceService } from './service.js';

const TAG = 'Devices';

export const deviceRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new DeviceService(ctx);
  const auth = { preValidation: [app.authenticate] };
  const security = [{ bearerAuth: [] }];

  r.put(
    '/devices',
    {
      ...auth,
      schema: {
        tags: [TAG],
        summary: 'Register / refresh this device push token (write-only; never returned)',
        security,
        body: DeviceUpsertBody,
        response: { 204: NoContent, 400: ErrorEnvelope, 401: ErrorEnvelope },
      },
    },
    async (req, reply) => {
      await service.upsert(req.user!.id, req.body);
      return reply.code(204).send(null);
    },
  );

  r.delete(
    '/devices/:deviceId',
    {
      ...auth,
      schema: {
        tags: [TAG],
        summary: 'Remove this device push token',
        security,
        params: DeviceParams,
        response: { 204: NoContent, 401: ErrorEnvelope },
      },
    },
    async (req, reply) => {
      await service.revoke(req.user!.id, req.params.deviceId);
      return reply.code(204).send(null);
    },
  );

  // --- delivery health (stream: delivery) -------------------------------------------------------

  // Per-user budget (route-level limits run before authentication and could only key by IP).
  const testPushLimiter = app.createRateLimit({
    max: 6,
    timeWindow: 10 * 60_000,
    keyGenerator: (req) => `test-push:${req.user?.id ?? req.ip}`,
  });
  const limitTestPush = async (req: FastifyRequest) => {
    const res = await testPushLimiter(req);
    if (!res.isAllowed && res.isExceeded) {
      throw errors.rateLimited(res.ttlInSeconds, 'Too many test notifications; try again in a few minutes');
    }
  };

  r.post(
    '/devices/test-push',
    {
      ...auth,
      preHandler: [limitTestPush],
      schema: {
        tags: [TAG],
        summary: 'Send a test push to my own phone(s)',
        description:
          'High priority, data-only `{ type: "test.push", eventId, coupleId: "", seq: "0", pr: "h" }`, sent ' +
          "straight to the caller's device tokens (never to the partner, never stored as an event). " +
          '`scheduled: false` with a reason when push is disabled on the server or no phone is registered. ' +
          '6 per user per 10 minutes.',
        security,
        body: TestPushBody,
        response: { 200: TestPushResponse, 400: ErrorEnvelope, 401: ErrorEnvelope, 429: ErrorEnvelope },
      },
    },
    async (req) => service.testPush(req.user!.id, req.body),
  );

  r.get(
    '/devices/health',
    {
      ...auth,
      schema: {
        tags: [TAG],
        summary: "Delivery health of my phones and, when paired, my partner's",
        security,
        response: { 200: DeviceHealthResponse, 401: ErrorEnvelope },
      },
    },
    async (req) => service.health(req.user!.id),
  );
};
