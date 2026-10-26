import { existsSync, readFileSync } from 'node:fs';
import { z } from 'zod';

/**
 * Zod-validated environment. `loadEnv()` throws an `EnvError` with a readable, secret-free
 * message when the environment is invalid so the process fails fast at boot.
 */

const MiB = 1024 * 1024;

const intFromEnv = (def: number, min = 0) => z.coerce.number().int().min(min).default(def);

const rawSchema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    LOG_LEVEL: z.enum(['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent']).default('info'),
    HOST: z.string().min(1, 'HOST is required'),
    PORT: z.coerce.number().int().min(0).max(65535),
    MONGODB_URI: z
      .string()
      .min(1, 'MONGODB_URI is required')
      .refine((s) => s.startsWith('mongodb://') || s.startsWith('mongodb+srv://'), {
        message: 'MONGODB_URI must start with mongodb:// or mongodb+srv://',
      }),
    MONGODB_DB: z.string().min(1, 'MONGODB_DB is required'),
    JWT_SECRET: z.string().min(32, 'JWT_SECRET must be at least 32 characters'),
    CORS_ORIGINS: z
      .string()
      .min(1, 'CORS_ORIGINS is required (comma separated, or "*" in development)')
      .transform((s) =>
        s
          .split(',')
          .map((x) => x.trim())
          .filter(Boolean),
      ),
    PUBLIC_BASE_URL: z.url().optional(),

    STORAGE_DRIVER: z.enum(['local', 'r2']).optional(),
    LOCAL_STORAGE_DIR: z.string().min(1).default('.data/storage'),
    R2_ACCOUNT_ID: z.string().min(1).optional(),
    R2_ACCESS_KEY_ID: z.string().min(1).optional(),
    R2_SECRET_ACCESS_KEY: z.string().min(1).optional(),
    R2_BUCKET: z.string().min(1).optional(),
    R2_ENDPOINT: z.url().optional(),

    FIREBASE_SERVICE_ACCOUNT_JSON: z.string().min(1).optional(),
    MAILER: z.enum(['console', 'resend']).default('console'),
    RESEND_API_KEY: z.string().min(1).optional(),
    MAIL_FROM: z.string().min(3).max(200).optional(),
    GOOGLE_CLIENT_IDS: z
      .string()
      .optional()
      .transform((s) =>
        (s ?? '')
          .split(',')
          .map((x) => x.trim())
          .filter(Boolean),
      ),

    ACCESS_TOKEN_TTL_SECONDS: intFromEnv(15 * 60, 60),
    REFRESH_TOKEN_TTL_DAYS: intFromEnv(30, 1),
    PASSWORD_RESET_TTL_MINUTES: intFromEnv(30, 1),
    EMAIL_VERIFICATION_TTL_MINUTES: intFromEnv(30, 1),
    EMAIL_VERIFICATION_RESEND_COOLDOWN_SECONDS: intFromEnv(60, 0),
    EMAIL_VERIFICATION_MAX_PER_HOUR: intFromEnv(5, 1),
    INVITATION_TTL_HOURS: intFromEnv(24, 1),
    INVITATION_ACCEPT_MAX_ATTEMPTS: intFromEnv(5, 1),
    INVITATION_ACCEPT_WINDOW_MINUTES: intFromEnv(15, 1),
    PING_COOLDOWN_SECONDS: intFromEnv(15, 0),
    PINGS_PER_HOUR: intFromEnv(40, 1),
    MEDIA_MAX_PHOTO_BYTES: intFromEnv(10 * MiB, 1),
    MEDIA_MAX_VOICE_BYTES: intFromEnv(8 * MiB, 1),
    MEDIA_MAX_VOICE_DURATION_MS: intFromEnv(180_000, 1),
    MEDIA_MAX_AVATAR_BYTES: intFromEnv(3 * MiB, 1),
    MEDIA_MAX_WALLPAPER_BYTES: intFromEnv(12 * MiB, 1),
    MEDIA_UPLOAD_URL_TTL_SECONDS: intFromEnv(10 * 60, 1),
    MEDIA_DOWNLOAD_URL_TTL_SECONDS: intFromEnv(15 * 60, 1),
    MEDIA_ABANDONED_UPLOAD_HOURS: intFromEnv(24, 1),
    VIEW_ONCE_URL_TTL_SECONDS: intFromEnv(120, 1),
    VIEW_ONCE_UNOPENED_DAYS: intFromEnv(7, 1),
    RATE_LIMIT_GLOBAL_MAX: intFromEnv(300, 1),
    RATE_LIMIT_GLOBAL_WINDOW_MS: intFromEnv(60_000, 1000),
    RATE_LIMIT_AUTH_MAX: intFromEnv(10, 1),
    RATE_LIMIT_AUTH_WINDOW_MS: intFromEnv(15 * 60_000, 1000),
    RATE_LIMIT_LIST_WRITES_PER_MIN: intFromEnv(120, 1),
    RATE_LIMIT_WIDGET_WRITES_PER_MIN: intFromEnv(30, 1),
    RATE_LIMIT_GAME_WRITES_PER_MIN: intFromEnv(30, 1),
    OUTBOX_POLL_MS: intFromEnv(1000, 50),
    OUTBOX_LEASE_MS: intFromEnv(30_000, 1000),
    OUTBOX_MAX_ATTEMPTS: intFromEnv(5, 1),
    OUTBOX_BACKOFF_BASE_MS: intFromEnv(2000, 0),
    CLEANUP_INTERVAL_MS: intFromEnv(15 * 60_000, 1000),
    WS_AUTH_TIMEOUT_MS: intFromEnv(5000, 100),
    WS_PING_INTERVAL_MS: intFromEnv(30_000, 1000),
    WS_IDLE_TIMEOUT_MS: intFromEnv(90_000, 1000),
    WS_MAX_PENDING_SENDS: intFromEnv(500, 1),
    // Games stream: where trivia questions come from. 'builtin' never touches the network (tests).
    TRIVIA_PROVIDER: z.enum(['opentdb', 'builtin']).default('opentdb'),
    TRIVIA_TIMEOUT_MS: intFromEnv(3500, 100),
    TRIVIA_MIN_INTERVAL_MS: intFromEnv(5500, 0),
  })
  .superRefine((v, ctx) => {
    const driver = v.STORAGE_DRIVER ?? (v.NODE_ENV === 'production' ? 'r2' : 'local');
    if (driver === 'r2') {
      for (const k of [
        'R2_ACCOUNT_ID',
        'R2_ACCESS_KEY_ID',
        'R2_SECRET_ACCESS_KEY',
        'R2_BUCKET',
        'R2_ENDPOINT',
      ] as const) {
        if (!v[k])
          ctx.addIssue({ code: 'custom', path: [k], message: `${k} is required when STORAGE_DRIVER=r2` });
      }
    }
    if (driver === 'local' && v.NODE_ENV === 'production') {
      ctx.addIssue({
        code: 'custom',
        path: ['STORAGE_DRIVER'],
        message: 'STORAGE_DRIVER=local is a development-only driver and is refused when NODE_ENV=production',
      });
    }
    if (v.MAILER === 'resend') {
      for (const k of ['RESEND_API_KEY', 'MAIL_FROM'] as const) {
        if (!v[k])
          ctx.addIssue({ code: 'custom', path: [k], message: `${k} is required when MAILER=resend` });
      }
    }
    if (v.MAIL_FROM && !/^[^<>\r\n]*<[^<>@\s]+@[^<>@\s]+>$|^[^<>@\s\r\n]+@[^<>@\s]+$/.test(v.MAIL_FROM)) {
      ctx.addIssue({
        code: 'custom',
        path: ['MAIL_FROM'],
        message: 'MAIL_FROM must look like "Name <hello@example.com>" or "hello@example.com"',
      });
    }
    if (v.NODE_ENV === 'production' && v.CORS_ORIGINS.includes('*')) {
      ctx.addIssue({
        code: 'custom',
        path: ['CORS_ORIGINS'],
        message: 'CORS_ORIGINS="*" is not allowed in production',
      });
    }
  });

