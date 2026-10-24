# Risaina — Architecture

## System overview

```
┌──────────────────────────────┐        HTTPS /api/v1  +  WSS /api/v1/ws        ┌──────────────────────────┐
│  Android app (Kotlin/Compose)│ ─────────────────────────────────────────────▶ │ Fastify API (Node/TS)    │
│  Room · DataStore · Glance   │ ◀──── FCM data messages (via Google) ────────  │ auth · couples · events  │
│  WorkManager · Media3        │                                                 │ outbox worker · WS hub   │
└──────────────────────────────┘                                                 └────┬──────────┬──────────┘
                                                                                       │          │
                                                                                 MongoDB     Cloudflare R2
                                                                            (documents,    (private objects,
                                                                             events log)    presigned URLs)
```

The backend is the only authorization boundary. The app never holds R2, MongoDB or Firebase
server credentials. Identity comes from the verified access token; couple membership is checked
on every operation.

## Android application (`android/`)

Single Gradle module `app`, package `com.risaina.app`.

```
com.risaina.app
├── RisainaApp.kt                 Hilt application, notification channels, WorkManager config
├── MainActivity.kt               Single activity, Compose host, deep-link handling
├── core
│   ├── designsystem              Tokens (colour, type, spacing, motion), theme, components
│   ├── model                     Pure Kotlin domain models (no Android deps)
│   ├── common                    Result/AppError, dispatchers, time, haptics abstraction
│   ├── network                   Ktor client, DTOs, auth plugin, WebSocket client
│   ├── database                  Room database, entities, DAOs
│   ├── datastore                 Preferences + session store
│   ├── notifications             Channels, builders, FCM service, quiet-hours policy
│   └── sync                      Event cursor, replay, outbox drain (WorkManager)
├── feature
│   ├── onboarding  authentication  pairing  home  ping  messages
│   ├── memories  voice  widgets  timeline  wallpaper  settings
└── navigation                    Type-safe Navigation Compose graph
```

### Unidirectional data flow

`Screen(uiState, onEvent)` → `ViewModel` exposes `StateFlow<UiState>` and a `Channel<Effect>` →
`Repository` merges Room (source of truth for display) with network results → network/db data
sources. Repositories expose `Flow`s; the UI never calls the network directly.

### Realtime and sync

`EventSyncManager` owns one WebSocket while the app is in the foreground (lifecycle-aware). On
connect it sends `sync{since: lastSeq}`; every received event is applied idempotently by `seq`
and the cursor advanced. In the background, FCM data messages trigger a bounded `SyncWorker`
that calls `GET /events?since=` and applies the same reducer. No persistent background socket.

### Touch Ping

`PingRepository.send()` creates a local `pending` row with a UUID idempotency key, posts it,
and updates the row to `accepted` or `failed`. `delivered`/`seen` arrive as events. The
recipient's app acknowledges delivery when it applies a `ping.created` event.

### Widgets (Glance)

Widgets read a small `WidgetSnapshot` kept in DataStore (updated by the sync layer), never the
network, so they render offline and respect privacy settings at write time. Updates are
requested after relevant events; Android may coalesce them.

## Backend (`backend/`)

```
src
├── app.ts            buildApp(): plugins, routes, error handler, OpenAPI
├── server.ts         process entry, env validation, graceful shutdown
├── config/env.ts     Zod-validated environment
├── db/               Mongo client, collections, indexes, schema validators
├── plugins/          auth (JWT), couple-membership guard, rate limits, websocket hub
├── modules/          auth · users · couples · invitations · devices · pings · messages
│                     media · memories · milestones · events · health
├── services/         storage (r2 | local), push (fcm | disabled), mailer (console)
├── workers/          outbox push worker, abandoned-upload cleanup
└── lib/              ids, crypto, errors, pagination, time
```

Each module: `schemas.ts` (Zod), `routes.ts`, `service.ts`, `repo.ts`. Routes never touch
collections directly.

### Durable events

`events {_id, coupleId, seq, type, actorId, payload, createdAt, push:{state,attempts,leaseUntil,lastError}}`
with unique index `(coupleId, seq)`. `seq` comes from `couples.eventSeq` via `$inc`. The WS hub
publishes after commit; the outbox worker handles push.

## Data model (MongoDB)

See `backend/src/db/collections.ts` and `docs/DATA_MODEL.md` for fields and indexes.

## Security model summary

- Access token → `request.user`; every couple-scoped route resolves the couple **from the user**,
  never from the request.
- Media object keys are server-generated; URLs are short-lived; download requires membership.
- Device tokens are write-only from the client's perspective.
- Rate limits per route group; invitation codes are 10-character Crockford base32 with hashed
  storage, 24 h expiry, single use, 5 attempts/15 min per IP+user.
