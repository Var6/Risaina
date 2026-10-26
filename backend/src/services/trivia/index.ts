import type { Logger } from 'pino';
import type { Env } from '../../config/env.js';
import type { Clock } from '../../lib/time.js';
import { BuiltinTrivia } from './builtin.js';
import { OpenTdbTrivia } from './opentdb.js';
import type { TriviaSource } from './types.js';

export { BuiltinTrivia } from './builtin.js';
export { OpenTdbTrivia } from './opentdb.js';
export type { TriviaRequest, TriviaResult, TriviaSource } from './types.js';

/** OpenTDB with the built-in set as fallback, or the built-in set alone (TRIVIA_PROVIDER=builtin). */
export function createTriviaSource(env: Env, logger: Logger, now: Clock): TriviaSource {
  const builtin = new BuiltinTrivia();
  if (env.trivia.provider === 'builtin') return builtin;
  return new OpenTdbTrivia({
    fallback: builtin,
    logger,
    now,
    timeoutMs: env.trivia.timeoutMs,
    minIntervalMs: env.trivia.minIntervalMs,
  });
}
