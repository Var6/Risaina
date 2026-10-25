import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { ObjectId } from 'mongodb';
import type { AppContext } from '../../app-context.js';
import { ErrorEnvelope, NoContent } from '../shared/schemas.js';
import { CoupleOnlyResponse, CouplePatchBody, CoupleResponse, UnlinkBody } from './schemas.js';
import { CoupleService } from './service.js';

const TAG = 'Couple';

export const coupleRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new CoupleService(ctx);
  const guarded = { preValidation: [app.authenticate], preHandler: [app.requireCouple] };

  r.get(
    '/couple',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Get the active couple and partner',
        security: [{ bearerAuth: [] }],
        response: { 200: CoupleResponse, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req) => service.getWithPartner(req.couple!, req.partnerId!),
  );

  r.patch(
    '/couple',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'Update shared couple fields',
        security: [{ bearerAuth: [] }],
        body: CouplePatchBody,
        response: { 200: CoupleOnlyResponse, 400: ErrorEnvelope, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req) => service.update(req.couple!, req.user!.id, req.body),
  );

  r.post(
    '/couple/unlink',
    {
      ...guarded,
      schema: {
        tags: [TAG],
        summary: 'End the couple: both members notified, all couple data and media purged',
        description:
          'Hard-deletes everything the couple created (messages, pings, memories, milestones, lists, games, ' +
          'widget boards, SOS, invitations, media objects and documents, avatars uploaded in this couple, and ' +
          'every event except the final couple.unlinked, which the ex-partner can still read for 7 days).',
        security: [{ bearerAuth: [] }],
        body: UnlinkBody,
        response: { 204: NoContent, 400: ErrorEnvelope, 401: ErrorEnvelope, 409: ErrorEnvelope },
      },
    },
    async (req, reply) => {
      await service.endCouple(req.couple!._id, new ObjectId(req.user!.id));
      return reply.code(204).send(null);
    },
  );
};
