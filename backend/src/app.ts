import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import type { IncomingMessage, Server, ServerResponse } from 'node:http';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import sensible from '@fastify/sensible';
import swagger from '@fastify/swagger';
import swaggerUi from '@fastify/swagger-ui';
import underPressure from '@fastify/under-pressure';
import websocket from '@fastify/websocket';
import Fastify, { type FastifyBaseLogger, type FastifyInstance } from 'fastify';
import {
  jsonSchemaTransform,
  jsonSchemaTransformObject,
  serializerCompiler,
  validatorCompiler,
} from 'fastify-type-provider-zod';
import type { Db, MongoClient } from 'mongodb';
import type { Logger } from 'pino';
import type { AppContext } from './app-context.js';
import type { Env } from './config/env.js';
import { getCollections } from './db/collections.js';
import { AttemptLimiter } from './lib/attempt-limiter.js';
import { createLogger } from './lib/logger.js';
import { type Clock, systemClock } from './lib/time.js';
import { authRoutes } from './modules/auth/routes.js';
import { TokenService } from './modules/auth/tokens.js';
import { coupleRoutes } from './modules/couples/routes.js';
import { deviceRoutes } from './modules/devices/routes.js';
import { eventRoutes } from './modules/events/routes.js';
import { healthRoutes } from './modules/health/routes.js';
import { invitationRoutes } from './modules/invitations/routes.js';
import { meRoutes } from './modules/me/routes.js';
import { mediaRoutes } from './modules/media/routes.js';
import { memoryRoutes } from './modules/memories/routes.js';
import { messageRoutes } from './modules/messages/routes.js';
import { milestoneRoutes } from './modules/milestones/routes.js';
import { pingRoutes } from './modules/pings/routes.js';
import './modules/shared/schemas.js';
import { registerAuthDecorators } from './plugins/authenticate.js';
import { registerErrorHandler } from './plugins/error-handler.js';
import { websocketRoutes } from './plugins/websocket.js';
import { WsHub } from './services/events/hub.js';
import { EventPublisher } from './services/events/publisher.js';
import type { Mailer } from './services/mailer/types.js';
import type { PushService } from './services/push/types.js';
import { LocalStorageDriver, registerLocalStorageRoutes } from './services/storage/index.js';
import type { StorageService } from './services/storage/types.js';

export const API_PREFIX = '/api/v1';

export interface BuildAppOptions {
  env: Env;
  mongo: { client: MongoClient; db: Db };
  storage: StorageService;
  push: PushService;
  mailer: Mailer;
  now?: Clock;
  logger?: Logger;
  version?: string;
}

export interface BuiltApp {
  app: FastifyInstance;
  ctx: AppContext;
}

export function readPackageVersion(): string {
  try {
    const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')) as {
      version?: string;
    };
    return pkg.version ?? '0.0.0';
  } catch {
    return '0.0.0';
  }
}

