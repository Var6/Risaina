# Risaina backend

Fastify 5 / TypeScript / MongoDB API for Risaina, a private two-person long-distance-relationship
app. Implements `docs/API.md` (route contract), `docs/COUNCIL_DECISIONS.md` (D-03 … D-08) and the
backend section of `docs/ARCHITECTURE.md`.

- Durable per-couple event log with monotonic `seq`, WebSocket hub with replay, and a push outbox
  worker (D-04).
- Self-hosted auth: argon2id, 15-minute HS256 access tokens, rotating opaque refresh tokens with
  reuse detection (D-03).
- Object storage behind one interface: Cloudflare R2 (S3 API) for production, a signed-URL local
  driver for development (D-06).
- FCM data-only push via `firebase-admin`, or an honest **disabled** mode when no service account is
  configured (D-07).
- OpenAPI generated from the Zod schemas: `/docs` (UI) and `/docs/json`.

## Requirements

- Node ≥ 22 (developed on Node 26.5), pnpm 11.
- MongoDB ≥ 7 (standalone is fine; no transactions are used). Developed against MongoDB 8.3.
- For tests: `mongodb-memory-server` uses the system `mongod` at `/opt/homebrew/bin/mongod` when
  present (set in `test/setup/global-setup.ts`), so nothing is downloaded.

## Setup

```sh
cd backend
pnpm install
cp .env.example .env        # then edit JWT_SECRET etc.
pnpm dev                    # tsx watch, reads .env
curl -s localhost:8080/api/v1/health | jq
open http://localhost:8080/docs
```

`pnpm install` only runs install scripts for `argon2`, `esbuild`, `protobufjs` and `@firebase/util`
(see `pnpm-workspace.yaml`); `mongodb-memory-server` is explicitly prevented from downloading a
binary.

## Environment

Validated by `src/config/env.ts` (Zod). The process exits with a readable list of problems and never
prints values.

| Variable | Required | Notes |
|---|---|---|
| `NODE_ENV` | no | `development` (default) · `test` · `production` |
| `HOST`, `PORT` | yes | |
| `MONGODB_URI`, `MONGODB_DB` | yes | |
| `JWT_SECRET` | yes | ≥ 32 chars. Also seeds (via HKDF) the HMAC key of the local storage driver |
| `CORS_ORIGINS` | yes | comma separated; `*` allowed outside production |
| `PUBLIC_BASE_URL` | no | defaults to `http://HOST:PORT`; used in signed URLs and reset links |
| `STORAGE_DRIVER` | no | `local` (default in dev/test) or `r2`. `local` is refused in production |
| `LOCAL_STORAGE_DIR` | no | default `.data/storage` |
| `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `R2_ENDPOINT` | when `r2` | |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | no | path to the JSON file, raw JSON, or base64 JSON. Absent ⇒ push disabled |
| `MAILER` | no | only `console` exists |
| limits | no | see `.env.example` for every knob and its default (ping cooldown 15 s, 40 pings/h, media sizes, invitation TTL 24 h, token TTLs, rate limits, outbox/cleanup intervals, WS timeouts) |

## Scripts

| Script | What it does |
|---|---|
| `pnpm dev` | `tsx watch src/server.ts` with `.env` loaded |
| `pnpm build` | `tsc -p tsconfig.build.json` → `dist/` |
| `pnpm start` | `node dist/server.js` (loads `.env` if present) |
| `pnpm typecheck` | `tsc --noEmit` over src, test and scripts (TypeScript 7) |
| `pnpm test` / `pnpm test:watch` | Vitest against an in-memory mongod |
| `pnpm lint` / `pnpm lint:fix` | Biome (lint + format) |
| `pnpm openapi:export` | writes `docs/openapi.json` without connecting to a database |

## Layout

```
src/
  app.ts            buildApp(): plugins, routes, error handler, OpenAPI
  server.ts         entry: env → Mongo (validators + indexes) → services → app → workers → shutdown
  app-context.ts    the explicit dependency bag every module receives
  config/env.ts
  db/               types, typed collections, indexes, $jsonSchema validators
  plugins/          authenticate + requireCouple decorators, error handler, websocket route
  modules/<name>/   schemas.ts (Zod) · repo.ts (Mongo) · service.ts (rules) · routes.ts (HTTP)
  services/         storage (local | r2), push (fcm | disabled), mailer (console), events (hub, publisher)
  workers/          outbox push worker, cleanup job
  lib/              errors, ids, crypto, pagination, time, quiet hours, attempt limiter, logger
