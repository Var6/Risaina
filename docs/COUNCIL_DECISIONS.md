# Risaina — Council Decision Record

This document records the consequential architecture decisions for Risaina, the
alternatives weighed, the reasons for rejecting them, known risks, and open items.
It is a simulated multidisciplinary review, not a record of external consultants.

Status key: **Decided** · **Decided, revisit** · **Open**

---

## D-01 Toolchain versions — Decided

**Context.** Fresh macOS (Apple Silicon) host with no JDK, Android SDK, or Gradle installed.
Node 26.5, pnpm 11, a local MongoDB 8.3 server, and adb were present. Versions were
looked up on 2026-10-25 from Google Maven, Maven Central and the official AGP
compatibility page, not from memory.

**Decision.**

| Component | Version | Why |
|---|---|---|
| JDK | OpenJDK 17 (Homebrew) | Minimum and default for AGP 9.4; smallest surface |
| Gradle | 9.6.0 | AGP 9.4 minimum/default per official table |
| Android Gradle Plugin | 9.4.1 | Current stable; Hilt ≥ 2.57 now *requires* AGP 9 |
| Kotlin | 2.4.21 | Current stable; pinned above AGP's bundled KGP 2.2.10 |
| KSP | 2.3.12 | KSP2 line, Kotlin-version independent |
| compileSdk / targetSdk | 36 | Current stable platform |
| minSdk | 26 | Font variation settings, notification channels, adaptive icons all native; covers ~97 % of active devices |
| Compose BOM | 2026.09.00 (Compose 1.12.1, Material3 1.4.0) | Current stable |
| Glance | 1.2.0 | Current stable widget framework |

**Alternatives rejected.**
- *AGP 8.13.2 + Gradle 8.14.* Familiar model, but Dagger/Hilt's current releases require AGP 9 and
  would have forced an immediate migration.
- *JDK 21.* Supported, but adds nothing for this project and widens the matrix.

**Risk.** AGP 9's built-in Kotlin is new; plugins that assume `kotlin-android` may misbehave.
*Mitigation:* pin KGP/KSP explicitly, keep plugin set minimal, verify with a real build.

---

## D-02 Android project shape — Decided, revisit

**Decision.** One Gradle module (`:app`) with strict package boundaries that mirror the
intended modules (`core.designsystem`, `core.model`, `core.network`, `core.database`,
`core.datastore`, `core.notifications`, `core.common`, `feature.*`). Extract to Gradle
modules when (a) build time of `:app` exceeds ~90 s incremental, or (b) a boundary is
violated twice in review.

