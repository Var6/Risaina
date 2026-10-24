import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { parseId } from '../../lib/ids.js';
import { ErrorEnvelope, IdParams, NoContent } from '../shared/schemas.js';
import {
  AcceptBody,
  AcceptResponse,
  CreateInvitationBody,
  CurrentInvitation,
  InvitationCreated,
} from './schemas.js';
import { InvitationService } from './service.js';

const TAG = 'Pairing';

export const invitationRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new InvitationService(ctx);
  const auth = { preValidation: [app.authenticate] };

  r.post(
    '/invitations',
    {
      ...auth,
      config: { rateLimit: { max: 20, timeWindow: 60 * 60 * 1000 } },
      schema: {
        tags: [TAG],
        summary: 'Create an invitation code (shown once); creates a pending couple if none',
        security: [{ bearerAuth: [] }],
        body: CreateInvitationBody,
        response: { 201: InvitationCreated, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req, reply) => {
      const invitation = await service.create(req.user!.id);
      return reply.code(201).send({ invitation });
    },
  );

  r.get(
    '/invitations/current',
    {
      ...auth,
      schema: {
        tags: [TAG],
        summary: 'Current open invitation (without the code)',
        security: [{ bearerAuth: [] }],
        response: { 200: CurrentInvitation, 401: ErrorEnvelope },
      },
    },
    async (req) => ({ invitation: await service.current(req.user!.id) }),
  );

  r.delete(
    '/invitations/:id',
    {
      ...auth,
      schema: {
        tags: [TAG],
        summary: 'Revoke an invitation',
        security: [{ bearerAuth: [] }],
        params: IdParams,
        response: { 204: NoContent, 401: ErrorEnvelope, 404: ErrorEnvelope },
      },
    },
    async (req, reply) => {
      await service.revoke(req.user!.id, parseId(req.params.id, 'Invitation'));
      return reply.code(204).send(null);
    },
  );

  r.post(
    '/invitations/accept',
    {
      ...auth,
      schema: {
        tags: [TAG],
        summary: 'Accept an invitation code and activate the couple',
        description:
          '5 attempts per 15 minutes per user+IP. 410 invitation_expired, 409 invitation_used / already_paired.',
        security: [{ bearerAuth: [] }],
        body: AcceptBody,
        response: {
          200: AcceptResponse,
          401: ErrorEnvelope,
          404: ErrorEnvelope,
          409: ErrorEnvelope,
          410: ErrorEnvelope,
          429: ErrorEnvelope,
        },
      },
    },
    async (req) => service.accept(req.user!.id, req.ip, req.body.code),
  );
};