export async function buildApp(opts: BuildAppOptions): Promise<BuiltApp> {
  const { env } = opts;
  const now = opts.now ?? systemClock;
  const logger = opts.logger ?? createLogger(env.logLevel, env.nodeEnv === 'development');

  // Logger generic pinned to FastifyBaseLogger so plugins typed against the default instance fit.
  const app = Fastify<Server, IncomingMessage, ServerResponse, FastifyBaseLogger>({
    loggerInstance: logger,
    genReqId: () => randomUUID(),
    requestIdHeader: false,
    trustProxy: env.isProduction,
    bodyLimit: 1024 * 1024,
  });

  app.setValidatorCompiler(validatorCompiler);
  app.setSerializerCompiler(serializerCompiler);

  const hub = new WsHub(env.ws.maxPendingSends, logger);
  const db = getCollections(opts.mongo.db);
  const ctx: AppContext = {
    env,
    db,
    mongoDb: opts.mongo.db,
    storage: opts.storage,
    push: opts.push,
    mailer: opts.mailer,
    hub,
    events: new EventPublisher(db, hub, now),
    tokens: new TokenService(env.jwtSecret, env.auth.accessTokenTtlSeconds, now),
    inviteLimiter: new AttemptLimiter(
      env.invitations.acceptMaxAttempts,
      env.invitations.acceptWindowMinutes * 60_000,
      now,
    ),
    now,
    logger,
    version: opts.version ?? readPackageVersion(),
    startedAt: now(),
  };

  // --- cross-cutting plugins ---------------------------------------------------------------
  await app.register(helmet, { contentSecurityPolicy: false, crossOriginEmbedderPolicy: false });
  await app.register(cors, {
    origin: env.corsOrigins.includes('*') ? true : env.corsOrigins,
    methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'],
    allowedHeaders: ['Authorization', 'Content-Type', 'X-Request-Id'],
    maxAge: 600,
  });
  await app.register(sensible);
  await app.register(underPressure, {
    maxEventLoopDelay: 1500,
    maxEventLoopUtilization: 0.98,
    retryAfter: 10,
    exposeStatusRoute: false,
  });
  await app.register(rateLimit, {
    global: true,
    max: env.rateLimit.globalMax,
    timeWindow: env.rateLimit.globalWindowMs,
    keyGenerator: (req) => req.ip,
    addHeadersOnExceeding: {
      'x-ratelimit-limit': true,
      'x-ratelimit-remaining': true,
      'x-ratelimit-reset': true,
    },
    addHeaders: {
      'x-ratelimit-limit': true,
      'x-ratelimit-remaining': true,
      'x-ratelimit-reset': true,
      'retry-after': true,
    },
  });
  await app.register(websocket, {
    options: { maxPayload: 16 * 1024, clientTracking: false },
    errorHandler: (_error, socket) => {
      socket.close(1011, 'websocket error');
    },
  });

  // --- OpenAPI -------------------------------------------------------------------------------
  await app.register(swagger, {
    openapi: {
      openapi: '3.1.0',
      info: {
        title: 'Risaina API',
        version: ctx.version,
        description: 'Private two-person relationship app. See docs/API.md for the design contract.',
      },
      servers: [{ url: env.publicBaseUrl }],
      components: {
        securitySchemes: { bearerAuth: { type: 'http', scheme: 'bearer', bearerFormat: 'JWT' } },
      },
      tags: [
        { name: 'Health' },
        { name: 'Auth' },
        { name: 'Me' },
        { name: 'Pairing' },
        { name: 'Couple' },
        { name: 'Devices' },
        { name: 'Pings' },
        { name: 'Messages' },
        { name: 'Media' },
        { name: 'Memories' },
        { name: 'Milestones' },
        { name: 'Events' },
      ],
    },
    transform: jsonSchemaTransform,
    transformObject: jsonSchemaTransformObject,
  });
  await app.register(swaggerUi, {
    routePrefix: '/docs',
    staticCSP: true,
    uiConfig: { docExpansion: 'list', deepLinking: true, persistAuthorization: true },
  });
  // Shared DTO schemas carry `.meta({ id })` (Zod global registry); jsonSchemaTransformObject
  // turns them into components.schemas with $ref links automatically.

  registerErrorHandler(app);
  registerAuthDecorators(app, { db: ctx.db, tokens: ctx.tokens, now });

  app.addHook('onSend', async (request, reply) => {
    reply.header('x-request-id', request.id);
  });

  // --- routes ------------------------------------------------------------------------------
  await app.register(
    async (api) => {
      await api.register(healthRoutes, ctx);
      await api.register(authRoutes, ctx);
      await api.register(meRoutes, ctx);
      await api.register(invitationRoutes, ctx);
      await api.register(coupleRoutes, ctx);
      await api.register(deviceRoutes, ctx);
      await api.register(pingRoutes, ctx);
      await api.register(messageRoutes, ctx);
      await api.register(mediaRoutes, ctx);
      await api.register(memoryRoutes, ctx);
      await api.register(milestoneRoutes, ctx);
      await api.register(eventRoutes, ctx);
      await api.register(websocketRoutes, ctx);
      if (opts.storage instanceof LocalStorageDriver) {
        const maxObjectBytes =
          Math.max(
            env.media.maxPhotoBytes,
            env.media.maxVoiceBytes,
            env.media.maxAvatarBytes,
            env.media.maxWallpaperBytes,
          ) + 1024;
        await api.register(registerLocalStorageRoutes, {
          prefix: '/_local-storage',
          storage: opts.storage,
          nodeEnv: env.nodeEnv,
          maxObjectBytes,
        });
      }
    },
    { prefix: API_PREFIX },
  );

  app.addHook('onClose', async () => {
    hub.closeAll();
  });

  return { app, ctx };
}