test/               Vitest suites (fastify.inject + real `ws` sockets against app.listen(port 0))
docs/               DATA_MODEL.md, openapi.json
```

## How requests are authorised

1. `authenticate` (preValidation) verifies the bearer JWT and checks the backing session is live
   (not revoked / expired) → `request.user = { id, sessionId }`. Logout, refresh-reuse detection,
   password reset and account deletion all revoke sessions and take effect immediately.
2. `requireCouple` (preHandler) loads the **active** couple from the user's `activeCoupleId` →
   `request.couple`, `request.partnerId`, or `409 not_paired`.
3. Every couple-scoped query uses `request.couple._id`. Request bodies/params never carry a couple id;
   strict Zod schemas reject unknown keys.

## Local storage mode (development only)

`STORAGE_DRIVER=local` stores objects under `LOCAL_STORAGE_DIR/<key>` (plus a `.meta.json`
sidecar with the content type). `POST /media/uploads` returns a PUT URL of the form
`PUBLIC_BASE_URL/api/v1/_local-storage/object?key=…&exp=…&ct=…&sig=…`; `GET /media/:id/url` returns a
similar GET URL. `sig` is an HMAC-SHA256 over method, key, expiry and content type with a key
derived from `JWT_SECRET`. The routes verify signature and expiry and reject anything else, so the
full presign → upload → complete (HEAD + magic-byte sniff with `file-type`) → signed download journey
runs with no cloud credentials. `loadEnv` refuses this driver when `NODE_ENV=production`, and the
routes refuse to register in production as a second guard. Object keys are always
`couples/{coupleId}/{category}/{mediaId}.{ext}` and are never accepted from clients.

## Enabling R2 and FCM later

**Cloudflare R2.** Create a private bucket and an API token with object read/write. Set
`STORAGE_DRIVER=r2` and the five `R2_*` variables (`R2_ENDPOINT` is
`https://<account-id>.r2.cloudflarestorage.com`). The driver uses AWS SDK v3 with path-style
addressing, presigned PUT (10 min) and GET (15 min). Not exercised against a live bucket in this
repository.

**Firebase Cloud Messaging.** Create a Firebase project, generate a service-account key and provide
it via `FIREBASE_SERVICE_ACCOUNT_JSON` (file path, raw JSON or base64). `/health` then reports
`push.status = "ok"`. Messages are data-only (`{type, eventId, coupleId, seq}`), high priority, and
tokens FCM reports as unregistered are deleted automatically. Not exercised against a live project.

**Email.** Password-reset delivery requires an email provider (open item O-01). The `Mailer`
interface is pluggable; today only `ConsoleMailer` exists (logs a non-sensitive line; prints the
reset link to stdout in development).

## Deployment recommendation

Run as **one long-running container** (Fly.io, Railway, Render, or a small VPS) with MongoDB Atlas
(free tier suffices) and Cloudflare R2. WebSockets and the outbox worker need a persistent process,
so serverless functions are not suitable. The design assumes a single instance: the WebSocket hub and
the invitation attempt limiter are in-process (D-04 known limit). Set `NODE_ENV=production`,
`STORAGE_DRIVER=r2`, restrict `CORS_ORIGINS`, terminate TLS at the platform, and point
`PUBLIC_BASE_URL` at the public URL. `GET /api/v1/health/ready` is the readiness probe.

## Known limitations

- No email provider, no Firebase project, no R2 bucket were available: the console mailer, the
  disabled push service and the local storage driver are the honest fallbacks; the R2 and FCM
  drivers are implemented but unverified against live services.
- Single backend instance (in-process WebSocket hub and attempt limiter).
- Domain write + event insert are not wrapped in a transaction (standalone Mongo supported); see
  `docs/DATA_MODEL.md` "Write ordering".
- Media uploads (including avatars) require an active couple because object keys are
  couple-scoped (`couples/{coupleId}/…`).
- `GET /me/export` is bounded to 1000 items per collection and reports `truncated`.
- Password breached-list check is not included (D-03).
