import type { Db } from 'mongodb';
import type { Logger } from 'pino';
import type { Env } from './config/env.js';
import type { Collections } from './db/collections.js';
import type { AttemptLimiter } from './lib/attempt-limiter.js';
import type { BackgroundTasks } from './lib/background.js';
import type { Clock } from './lib/time.js';
import type { GoogleTokenVerifier } from './modules/auth/google.js';
import type { TokenService } from './modules/auth/tokens.js';
import type { WsHub } from './services/events/hub.js';
import type { EventPublisher } from './services/events/publisher.js';
import type { Mailer } from './services/mailer/types.js';
import type { PushService } from './services/push/types.js';
import type { StorageService } from './services/storage/types.js';

/**
 * Explicit dependency bag handed to every module's route plugin. No global singletons: tests
 * construct one with in-memory Mongo, a temp local storage dir and a fake push service.
 */
export interface AppContext {
  env: Env;
  db: Collections;
  mongoDb: Db;
  storage: StorageService;
  push: PushService;
  mailer: Mailer;
  hub: WsHub;
  events: EventPublisher;
  tokens: TokenService;
  /** null when GOOGLE_CLIENT_IDS is unset: POST /auth/google answers 503 google_not_configured. */
  google: GoogleTokenVerifier | null;
  inviteLimiter: AttemptLimiter;
  /** Work that must not block a response (e.g. storage deletes after unlink); drained on close. */
  background: BackgroundTasks;
  now: Clock;
  logger: Logger;
  version: string;
  startedAt: Date;
}
