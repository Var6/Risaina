import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { z } from 'zod';
import { errors } from '../../lib/errors.js';
import type { LocalStorageDriver } from './local.js';

export interface LocalStorageRoutesOptions {
  storage: LocalStorageDriver;
  nodeEnv: string;
  maxObjectBytes: number;
}

const querySchema = z.object({
  key: z.string().min(1).max(256),
  exp: z.coerce.number().int().positive(),
  ct: z.string().max(128).optional(),
  sig: z.string().regex(/^[a-f0-9]{64}$/),
});

/**
 * DEVELOPMENT-ONLY routes backing `LocalStorageDriver` signed URLs.
 * Mounted under `/api/v1/_local-storage`. Refused when NODE_ENV=production (defence in depth;
 * `loadEnv` already rejects the local driver in production).
 */
export const registerLocalStorageRoutes: FastifyPluginAsync<LocalStorageRoutesOptions> = async (
  app,
  opts,
) => {
  if (opts.nodeEnv === 'production') {
    throw new Error('Local storage routes must not be registered in production');
  }
  const r = app.withTypeProvider<ZodTypeProvider>();

  // Accept any content type as a raw buffer within this encapsulated plugin only.
  app.removeAllContentTypeParsers();
  app.addContentTypeParser('*', { parseAs: 'buffer', bodyLimit: opts.maxObjectBytes }, (_req, body, done) => {
    done(null, body);
  });

  r.put(
    '/object',
    {
      bodyLimit: opts.maxObjectBytes,
      config: { rateLimit: false },
      schema: {
        tags: ['Local storage (development only)'],
        summary: 'Upload target for development signed URLs',
        description:
          'Development only. Verifies the HMAC signature and expiry produced by POST /media/uploads.',
        querystring: querySchema,
        response: { 204: z.null().meta({ description: 'No content' }) },
      },
    },
    async (req, reply) => {
      const { key, exp, ct, sig } = req.query;
      const contentType = req.headers['content-type'] ?? '';
      if (!opts.storage.verify('PUT', key, exp, ct ?? '', sig)) {
        throw errors.forbidden('Invalid or expired upload signature');
      }
      if (ct && contentType.split(';')[0]?.trim() !== ct) {
        throw errors.unsupportedMedia('Content-Type does not match the signed upload');
      }
      const body = req.body;
      if (!Buffer.isBuffer(body)) throw errors.validation('Expected a raw request body');
      await opts.storage.putObject(key, body, ct ?? contentType);
      return reply.code(204).send(null);
    },
  );

  r.get(
    '/object',
    {
      config: { rateLimit: false },
      schema: {
        tags: ['Local storage (development only)'],
        summary: 'Download endpoint for development signed URLs',
        querystring: querySchema,
        hide: false,
      },
    },
    async (req, reply) => {
      const { key, exp, sig } = req.query;
      if (!opts.storage.verify('GET', key, exp, '', sig)) {
        throw errors.forbidden('Invalid or expired download signature');
      }
      const obj = await opts.storage.openReadStream(key);
      if (!obj) throw errors.notFound('Object');
      reply.header('Content-Type', obj.head.contentType ?? 'application/octet-stream');
      reply.header('Content-Length', String(obj.head.sizeBytes));
      reply.header('Cache-Control', 'private, no-store');
      return reply.send(obj.stream);
    },
  );
};
