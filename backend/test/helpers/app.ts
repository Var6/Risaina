import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import type { FastifyInstance } from 'fastify';
import type { JWTVerifyGetKey } from 'jose';
import { MongoClient } from 'mongodb';
import { pino } from 'pino';
import { inject } from 'vitest';
import { buildApp } from '../../src/app.js';
import type { AppContext } from '../../src/app-context.js';
import { type Env, loadEnv } from '../../src/config/env.js';
import { type Collections, getCollections, prepareDatabase } from '../../src/db/index.js';
import { createGoogleVerifier } from '../../src/modules/auth/google.js';
import {
  type EmailVerificationMail,
  type Mailer,
  MailerError,
  type PasswordResetMail,
} from '../../src/services/mailer/types.js';
import { DisabledPushService } from '../../src/services/push/disabled.js';
import type {
  PushData,
  PushSendOptions,
  PushSendResult,
  PushService,
} from '../../src/services/push/types.js';
import { LOCAL_STORAGE_ROUTE_PREFIX } from '../../src/services/storage/index.js';
import { LocalStorageDriver } from '../../src/services/storage/local.js';
import type { TriviaSource } from '../../src/services/trivia/types.js';
import { OutboxWorker } from '../../src/workers/outbox.js';

export const TEST_JWT_SECRET = 'test-secret-test-secret-test-secret-test-secret-0123456789';

/** Mutable clock so tests can move time forward deterministically. */
export class TestClock {
  private offsetMs = 0;
  now = (): Date => new Date(Date.now() + this.offsetMs);
  advance(ms: number): void {
    this.offsetMs += ms;
  }
}

export class CapturingMailer implements Mailer {
  readonly provider = 'capturing' as const;
  /** Password reset mails (kept under the original name). */
  readonly sent: PasswordResetMail[] = [];
  readonly verifications: EmailVerificationMail[] = [];
  /** When true every send rejects (after recording the attempt), like a provider outage. */
  fail = false;
  async sendPasswordReset(mail: PasswordResetMail): Promise<void> {
    this.sent.push(mail);
    if (this.fail) throw new MailerError('capturing mailer: simulated failure');
  }
  async sendEmailVerification(mail: EmailVerificationMail): Promise<void> {
    this.verifications.push(mail);
    if (this.fail) throw new MailerError('capturing mailer: simulated failure');
  }
  lastCodeFor(email: string): string | undefined {
    return this.verifications.filter((m) => m.to === email).at(-1)?.code;
  }
}

/** Fake push: records calls; `failTimes` makes the first N sends fail with a retryable error. */
export class FakePushService implements PushService {
  readonly enabled = true;
  readonly provider = 'fake' as const;
  readonly calls: { tokens: string[]; data: PushData; opts: PushSendOptions }[] = [];
  failTimes = 0;
  unregistered = new Set<string>();

  async send(tokens: string[], data: PushData, opts?: PushSendOptions): Promise<PushSendResult> {
    this.calls.push({ tokens: [...tokens], data: { ...data }, opts: { priority: 'high', ...opts } });
    if (this.failTimes > 0) {
      this.failTimes--;
      return {
        successCount: 0,
        failureCount: tokens.length,
        unregisteredTokens: [],
        acceptedTokens: [],
        retryable: true,
        errorSummary: 'messaging/internal-error',
      };
    }
    const unregisteredTokens = tokens.filter((t) => this.unregistered.has(t));
    const acceptedTokens = tokens.filter((t) => !this.unregistered.has(t));
    return {
      successCount: acceptedTokens.length,
      failureCount: unregisteredTokens.length,
      unregisteredTokens,
      acceptedTokens,
      retryable: false,
      errorSummary: unregisteredTokens.length ? 'messaging/registration-token-not-registered' : null,
    };
  }
}

export interface TestApp {
  app: FastifyInstance;
  ctx: AppContext;
  env: Env;
  db: Collections;
  client: MongoClient;
  storage: LocalStorageDriver;
  storageDir: string;
  push: PushService;
  mailer: CapturingMailer;
  clock: TestClock;
  outbox: OutboxWorker;
  close: () => Promise<void>;
}

export interface TestAppOptions {
  envOverrides?: Record<string, string>;
  push?: PushService;
  outbox?: Partial<{ backoffBaseMs: number; maxAttempts: number }>;
  /** Local JWKS used to verify Google ID tokens (requires GOOGLE_CLIENT_IDS in envOverrides). */
  googleKeys?: JWTVerifyGetKey;
  /** Trivia question source; defaults to the built-in set (TRIVIA_PROVIDER=builtin: no network). */
  trivia?: TriviaSource;
}

