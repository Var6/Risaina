import { buildApp } from './app.js';
import { EnvError, loadEnv } from './config/env.js';
import { connectMongo } from './db/client.js';
import { getCollections } from './db/collections.js';
import { prepareDatabase } from './db/index.js';
import { createLogger } from './lib/logger.js';
import { createMailer } from './services/mailer/index.js';
import { createPushService } from './services/push/index.js';
import { createStorage } from './services/storage/index.js';
import { CleanupWorker } from './workers/cleanup.js';
import { OutboxWorker } from './workers/outbox.js';

async function main(): Promise<void> {
  let env: ReturnType<typeof loadEnv>;
  try {
    env = loadEnv();
  } catch (err) {
    if (err instanceof EnvError) {
      process.stderr.write(`${err.message}\n`);
      process.exit(1);
    }
    throw err;
  }

  const logger = createLogger(env.logLevel, env.nodeEnv === 'development');
  const now = () => new Date();

  logger.info(
    {
      nodeEnv: env.nodeEnv,
      storageDriver: env.storage.driver,
      pushEnabled: env.push.enabled,
      mailer: env.mailer.driver,
      googleSignIn: env.google.enabled,
    },
    'starting risaina backend',
  );

  const mongo = await connectMongo(env.mongo.uri, env.mongo.db);
  await prepareDatabase(mongo.db);
  logger.info({ db: env.mongo.db }, 'mongo connected; validators and indexes ensured');

  const storage = createStorage(env, now);
  const push = createPushService(env);
  const mailer = createMailer(env, logger, now);
  if (!push.enabled)
    logger.warn(
      'push notifications are DISABLED (no FIREBASE_SERVICE_ACCOUNT_JSON); outbox will mark events skipped',
    );
  if (storage.driver === 'local')
    logger.warn(
      { dir: env.storage.driver === 'local' ? env.storage.dir : undefined },
      'using the development-only local storage driver',
    );

  const { app, ctx } = await buildApp({ env, mongo, storage, push, mailer, now, logger });

  const db = getCollections(mongo.db);
  const outbox = new OutboxWorker(db, push, now, logger, env.outbox);
  const cleanup = new CleanupWorker(db, storage, now, logger, {
    intervalMs: env.cleanup.intervalMs,
    abandonedUploadHours: env.media.abandonedUploadHours,
    viewOnceUrlTtlSeconds: env.viewOnce.urlTtlSeconds,
    viewOnceUnopenedDays: env.viewOnce.unopenedDays,
  });
  ctx.events.onEmitted(() => outbox.wake());

  await app.listen({ host: env.host, port: env.port });
  outbox.start();
  cleanup.start();
  logger.info({ host: env.host, port: env.port, docs: `${env.publicBaseUrl}/docs` }, 'listening');

  let shuttingDown = false;
  const shutdown = async (signal: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    logger.info({ signal }, 'shutting down');
    const forceExit = setTimeout(() => {
      logger.error('graceful shutdown timed out; forcing exit');
      process.exit(1);
    }, 15_000);
    forceExit.unref();
    try {
      await Promise.all([outbox.stop(), cleanup.stop()]);
      ctx.hub.closeAll();
      await app.close();
      await mongo.client.close();
      logger.info('shutdown complete');
      process.exit(0);
    } catch (err) {
      logger.error({ err }, 'shutdown failed');
      process.exit(1);
    }
  };
  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('unhandledRejection', (reason) => {
    logger.error({ err: reason }, 'unhandled rejection');
  });
}

main().catch((err) => {
  process.stderr.write(`fatal: ${err instanceof Error ? (err.stack ?? err.message) : String(err)}\n`);
  process.exit(1);
});
