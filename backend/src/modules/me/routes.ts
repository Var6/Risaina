import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { ErrorEnvelope, NoContent } from '../shared/schemas.js';
import {
  DeleteMeBody,
  ExportResponse,
  MePatchBody,
  MeResponse,
  PreferencesBody,
  PreferencesResponse,
  UserResponse,
} from './schemas.js';
import { MeService } from './service.js';

const TAG = 'Me';

export const meRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new MeService(ctx);
  const auth = { preValidation: [app.authenticate] };
  const security = [{ bearerAuth: [] }];

  r.get(
    '/me',
    {
      ...auth,
      schema: {
        tags: [TAG],
        summary: 'Current user, couple, partner and preferences',
        security,
        response: { 200: MeResponse, 401: ErrorEnvelope },
      },
    },
    async (req) => service.get(req.user!.id),
  );

  r.patch(
    '/me',
    {
      ...auth,
      schema: {
        tags: [TAG],
        summary: 'Update display name / avatar',
        security,
        body: MePatchBody,
        response: {
          200: UserResponse,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
        },
      },
    },
    async (req) => service.update(req.user!.id, req.body),
  );

  r.get(
    '/me/preferences',
    {
      ...auth,
      schema: {
        tags: [TAG],
        summary: 'Get preferences',
        security,
        response: { 200: PreferencesResponse, 401: ErrorEnvelope },
      },
    },
    async (req) => service.getPreferences(req.user!.id),
  );

  r.put(
    '/me/preferences',
    {
      ...auth,
      schema: {
        tags: [TAG],
        summary: 'Update preferences (partial allowed)',
        security,
        body: PreferencesBody,
        response: { 200: PreferencesResponse, 400: ErrorEnvelope, 401: ErrorEnvelope },
      },
    },
    async (req) => service.updatePreferences(req.user!.id, req.body),
  );

  r.delete(
    '/me',
    {
      ...auth,
      config: { rateLimit: { max: 5, timeWindow: 15 * 60 * 1000 } },
      schema: {
        tags: [TAG],
        summary: 'Delete the account (cascades: sessions, devices, couple ended, own media deleted)',
        security,
        body: DeleteMeBody,
        response: { 204: NoContent, 401: ErrorEnvelope },
      },
    },
    async (req, reply) => {
      await service.deleteAccount(req.user!.id, req.body.password);
      return reply.code(204).send(null);
    },
  );

  r.get(
    '/me/export',
    {
      ...auth,
      config: { rateLimit: { max: 5, timeWindow: 15 * 60 * 1000 } },
      schema: {
        tags: [TAG],
        summary: 'Export my data (bounded to 1000 items per collection)',
        security,
        response: { 200: ExportResponse, 401: ErrorEnvelope },
      },
    },
    async (req) => service.export(req.user!.id),
  );
};
