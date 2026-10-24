import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams } from '../shared/schemas.js';
import { CreateMessageBody, MessageListQuery, MessageListResponse, MessageResponse } from './schemas.js';
import { MessageService } from './service.js';

const TAG = 'Messages';

export const messageRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new MessageService(ctx);
  const guarded = { preValidation: [app.authenticate], preHandler: [app.requireCouple] };
  const security = [{ bearerAuth: [] }];

  r.post(
    '/messages',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Send a message (idempotent)',
        description:
          'text required for text|emoji; mediaId required for photo|voice and must be ready, owned by the sender and of the matching category.',
        security,
        body: CreateMessageBody,
        response: {
          201: MessageResponse,
          200: MessageResponse,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          403: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
        },
      },
    },
    async (req, reply) => {
      const { message, created } = await service.create(req.couple!, req.user!.id, req.partnerId!, req.body);
      return reply.code(created ? 201 : 200).send({ message });
    },
  );

  r.get(
    '/messages',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Message history (newest first; deleted messages are tombstones)',
        security,
        querystring: MessageListQuery,
        response: { 200: MessageListResponse, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req) => service.list(req.couple!, req.query.limit, req.query.before),
  );

  r.post(
    '/messages/:id/read',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Recipient marks as read (idempotent)',
        security,
        params: IdParams,
        response: {
          200: MessageResponse,
          401: ErrorEnvelope,
          403: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
        },
      },
    },
    async (req) => service.markRead(req.couple!, req.user!.id, parseId(req.params.id, 'Message')),
  );

  r.delete(
    '/messages/:id',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Sender soft-deletes a message (media object deleted)',
        security,
        params: IdParams,
        response: {
          200: MessageResponse,
          401: ErrorEnvelope,
          403: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
        },
      },
    },
    async (req) => service.softDelete(req.couple!, req.user!.id, parseId(req.params.id, 'Message')),
  );
};
