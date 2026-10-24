# Risaina — Implementation Plan

Phases map to the master prompt. A phase is complete only when it builds and its tests pass.

## Phase 0 — Inspect and plan ✅
Toolchain installed (JDK 17, SDK 36, Gradle 9.6 via wrapper). Versions verified against
official sources. Decision record, architecture, plan, progress, API contract written.

## Phase 1 — Design system and Android foundation
1. Gradle project: version catalog, AGP 9.4.1, Kotlin 2.4.21, Hilt, KSP, Room, Compose BOM.
2. Design system: colour tokens (light + dark), Fraunces/Inter type scale, spacing, shapes,
   motion tokens, core components (buttons, cards, text fields, chips, avatars, status pills).
3. Adaptive launcher icon (vector foreground + background), splash.
4. Navigation graph with type-safe routes; onboarding → auth → pairing → home.
5. Screens with real state machines but local data sources: Welcome, Sign in/up, Pairing,
   Home, Ping, Messages, Memories, Timeline, Settings, Widget gallery.
6. Unit tests: quiet hours, countdown, ping state machine, message validation.
7. `./gradlew assembleDebug testDebugUnitTest lint` green.

## Phase 2 — Backend foundation
1. Fastify app, env validation, Mongo connection with indexes, health endpoints.
2. Auth module (register, login, refresh rotation, logout, password reset tokens).
3. Couple guard plugin, users/me, preferences, account deletion skeleton.
4. OpenAPI generation, error envelope, rate limiting, security headers.
5. Vitest: auth, authorization, cross-couple access denial.

## Phase 3 — Pairing and notifications
1. Invitations (create, revoke, accept once, expiry) + couple activation.
2. Device token registration/revocation; FCM service (disabled when unconfigured).
3. Pings: create (idempotent, rate-limited), delivered/seen (recipient-only), history.
4. Events log, WS hub with auth + sync, outbox worker with retries.
5. Android: real network layer, session store, WS sync manager, FCM service, notifications,
   quiet hours, pairing flow, Touch Ping end-to-end.
6. Tests: invitation reuse/expiry, third-user join denial, impersonation denial, duplicate
   idempotency keys, outbox retry, WS auth rejection.

## Phase 4 — Media and messaging
1. Media module (presign, complete with verification, signed download, delete, cleanup job).
2. Messages (text/emoji/photo/voice, pagination, read receipts, deletion rules).
3. Memories + milestones.
4. Android: conversation UI, upload with progress + retry, voice recorder (MediaRecorder),
   Media3 playback, memories grid with lazy loading, image compression.
5. Tests: media authorization, URL expiry, cross-couple media denial, message validation.

## Phase 5 — Widgets and wallpaper
Photo, love-note, Touch Ping, emoji, countdown, voice-note widgets (Glance) with snapshot
store; Wallpaper Studio with preview and explicit apply via `WallpaperManager`.

## Phase 6 — Hardening
Privacy controls, lint, security tests, UI tests, release build instructions, docs, device
testing checklist.