**Alternatives.** Full multi-module from day one (rejected: slows iteration on an empty
codebase, and AGP 9's `uniquePackageNames` adds friction for no current benefit).

**Architecture.** MVVM with unidirectional data flow: `Screen(state, onEvent)` ← `ViewModel`
(`StateFlow<UiState>`) ← `Repository` (Room + Ktor + DataStore) ← data sources. UI state is
immutable data classes; one-shot effects use a `Channel`.

---

## D-03 Authentication — Decided

**Options.**
1. Firebase Authentication (email + Google).
2. Self-hosted email/password (argon2id) + short-lived JWT access tokens + rotating opaque refresh tokens.
3. Passwordless magic links.

**Decision: option 2.**
- Keeps identity under the backend's control; backend tests run fully offline.
- Does not make the API depend on Firebase for identity (FCM remains a pure transport).
- Magic links and Google Sign-In both require external mail/OAuth setup that is not available.

**Details.** Access token: HS256 JWT, 15 min, `sub=userId`, `sid=sessionId`. Refresh token:
256-bit random, stored as SHA-256 hash, 30-day sliding expiry, rotated on every use, reuse
detection revokes the whole session family. Passwords: argon2id, min 10 chars, breached-list
check not included (documented limitation). Password recovery: reset tokens are generated and
hashed server-side; **delivery requires an email provider** which is not configured — a console
mailer is used in development and the limitation is documented.

**Rejected.** Firebase Auth: mixes identity with push transport, requires the Firebase
emulator for every backend test, and leaks a dependency on `google-services.json` into the
authentication path.

---

## D-04 Realtime, durability and missed-event recovery — Decided

**Options.**
1. Fire-and-forget WebSocket broadcasts.
2. Per-couple durable event log with monotonic sequence numbers + WebSocket replay.
3. MongoDB change streams / Redis pub-sub with an external queue.

**Decision: option 2.**
Every state change (ping, message, read receipt, memory, couple change) is first written as a
domain document **and** an `events` document `{coupleId, seq, type, payload, createdAt}` where
`seq` is allocated by atomically incrementing `couples.eventSeq`. Connected sockets receive the
event; clients persist `lastSeq`; on connect they send `sync{since}` and the server replays from
the `events` collection. Duplicate delivery is harmless because clients de-duplicate on `seq`.

The same `events` collection is the **push outbox**: each event carries `push.state`
(`pending|sent|skipped|failed`) and `push.attempts`. An in-process worker leases pending events
(`findOneAndUpdate` with a lease expiry) and sends FCM with bounded retries. A DB write therefore
cannot be silently lost before notification processing, without introducing a queue.

**Rejected.** Option 1 loses events on disconnect. Option 3 is correct but is distributed-systems
overhead for a two-user app. **Known limit:** the design assumes one backend instance; with more
than one, WS fan-out needs a shared bus (documented, not built).

---

## D-05 Touch Ping lifecycle — Decided

`idle → sending → accepted → delivered → seen`, plus `failed`, `expired`.

- `sending`: local only, with haptic + animation.
- `accepted`: server persisted the ping (HTTP 201). Idempotency key prevents duplicates.
- `delivered`: set **only** when the *recipient's authenticated device* calls
  `POST /pings/:id/delivered` after it has received the event (via WS or FCM data message).
  The server timestamps it. FCM acceptance is never treated as delivery.
- `seen`: recipient opened the ping (`POST /pings/:id/seen`).
- A sender can never set delivered/seen on their own ping (enforced server-side, tested).

Rate limits: default 1 ping / 15 s and 40 / hour per sender (server-enforced, user-adjustable
downwards). Quiet hours: recipient preference; server skips push when inside quiet hours unless
`allowPushInQuietHours` is set; the app additionally silences locally.

---

## D-06 Media storage — Decided

**Decision.** Cloudflare R2, private bucket, server-generated object keys
(`couples/{coupleId}/{category}/{mediaId}.{ext}`), S3 presigned PUT (10 min) and GET (15 min).
Flow: `POST /media/uploads` (validates membership, category, MIME, size) → client PUTs to R2 →
`POST /media/:id/complete` → server `HEAD`s the object, checks size and content-type, reads the
first bytes to sniff magic numbers → marks `ready` → emits `media.ready` event. Abandoned
`pending` uploads older than 24 h are deleted by a periodic job. Deleting a message/memory/couple
deletes the R2 objects.

**Local development.** A `STORAGE_DRIVER=local` adapter implements the same interface
(signed, expiring URLs served by the backend) so the full upload→download journey is testable
with no R2 credentials. It is clearly a development driver, not a production path.

**Rejected.** Proxying all uploads through the API (simpler auth, but doubles bandwidth and
blocks the event loop on large files); public-read bucket with obscure keys (not private).

---

## D-07 Push notifications — Decided

FCM via `firebase-admin` on the backend with **data-only** messages containing
`{type, eventId, coupleId, seq}` and no intimate content. The Android app builds the
notification locally, honouring the user's preview setting, quiet hours, and channel
configuration, then fetches details from the API. If Firebase credentials are absent the
backend starts with push **disabled** and says so in `/health`; the Android build applies the
Google Services plugin only if `google-services.json` exists, so the project builds without it.

---

## D-08 Backend stack and hosting — Decided (hosting: recommended, not performed)

Node 26 (LTS line), TypeScript, Fastify 5, Zod 4 via `fastify-type-provider-zod`, official
MongoDB driver 7, AWS SDK v3 S3 client (R2), `jose`, `argon2`, `pino`, Vitest 5,
`@fastify/swagger` + `swagger-ui`, `@fastify/websocket`, `@fastify/rate-limit`,
`@fastify/helmet`, `@fastify/cors`, `@fastify/under-pressure`.

**Hosting recommendation.** A single long-running container (Fly.io, Railway, Render, or a small
VPS) because WebSockets and the outbox worker need a persistent process. Serverless functions are
rejected for the API process. MongoDB Atlas (free tier suffices), Cloudflare R2. Nothing is
deployed by this work.

---

## D-09 Local persistence and offline — Decided

Room for messages, pings, memories, milestones, media metadata, the event cursor, and a
client-side outbox of unsent operations (each with its idempotency key). DataStore for
preferences and session metadata. Access/refresh tokens are stored in
`EncryptedSharedPreferences`-free design: tokens live in DataStore inside app-private storage,
protected by Android's per-app sandbox; keystore-backed encryption is a Release C refinement
(recorded as open item O-03).

Offline rules: cached content is viewable; new pings require connectivity and are never shown as
accepted while offline; queued text messages are shown as *pending* until the server accepts.

---

## D-10 Encryption claims — Decided

No end-to-end encryption claim is made. Transport is TLS; at-rest encryption is provided by
MongoDB Atlas and R2. A separate design review is required before any E2EE claim (key storage,
device change, backup, recovery). Recorded in SECURITY.md.

---

## D-11 Typography and licensing — Decided

Fraunces (variable serif, SIL OFL 1.1) for emotional headlines, Inter (variable sans, SIL OFL
1.1) for body and controls. Bundled as resources with their OFL licences; no runtime font
downloads. Both files are < 1 MB each.

---

## Open items

| ID | Item | Owner | Plan |
|---|---|---|---|
| O-01 | Email provider for password reset / invitation emails | User | Configure SMTP or Resend; interface is pluggable |
| O-02 | Firebase project + `google-services.json` + service account | User | See docs/FIREBASE_SETUP.md |
| O-03 | Keystore-backed token encryption on Android | Team | Release C |
| O-04 | Multi-instance WebSocket fan-out | Team | Only if hosting requires >1 instance |
| O-05 | Real two-device testing | User + team | Needs two devices or emulators with Play services |
| O-06 | Store-ready privacy policy review | User | Draft provided in docs/PRIVACY_POLICY_DRAFT.md |
