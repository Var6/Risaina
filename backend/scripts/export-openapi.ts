/**
 * Writes the generated OpenAPI document to backend/docs/openapi.json without touching a database:
 * the Mongo client is created lazily and never connected (no route handler runs).
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { MongoClient } from 'mongodb';
import { pino } from 'pino';
import { buildApp } from '../src/app.js';
import { loadEnv } from '../src/config/env.js';
import { ConsoleMailer } from '../src/services/mailer/console.js';
import { DisabledPushService } from '../src/services/push/disabled.js';
import { LocalStorageDriver } from '../src/services/storage/local.js';

const env = loadEnv({
  NODE_ENV: 'development',
  HOST: '127.0.0.1',
  PORT: '8080',
  MONGODB_URI: 'mongodb://127.0.0.1:27017',
  MONGODB_DB: 'risaina_openapi_export',
  JWT_SECRET: 'openapi-export-only-secret-not-used-for-anything-real',
  CORS_ORIGINS: '*',
  PUBLIC_BASE_URL: 'http://localhost:8080',
  STORAGE_DRIVER: 'local',
});

const client = new MongoClient(env.mongo.uri);
const logger = pino({ level: 'silent' });
const { app } = await buildApp({
  env,
  mongo: { client, db: client.db(env.mongo.db) },
  storage: new LocalStorageDriver({
    dir: '.data/openapi-export',
    publicBaseUrl: env.publicBaseUrl,
    secret: env.jwtSecret,
    routePrefix: '/api/v1/_local-storage',
  }),
  push: new DisabledPushService('export'),
  mailer: new ConsoleMailer(logger, false),
  logger,
});
await app.ready();
const doc = app.swagger();
mkdirSync(new URL('../docs/', import.meta.url), { recursive: true });
const out = new URL('../docs/openapi.json', import.meta.url);
writeFileSync(out, `${JSON.stringify(doc, null, 2)}\n`);
await app.close();
await client.close();
process.stdout.write(
  `wrote ${out.pathname} (${Object.keys((doc as { paths?: object }).paths ?? {}).length} paths)\n`,
);