export async function createTestApp(options: TestAppOptions = {}): Promise<TestApp> {
  const mongoUri = inject('mongoUri');
  const dbName = `t_${randomUUID().replace(/-/g, '').slice(0, 20)}`;
  const storageDir = mkdtempSync(path.join(tmpdir(), 'risaina-storage-'));
  const clock = new TestClock();

  const env = loadEnv({
    NODE_ENV: 'test',
    HOST: '127.0.0.1',
    PORT: '0',
    MONGODB_URI: mongoUri,
    MONGODB_DB: dbName,
    JWT_SECRET: TEST_JWT_SECRET,
    CORS_ORIGINS: '*',
    PUBLIC_BASE_URL: 'http://127.0.0.1:0',
    STORAGE_DRIVER: 'local',
    LOCAL_STORAGE_DIR: storageDir,
    LOG_LEVEL: 'silent',
    RATE_LIMIT_GLOBAL_MAX: '10000',
    RATE_LIMIT_AUTH_MAX: '1000',
    PING_COOLDOWN_SECONDS: '15',
    WS_AUTH_TIMEOUT_MS: '400',
    WS_PING_INTERVAL_MS: '1000',
    WS_IDLE_TIMEOUT_MS: '5000',
    TRIVIA_PROVIDER: 'builtin',
    ...options.envOverrides,
  });

  const client = new MongoClient(mongoUri);
  await client.connect();
  const mongoDb = client.db(dbName);
  await prepareDatabase(mongoDb);

  const storage = new LocalStorageDriver(
    {
      dir: storageDir,
      publicBaseUrl: env.publicBaseUrl,
      secret: env.jwtSecret,
      routePrefix: LOCAL_STORAGE_ROUTE_PREFIX,
    },
    clock.now,
  );
  const push = options.push ?? new DisabledPushService('test');
  const mailer = new CapturingMailer();
  const logger = pino({ level: 'silent' });

  const { app, ctx } = await buildApp({
    env,
    mongo: { client, db: mongoDb },
    storage,
    push,
    mailer,
    google:
      env.google.enabled && options.googleKeys
        ? createGoogleVerifier({ clientIds: env.google.clientIds, keys: options.googleKeys, now: clock.now })
        : null,
    ...(options.trivia ? { trivia: options.trivia } : {}),
    now: clock.now,
    logger,
    version: 'test',
  });
  const db = getCollections(mongoDb);
  const outbox = new OutboxWorker(db, push, clock.now, logger, {
    pollMs: 50,
    leaseMs: 30_000,
    maxAttempts: options.outbox?.maxAttempts ?? env.outbox.maxAttempts,
    backoffBaseMs: options.outbox?.backoffBaseMs ?? 0,
  });
  await app.ready();

  return {
    app,
    ctx,
    env,
    db,
    client,
    storage,
    storageDir,
    push,
    mailer,
    clock,
    outbox,
    close: async () => {
      await outbox.stop();
      await app.close();
      await mongoDb.dropDatabase();
      await client.close();
      rmSync(storageDir, { recursive: true, force: true });
    },
  };
}

// --- request helpers -------------------------------------------------------------------------

export interface Session {
  user: {
    id: string;
    displayName: string;
    avatarMediaId: string | null;
    createdAt: string;
    emailVerified: boolean;
    hasPassword: boolean;
  };
  tokens: { accessToken: string; accessExpiresAt: string; refreshToken: string; refreshExpiresAt: string };
}

export const API = '/api/v1';

export async function register(
  app: FastifyInstance,
  name: string,
  password = 'correct-horse-battery',
  email = `${name.toLowerCase()}-${randomUUID().slice(0, 8)}@example.test`,
): Promise<Session> {
  const res = await app.inject({
    method: 'POST',
    url: `${API}/auth/register`,
    payload: { email, password, displayName: name },
  });
  if (res.statusCode !== 201) throw new Error(`register failed: ${res.statusCode} ${res.body}`);
  return res.json<Session>();
}

export function bearer(session: Session | string): { authorization: string } {
  const token = typeof session === 'string' ? session : session.tokens.accessToken;
  return { authorization: `Bearer ${token}` };
}

/** Pairs two users through the real invitation flow and returns the couple id. */
export async function pair(app: FastifyInstance, inviter: Session, acceptor: Session): Promise<string> {
  const inv = await app.inject({
    method: 'POST',
    url: `${API}/invitations`,
    headers: bearer(inviter),
    payload: {},
  });
  if (inv.statusCode !== 201) throw new Error(`invite failed: ${inv.statusCode} ${inv.body}`);
  const code = inv.json<{ invitation: { code: string } }>().invitation.code;
  const acc = await app.inject({
    method: 'POST',
    url: `${API}/invitations/accept`,
    headers: bearer(acceptor),
    payload: { code },
  });
  if (acc.statusCode !== 200) throw new Error(`accept failed: ${acc.statusCode} ${acc.body}`);
  return acc.json<{ couple: { id: string } }>().couple.id;
}

export async function sendPing(app: FastifyInstance, sender: Session, body: Record<string, unknown> = {}) {
  return app.inject({
    method: 'POST',
    url: `${API}/pings`,
    headers: bearer(sender),
    payload: { type: 'hug', emoji: '🤗', idempotencyKey: randomUUID(), ...body },
  });
}

/** Minimal valid PNG (1x1) for upload tests — real magic bytes so file-type recognises it. */
export const TINY_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
);

/** Upload a photo through the real presign → signed PUT → complete journey. Returns the media id. */
export async function uploadPhoto(
  t: TestApp,
  owner: Session,
  bytes: Buffer = TINY_PNG,
  category = 'photo',
): Promise<string> {
  const create = await t.app.inject({
    method: 'POST',
    url: `${API}/media/uploads`,
    headers: bearer(owner),
    payload: { category, mimeType: 'image/png', sizeBytes: bytes.byteLength },
  });
  if (create.statusCode !== 201) throw new Error(`media create failed: ${create.statusCode} ${create.body}`);
  const { media, upload } = create.json<{
    media: { id: string };
    upload: { url: string; headers: Record<string, string> };
  }>();
  const put = await t.app.inject({
    method: 'PUT',
    url: upload.url,
    headers: { 'content-type': 'image/png' },
    payload: bytes,
  });
  if (put.statusCode !== 204) throw new Error(`upload failed: ${put.statusCode} ${put.body}`);
  const complete = await t.app.inject({
    method: 'POST',
    url: `${API}/media/${media.id}/complete`,
    headers: bearer(owner),
  });
  if (complete.statusCode !== 200)
    throw new Error(`complete failed: ${complete.statusCode} ${complete.body}`);
  return media.id;
}