export type FirebaseServiceAccount = {
  projectId: string;
  clientEmail: string;
  privateKey: string;
};

export type StorageConfig =
  | { driver: 'local'; dir: string }
  | {
      driver: 'r2';
      accountId: string;
      accessKeyId: string;
      secretAccessKey: string;
      bucket: string;
      endpoint: string;
    };

export type PushConfig =
  | { enabled: false; reason: string }
  | { enabled: true; serviceAccount: FirebaseServiceAccount };

export type MailerConfig = { driver: 'console' } | { driver: 'resend'; apiKey: string; from: string };

/** Google sign-in: `clientIds` are the OAuth *Web* client IDs accepted as the ID token audience. */
export type GoogleConfig = { enabled: false } | { enabled: true; clientIds: string[] };

export interface Env {
  nodeEnv: 'development' | 'test' | 'production';
  isProduction: boolean;
  logLevel: string;
  host: string;
  port: number;
  publicBaseUrl: string;
  corsOrigins: string[];
  mongo: { uri: string; db: string };
  jwtSecret: string;
  storage: StorageConfig;
  push: PushConfig;
  mailer: MailerConfig;
  google: GoogleConfig;
  auth: {
    accessTokenTtlSeconds: number;
    refreshTokenTtlDays: number;
    passwordResetTtlMinutes: number;
  };
  emailVerification: { ttlMinutes: number; resendCooldownSeconds: number; maxPerHour: number };
  invitations: { ttlHours: number; acceptMaxAttempts: number; acceptWindowMinutes: number };
  pings: { cooldownSeconds: number; perHour: number };
  media: {
    maxPhotoBytes: number;
    maxVoiceBytes: number;
    maxVoiceDurationMs: number;
    maxAvatarBytes: number;
    maxWallpaperBytes: number;
    uploadUrlTtlSeconds: number;
    downloadUrlTtlSeconds: number;
    abandonedUploadHours: number;
  };
  viewOnce: {
    /** TTL of the one download URL returned by POST /messages/:id/open. */
    urlTtlSeconds: number;
    /** Unopened view-once photos are purged after this many days. */
    unopenedDays: number;
  };
  rateLimit: {
    globalMax: number;
    globalWindowMs: number;
    authMax: number;
    authWindowMs: number;
    /** POST/PATCH/DELETE /lists per user per minute (shared budget across the three). */
    listWritesPerMinute: number;
    /** PUT /widget-board per user per minute. */
    widgetWritesPerMinute: number;
    /** POST /games and /games/:id/* per user per minute. */
    gameWritesPerMinute: number;
  };
  outbox: { pollMs: number; leaseMs: number; maxAttempts: number; backoffBaseMs: number };
  cleanup: { intervalMs: number };
  ws: { authTimeoutMs: number; pingIntervalMs: number; idleTimeoutMs: number; maxPendingSends: number };
  /** Trivia duel questions: Open Trivia DB (server-side only) with a built-in fallback set. */
  trivia: { provider: 'opentdb' | 'builtin'; timeoutMs: number; minIntervalMs: number };
}

