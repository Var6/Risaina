import type { FastifyError, FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { hasZodFastifySchemaValidationErrors, isResponseSerializationError } from 'fastify-type-provider-zod';
import { AppError, type ErrorCode } from '../lib/errors.js';

interface Envelope {
  error: { code: ErrorCode; message: string; details?: unknown };
}

function send(
  reply: FastifyReply,
  status: number,
  body: Envelope,
  headers: Record<string, string> = {},
): FastifyReply {
  for (const [k, v] of Object.entries(headers)) reply.header(k, v);
  return reply.code(status).type('application/json; charset=utf-8').send(body);
}

/** Maps every error to the API.md envelope. Never leaks stack traces or internal messages for 5xx. */
export function registerErrorHandler(app: FastifyInstance): void {
  app.setNotFoundHandler((_request, reply) => {
    send(reply, 404, { error: { code: 'not_found', message: 'Route not found' } });
  });

  app.setErrorHandler(
    (error: FastifyError | AppError | Error, request: FastifyRequest, reply: FastifyReply) => {
      if (error instanceof AppError) {
        send(reply, error.statusCode, error.toBody(), error.headers);
        return;
      }

      if (hasZodFastifySchemaValidationErrors(error)) {
        const details = error.validation.map((v) => ({
          path: v.instancePath || '/',
          message: v.message,
          keyword: v.keyword,
        }));
        send(reply, 400, {
          error: { code: 'validation_failed', message: 'Request validation failed', details },
        });
        return;
      }

      if (isResponseSerializationError(error)) {
        request.log.error({ err: error, reqId: request.id }, 'response failed schema serialization');
        send(reply, 500, { error: { code: 'internal', message: 'Internal server error' } });
        return;
      }

      const fe = error as FastifyError;
      const status = typeof fe.statusCode === 'number' ? fe.statusCode : 500;

      if (status === 429) {
        const retryAfter = reply.getHeader('retry-after');
        const headers: Record<string, string> = {};
        if (retryAfter !== undefined) headers['retry-after'] = String(retryAfter);
        send(reply, 429, { error: { code: 'rate_limited', message: 'Too many requests' } }, headers);
        return;
      }
      if (status === 503) {
        // @fastify/under-pressure. Same client behaviour as a rate limit: back off and retry later.
        const headers: Record<string, string> = {
          'retry-after': String(reply.getHeader('retry-after') ?? '10'),
        };
        send(
          reply,
          503,
          { error: { code: 'rate_limited', message: 'Service temporarily unavailable' } },
          headers,
        );
        return;
      }
      if (status === 401) {
        send(reply, 401, { error: { code: 'unauthorized', message: 'Unauthorized' } });
        return;
      }
      if (status === 403) {
        send(reply, 403, { error: { code: 'forbidden', message: 'Forbidden' } });
        return;
      }
      if (status === 404) {
        send(reply, 404, { error: { code: 'not_found', message: 'Not found' } });
        return;
      }
      if (status === 413) {
        send(reply, 413, { error: { code: 'payload_too_large', message: 'Payload too large' } });
        return;
      }
      if (status === 415) {
        send(reply, 415, { error: { code: 'unsupported_media', message: 'Unsupported media type' } });
        return;
      }
      if (status >= 400 && status < 500) {
        // Body parsing errors, bad JSON, empty body, etc.
        send(reply, status === 400 ? 400 : status, {
          error: {
            code: status === 409 ? 'conflict' : 'validation_failed',
            message: fe.message || 'Bad request',
          },
        });
        return;
      }

      request.log.error({ err: error, reqId: request.id }, 'unhandled error');
      send(reply, 500, { error: { code: 'internal', message: 'Internal server error' } });
    },
  );
}
