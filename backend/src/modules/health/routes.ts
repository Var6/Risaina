import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { z } from 'zod';
import type { AppContext } from '../../app-context.js';
import { pingMongo } from '../../db/client.js';

const HealthResponse = z.object({
  status: z.enum(['ok', 'degraded']),
  version: z.string(),
  uptimeSeconds: z.number().int(),
  checks: z.object({
    mongo: z.object({ status: z.enum(['ok', 'error']) }),
    storage: z.object({ status: z.enum(['ok', 'error']), driver: z.enum(['local', 'r2']) }),
    push: z.object({ status: z.enum(['ok', 'disabled']), provider: z.string() }),
  }),
});

export const healthRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();

  r.get(
    '/health',
    {
      config: { rateLimit: false },
      schema: {
        tags: ['Health'],
        summary: 'Liveness + dependency status',
        response: { 200: HealthResponse },
      },
    },
    async () => {
      const [mongo, storage] = await Promise.all([pingMongo(ctx.mongoDb), ctx.storage.healthCheck()]);
      const status = mongo && storage === 'ok' ? 'ok' : 'degraded';
      return {
        status,
        version: ctx.version,
        uptimeSeconds: Math.floor((ctx.now().getTime() - ctx.startedAt.getTime()) / 1000),
        checks: {
          mongo: { status: mongo ? 'ok' : 'error' },
          storage: { status: storage, driver: ctx.storage.driver },
          push: { status: ctx.push.enabled ? 'ok' : 'disabled', provider: ctx.push.provider },
        },
      } as const;
    },
  );

  r.get(
    '/health/ready',
    {
      config: { rateLimit: false },
      schema: {
        tags: ['Health'],
        summary: 'Readiness (200 when Mongo answers, else 503)',
        response: { 200: z.object({ ready: z.literal(true) }), 503: z.object({ ready: z.literal(false) }) },
      },
    },
    async (_req, reply) => {
      const ok = await pingMongo(ctx.mongoDb);
      return reply.code(ok ? 200 : 503).send({ ready: ok } as { ready: true } | { ready: false });
    },
  );
};