export class EnvError extends Error {
  override name = 'EnvError';
}

/** Parse the Firebase service account from a path, raw JSON, or base64 JSON. Never logs content. */
function parseServiceAccount(raw: string): FirebaseServiceAccount {
  let text: string | null = null;
  const trimmed = raw.trim();
  if (trimmed.startsWith('{')) {
    text = trimmed;
  } else if (existsSync(trimmed)) {
    text = readFileSync(trimmed, 'utf8');
  } else {
    try {
      text = Buffer.from(trimmed, 'base64').toString('utf8');
    } catch {
      text = null;
    }
  }
  if (!text) throw new EnvError('FIREBASE_SERVICE_ACCOUNT_JSON: could not read as path, JSON or base64 JSON');
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new EnvError('FIREBASE_SERVICE_ACCOUNT_JSON: content is not valid JSON');
  }
  const sa = z
    .object({
      project_id: z.string().min(1),
      client_email: z.string().min(1),
      private_key: z.string().min(1),
    })
    .safeParse(parsed);
  if (!sa.success) {
    throw new EnvError('FIREBASE_SERVICE_ACCOUNT_JSON: missing project_id, client_email or private_key');
  }
  return {
    projectId: sa.data.project_id,
    clientEmail: sa.data.client_email,
    privateKey: sa.data.private_key,
  };
}

