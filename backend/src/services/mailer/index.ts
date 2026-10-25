import type { Logger } from 'pino';
import type { Env } from '../../config/env.js';
import type { Clock } from '../../lib/time.js';
import { ConsoleMailer } from './console.js';
import { ResendMailer } from './resend.js';
import type { Mailer } from './types.js';

export function createMailer(env: Env, logger: Logger, now?: Clock): Mailer {
  if (env.mailer.driver === 'resend') {
    return new ResendMailer({ apiKey: env.mailer.apiKey, from: env.mailer.from, logger, now });
  }
  return new ConsoleMailer(logger, !env.isProduction);
}

export { ConsoleMailer } from './console.js';
export { RESEND_ENDPOINT, ResendMailer } from './resend.js';
export * from './templates.js';
export * from './types.js';
