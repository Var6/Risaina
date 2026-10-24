import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { ErrorEnvelope, NoContent } from '../shared/schemas.js';
import { DeviceParams, DeviceUpsertBody } from './schemas.js';
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
};