export function loadEnv(source: NodeJS.ProcessEnv = process.env): Env {
  const result = rawSchema.safeParse(source);
  if (!result.success) {
    // Only variable names and messages are reported; values are never included.
    const lines = result.error.issues.map((i) => `  - ${i.path.join('.') || '(root)'}: ${i.message}`);
    throw new EnvError(`Invalid environment configuration:\n${lines.join('\n')}`);
  }
  const v = result.data;
  const driver = v.STORAGE_DRIVER ?? (v.NODE_ENV === 'production' ? 'r2' : 'local');

  const storage: StorageConfig =
    driver === 'r2'
      ? {
          driver: 'r2',
          accountId: v.R2_ACCOUNT_ID!,
          accessKeyId: v.R2_ACCESS_KEY_ID!,
          secretAccessKey: v.R2_SECRET_ACCESS_KEY!,
          bucket: v.R2_BUCKET!,
          endpoint: v.R2_ENDPOINT!,
        }
      : { driver: 'local', dir: v.LOCAL_STORAGE_DIR };

  const push: PushConfig = v.FIREBASE_SERVICE_ACCOUNT_JSON
    ? { enabled: true, serviceAccount: parseServiceAccount(v.FIREBASE_SERVICE_ACCOUNT_JSON) }
    : { enabled: false, reason: 'FIREBASE_SERVICE_ACCOUNT_JSON not set' };

  const hostForUrl = v.HOST === '0.0.0.0' || v.HOST === '::' ? 'localhost' : v.HOST;
  const publicBaseUrl = (v.PUBLIC_BASE_URL ?? `http://${hostForUrl}:${v.PORT}`).replace(/\/+$/, '');

  return {
    nodeEnv: v.NODE_ENV,
    isProduction: v.NODE_ENV === 'production',
    logLevel: v.LOG_LEVEL,
    host: v.HOST,
    port: v.PORT,
    publicBaseUrl,
    corsOrigins: v.CORS_ORIGINS,
    mongo: { uri: v.MONGODB_URI, db: v.MONGODB_DB },
    jwtSecret: v.JWT_SECRET,
    storage,
    push,
    mailer:
      v.MAILER === 'resend'
        ? { driver: 'resend', apiKey: v.RESEND_API_KEY!, from: v.MAIL_FROM! }
        : { driver: 'console' },
    google:
      v.GOOGLE_CLIENT_IDS.length > 0 ? { enabled: true, clientIds: v.GOOGLE_CLIENT_IDS } : { enabled: false },
    auth: {
      accessTokenTtlSeconds: v.ACCESS_TOKEN_TTL_SECONDS,
      refreshTokenTtlDays: v.REFRESH_TOKEN_TTL_DAYS,
      passwordResetTtlMinutes: v.PASSWORD_RESET_TTL_MINUTES,
    },
    emailVerification: {
      ttlMinutes: v.EMAIL_VERIFICATION_TTL_MINUTES,
      resendCooldownSeconds: v.EMAIL_VERIFICATION_RESEND_COOLDOWN_SECONDS,
      maxPerHour: v.EMAIL_VERIFICATION_MAX_PER_HOUR,
    },
    invitations: {
      ttlHours: v.INVITATION_TTL_HOURS,
      acceptMaxAttempts: v.INVITATION_ACCEPT_MAX_ATTEMPTS,
      acceptWindowMinutes: v.INVITATION_ACCEPT_WINDOW_MINUTES,
    },
    pings: { cooldownSeconds: v.PING_COOLDOWN_SECONDS, perHour: v.PINGS_PER_HOUR },
    media: {
      maxPhotoBytes: v.MEDIA_MAX_PHOTO_BYTES,
      maxVoiceBytes: v.MEDIA_MAX_VOICE_BYTES,
      maxVoiceDurationMs: v.MEDIA_MAX_VOICE_DURATION_MS,
      maxAvatarBytes: v.MEDIA_MAX_AVATAR_BYTES,
      maxWallpaperBytes: v.MEDIA_MAX_WALLPAPER_BYTES,
      uploadUrlTtlSeconds: v.MEDIA_UPLOAD_URL_TTL_SECONDS,
      downloadUrlTtlSeconds: v.MEDIA_DOWNLOAD_URL_TTL_SECONDS,
      abandonedUploadHours: v.MEDIA_ABANDONED_UPLOAD_HOURS,
    },
    viewOnce: { urlTtlSeconds: v.VIEW_ONCE_URL_TTL_SECONDS, unopenedDays: v.VIEW_ONCE_UNOPENED_DAYS },
    rateLimit: {
      globalMax: v.RATE_LIMIT_GLOBAL_MAX,
      globalWindowMs: v.RATE_LIMIT_GLOBAL_WINDOW_MS,
      authMax: v.RATE_LIMIT_AUTH_MAX,
      authWindowMs: v.RATE_LIMIT_AUTH_WINDOW_MS,
      listWritesPerMinute: v.RATE_LIMIT_LIST_WRITES_PER_MIN,
      widgetWritesPerMinute: v.RATE_LIMIT_WIDGET_WRITES_PER_MIN,
      gameWritesPerMinute: v.RATE_LIMIT_GAME_WRITES_PER_MIN,
    },
    outbox: {
      pollMs: v.OUTBOX_POLL_MS,
      leaseMs: v.OUTBOX_LEASE_MS,
      maxAttempts: v.OUTBOX_MAX_ATTEMPTS,
      backoffBaseMs: v.OUTBOX_BACKOFF_BASE_MS,
    },
    cleanup: { intervalMs: v.CLEANUP_INTERVAL_MS },
    ws: {
      authTimeoutMs: v.WS_AUTH_TIMEOUT_MS,
      pingIntervalMs: v.WS_PING_INTERVAL_MS,
      idleTimeoutMs: v.WS_IDLE_TIMEOUT_MS,
      maxPendingSends: v.WS_MAX_PENDING_SENDS,
    },
    trivia: {
      provider: v.TRIVIA_PROVIDER,
      timeoutMs: v.TRIVIA_TIMEOUT_MS,
      minIntervalMs: v.TRIVIA_MIN_INTERVAL_MS,
    },
  };
}
