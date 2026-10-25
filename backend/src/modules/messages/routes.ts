import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams } from '../shared/schemas.js';
import {
  CreateMessageBody,
  MessageListQuery,
  MessageListResponse,
  MessageResponse,
  OpenMessageResponse,
} from './schemas.js';
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
          'text required for text|emoji; mediaId required for photo|voice and must be ready, owned by the sender and of the matching category. ' +
          'viewOnce: true only for photo (400 otherwise); its media must not already be used by a message, memory or widget board (409 conflict). ' +
          'A non view-once photo also creates a memory (memory.created, sourceMessageId = message id).',
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

  r.post(
    '/messages/:id/open',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Recipient opens a view-once photo (once): short-lived download URL',
        description:
          'First call: sets openedAt, emits message.opened { messageId, openedAt } and returns { url, expiresAt, message } ' +
          '(URL TTL VIEW_ONCE_URL_TTL_SECONDS, default 120). Later calls: 410 view_once_consumed. Purged unopened: ' +
          '410 view_once_expired. Not a view-once photo: 400. Sender: 403. Deleted or other couple: 404.',
        security,
        params: IdParams,
        response: {
          200: OpenMessageResponse,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          403: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
          410: ErrorEnvelope,
        },
      },
    },
    async (req) => service.open(req.couple!, req.user!.id, parseId(req.params.id, 'Message')),
  );

  r.delete(
    '/messages/:id',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Sender soft-deletes a message (media object deleted; its auto-memory soft-deleted)',
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
