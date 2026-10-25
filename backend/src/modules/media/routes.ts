import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams, NoContent } from '../shared/schemas.js';
import { CreateUploadBody, MediaResponse, MediaUrlResponse, UploadCreated } from './schemas.js';
import { MediaService } from './service.js';

const TAG = 'Media';

export const mediaRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new MediaService(ctx);
  const guarded = { preValidation: [app.authenticate], preHandler: [app.requireCouple] };
  const security = [{ bearerAuth: [] }];

  r.post(
    '/media/uploads',
    {
      ...guarded,
      config: { rateLimit: { max: 60, timeWindow: 60 * 60 * 1000 } },
      schema: {
        tags: [TAG],
        summary: 'Create a pending media record and a presigned PUT URL',
        security,
        body: CreateUploadBody,
        response: {
          201: UploadCreated,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          409: ErrorEnvelope,
          413: ErrorEnvelope,
          415: ErrorEnvelope,
        },
      },
    },
    async (req, reply) => {
      const result = await service.createUpload(req.couple!, req.user!.id, req.body);
      return reply.code(201).send(result);
    },
  );

  r.post(
    '/media/:id/complete',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Verify the uploaded object (HEAD + magic bytes) and mark it ready',
        security,
        params: IdParams,
        response: {
          200: MediaResponse,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          403: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
          413: ErrorEnvelope,
          415: ErrorEnvelope,
        },
      },
    },
    async (req) => service.complete(req.couple!, req.user!.id, parseId(req.params.id, 'Media')),
  );

  r.get(
    '/media/:id/url',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Short-lived signed download URL (membership-checked)',
        description: 'View-once media: 403 view_once for both members (use POST /messages/:id/open).',
        security,
        params: IdParams,
        response: {
          200: MediaUrlResponse,
          401: ErrorEnvelope,
          403: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
        },
      },
    },
    async (req) => service.downloadUrl(req.couple!, parseId(req.params.id, 'Media')),
  );

  r.delete(
    '/media/:id',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Delete media (owner only)',
        security,
        params: IdParams,
        response: {
          204: NoContent,
          401: ErrorEnvelope,
          403: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
        },
      },
    },
    async (req, reply) => {
      await service.delete(req.couple!, req.user!.id, parseId(req.params.id, 'Media'));
      return reply.code(204).send(null);
    },
  );
};
