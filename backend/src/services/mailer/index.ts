import type { Logger } from 'pino';
import type { Env } from '../../config/env.js';
import { ConsoleMailer } from './console.js';
import type { Mailer } from './types.js';

export function createMailer(env: Env, logger: Logger): Mailer {
  // env.mailer is always 'console' today.
  return new ConsoleMailer(logger, !env.isProduction);
}

export { ConsoleMailer } from './console.js';
export * from './types.js';
