import { type Logger, type LoggerOptions, pino } from 'pino';

/**
 * Pino logger with redaction. Request bodies are never logged by Fastify; the redaction list is
 * defence in depth for anything that flows through `log.info({...})`.
 */
export const REDACT_PATHS = [
  'req.headers.authorization',
  'req.headers.cookie',
  'headers.authorization',
  'authorization',
  'password',
  '*.password',
  'newPassword',
  '*.newPassword',
  'token',
  '*.token',
  'refreshToken',
  '*.refreshToken',
  'accessToken',
  '*.accessToken',
  'tokens',
  '*.tokens',
  'email',
  '*.email',
  'text',
  '*.text',
  'message.text',
  'ping.message',
  '*.ping.message',
  'code',
  '*.code',
  'deviceToken',
  '*.deviceToken',
  'resetUrl',
  '*.resetUrl',
];

export function loggerOptions(level: string, pretty: boolean): LoggerOptions {
  const opts: LoggerOptions = {
    level,
    redact: { paths: REDACT_PATHS, censor: '[redacted]' },
    serializers: {
      req(req: { method?: string; url?: string; id?: unknown; ip?: string }) {
        // Strip query strings: signed URLs and invitation codes must never reach logs.
        const url = typeof req.url === 'string' ? req.url.split('?')[0] : undefined;
        return { method: req.method, url, id: req.id, ip: req.ip };
      },
      res(res: { statusCode?: number }) {
        return { statusCode: res.statusCode };
      },
    },
  };
  if (pretty) {
    opts.transport = { target: 'pino-pretty', options: { colorize: true, translateTime: 'SYS:standard' } };
  }
  return opts;
}

export function createLogger(level: string, pretty: boolean): Logger {
  return pino(loggerOptions(level, pretty));
}

export type { Logger };
