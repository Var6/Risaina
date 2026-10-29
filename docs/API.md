# Risaina API v1 — Contract

Base path: `/api/v1`. JSON everywhere. All times are ISO-8601 UTC strings. IDs are 24-hex
Mongo ObjectIds rendered as strings. The generated OpenAPI document is served at
`/docs` (UI) and `/docs/json` by the running backend and is the source of truth once the
backend exists; this file is the design contract.

## Conventions

- **Auth:** `Authorization: Bearer <accessToken>` on every route except `auth/*`, `health*`.
- **Errors:** `{ "error": { "code": "string", "message": "string", "details"?: any } }` with
  HTTP status. Codes used: `validation_failed`, `unauthorized`, `forbidden`, `not_found`,
  `conflict`, `rate_limited`, `invitation_expired`, `invitation_used`, `already_paired`,
  `not_paired`, `media_not_ready`, `payload_too_large`, `unsupported_media`, `internal`.
- **Pagination:** cursor based. Request `?limit=30&before=<cursor>`; response
  `{ items: [...], nextCursor: string | null }`. Cursors are opaque strings (createdAt+id).
- **Idempotency:** `POST /pings` and `POST /messages` require `idempotencyKey` (UUID v4).
  Replaying the same key for the same user returns the original resource with `200`.
- **Rate limits:** sensible defaults per route group; `429` with `Retry-After`.

## Shapes

```ts
User       { id, displayName, avatarMediaId: string|null, createdAt }
Partner    { id, displayName, avatarMediaId: string|null }
Couple     { id, status: 'pending'|'active'|'ended', memberIds: string[], relationshipStartDate: string|null,
             nextMeetingDate: string|null, nextMeetingLabel: string|null, sharedPreferences: {...}, createdAt }
Tokens     { accessToken, accessExpiresAt, refreshToken, refreshExpiresAt }
Preferences{ notificationsEnabled, previewInNotifications, quietHours: { enabled, start: 'HH:mm', end: 'HH:mm', timeZone },
             allowPushInQuietHours, pingCooldownSeconds, widgetPrivacy: { showPhoto, showNoteText, showEmoji }, reducedMotion }
Ping       { id, coupleId, senderId, recipientId, type, emoji, message: string|null, status: 'accepted'|'delivered'|'seen',
             createdAt, deliveredAt: string|null, seenAt: string|null, idempotencyKey }
Message    { id, coupleId, senderId, recipientId, type: 'text'|'emoji'|'photo'|'voice', text: string|null,
             mediaId: string|null, createdAt, editedAt: string|null, deletedAt: string|null, readAt: string|null, idempotencyKey }
Media      { id, coupleId, ownerId, category: 'photo'|'voice'|'avatar'|'wallpaper', mimeType, sizeBytes,
             durationMs: number|null, width: number|null, height: number|null, status: 'pending'|'ready'|'deleted', createdAt }
Memory     { id, coupleId, createdBy, mediaId, caption: string|null, takenAt: string|null, pinned: boolean, createdAt, deletedAt }
Milestone  { id, coupleId, createdBy, title, date, kind: 'anniversary'|'meeting'|'custom', visibility: 'shared', createdAt }
Event      { id, coupleId, seq: number, type: string, actorId, payload: object, createdAt }
```

## Auth

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/auth/register` | `{ email, password, displayName }` | `201 { user, tokens }` |
| POST | `/auth/login` | `{ email, password }` | `200 { user, tokens }` |
| POST | `/auth/refresh` | `{ refreshToken }` | `200 { tokens }` (rotation; reuse ⇒ session family revoked) |
| POST | `/auth/logout` | `{ refreshToken, deviceId? }` | `204` (revokes session and that device's push token) |
| POST | `/auth/password/forgot` | `{ email }` | `202` always |
| POST | `/auth/password/reset` | `{ token, newPassword }` | `204` |

## Me

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/me` | | `{ user, couple: Couple|null, partner: Partner|null, preferences }` |
| PATCH | `/me` | `{ displayName?, avatarMediaId? }` | `{ user }` |
| GET | `/me/preferences` | | `{ preferences }` |
| PUT | `/me/preferences` | `Preferences` (partial allowed) | `{ preferences }` |
| DELETE | `/me` | `{ password }` | `204` — cascades: sessions, devices, couple ended **and purged** (see "Unlink purge"), own media deleted |
| GET | `/me/export` | | `{ user, couple, pings, messages, memories, milestones }` (bounded) |

## Pairing

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/invitations` | `{}` | `201 { invitation: { id, code, expiresAt } }` — code shown once; creates a `pending` couple if none |
| GET | `/invitations/current` | | `{ invitation: { id, expiresAt, createdAt } | null }` (no code) |
| DELETE | `/invitations/:id` | | `204` revoke |
| POST | `/invitations/accept` | `{ code }` | `200 { couple, partner }` — 410 `invitation_expired`, 409 `invitation_used`/`already_paired` |
| GET | `/couple` | | `{ couple, partner }` |
| PATCH | `/couple` | `{ relationshipStartDate?, nextMeetingDate?, nextMeetingLabel?, sharedPreferences? }` | `{ couple }` |
| POST | `/couple/unlink` | `{ confirm: true }` | `204` — couple `ended`, both notified, **all couple data and media deleted** (see "Unlink purge") |

## Devices

| Method | Path | Body | Response |
|---|---|---|---|
| PUT | `/devices` | `{ deviceId, platform: 'android', token, appVersion, model?, sdkInt? }` | `204` upsert; token never returned (`sdkInt`: see "Devices and outbox hardening") |
| DELETE | `/devices/:deviceId` | | `204` |

## Pings

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/pings` | `{ type, emoji, message?, idempotencyKey }` | `201 { ping }` / `200` on replay / `429` on cooldown |
| GET | `/pings` | `?limit&before` | `{ items: Ping[], nextCursor }` |
| POST | `/pings/:id/delivered` | | `{ ping }` recipient only; idempotent |
| POST | `/pings/:id/seen` | | `{ ping }` recipient only; idempotent |

`type` ∈ `thinking_of_you | miss_you | hug | kiss | goodnight | good_morning | custom`.
`message` ≤ 140 chars. `emoji` is 1–2 grapheme clusters.

## Messages

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/messages` | `{ type, text?, mediaId?, idempotencyKey }` | `201 { message }` |
| GET | `/messages` | `?limit&before` | `{ items, nextCursor }` |
| POST | `/messages/:id/read` | | `{ message }` recipient only |
| DELETE | `/messages/:id` | | `{ message }` sender only; soft delete; media object deleted |

Rules: `text` required for `text|emoji` (≤ 2000 chars); `mediaId` required for `photo|voice`
and must be `ready`, owned by the sender, category matching.

## Media

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/media/uploads` | `{ category, mimeType, sizeBytes, durationMs?, width?, height? }` | `201 { media, upload: { url, method: 'PUT', headers, expiresAt } }` |
| POST | `/media/:id/complete` | | `{ media }` — server verifies object (HEAD + magic bytes) |
| GET | `/media/:id/url` | | `{ url, expiresAt }` short-lived, membership-checked |
| DELETE | `/media/:id` | | `204` owner only |

Limits (defaults, env-configurable): photo ≤ 10 MB (`image/jpeg|png|webp|heic`), voice ≤ 8 MB
and ≤ 180 s (`audio/mp4|audio/aac|audio/ogg`), avatar ≤ 3 MB, wallpaper ≤ 12 MB.

## Memories & milestones

| Method | Path | Body |
|---|---|---|
| POST | `/memories` | `{ mediaId, caption?, takenAt? }` |
| GET | `/memories` | `?limit&before&pinned` |
| PATCH | `/memories/:id` | `{ caption?, pinned? }` |
| DELETE | `/memories/:id` | |
| POST | `/milestones` | `{ title, date, kind }` |
| GET | `/milestones` | |
| PATCH | `/milestones/:id` | `{ title?, date?, kind? }` |
| DELETE | `/milestones/:id` | |

## Events & realtime

| Method | Path | | |
|---|---|---|---|
| GET | `/events` | `?since=<seq>&limit=200` | `{ items: Event[], latestSeq }` (after an unlink: see "Unlink purge") |
| WS | `/ws` | see below | |

WebSocket protocol:
1. Client connects (no token in URL). Within 5 s sends `{ "type": "auth", "token": "<access>" }`.
2. Server replies `{ "type": "ready", "coupleId", "latestSeq" }` or closes with 4401.
3. Client sends `{ "type": "sync", "since": <seq> }`; server streams `{ "type": "event", "event": Event }` for each missed event then `{ "type": "synced" }`.
4. Live events arrive as `{ "type": "event", "event" }`. Client acks nothing; it persists `seq`.
5. Heartbeat: server pings every 30 s; client replies pong (Ktor handles). Idle 90 s ⇒ close.
6. Server may send `{ "type": "revoked" }` and close 4403 when the couple ends or session is revoked.

Event types: `ping.created`, `ping.delivered`, `ping.seen`, `message.created`, `message.read`,
`message.deleted`, `media.ready`, `memory.created`, `memory.updated`, `memory.deleted`,
`milestone.created`, `milestone.updated`, `milestone.deleted`, `couple.updated`,
`couple.unlinked`, `partner.updated`.

Push (FCM data message) payload mirrors a minimal event: `{ type, eventId, coupleId, seq }`, plus `pr` and
`ref` (see "Push delivery and delivery health" for both and for the priority of each type).

## Health

`GET /health` → `{ status: 'ok', version, uptimeSeconds, checks: { mongo, storage, push } }`.
`GET /health/ready` → 200/503.

## Event payload shapes

| Event type | `payload` |
|---|---|
| `ping.created` | `{ ping: Ping }` |
| `ping.delivered` | `{ pingId, deliveredAt }` |
| `ping.seen` | `{ pingId, seenAt }` |
| `message.created` | `{ message: Message }` |
| `message.read` | `{ messageId, readAt }` |
| `message.deleted` | `{ messageId, deletedAt }` |
| `media.ready` | `{ media: Media }` |
| `memory.created` / `memory.updated` | `{ memory: Memory }` |
| `memory.deleted` | `{ memoryId, deletedAt }` |
| `milestone.created` / `milestone.updated` | `{ milestone: Milestone }` |
| `milestone.deleted` | `{ milestoneId }` |
| `couple.updated` | `{ couple: Couple }` |
| `couple.unlinked` | `{ coupleId, endedAt, by }` |
| `partner.updated` | `{ partner: Partner }` |

`actorId` is the user who caused the event. Events are delivered to **both** members; clients
ignore effects that only concern the actor (for example the sender does not notify itself).

## Auth additions

Added after v1 (Google sign-in, email verification, real mailer). Additive: no existing field or
route changed shape, except that `DELETE /me` now also accepts `{ confirm: "DELETE" }`.

### `User` shape (all user responses)

```ts
User { id, displayName, avatarMediaId: string|null, createdAt,
       emailVerified: boolean,   // confirmed by 6-digit code, or the account came from Google
       hasPassword: boolean }    // false for Google-only accounts → DELETE /me uses { confirm: "DELETE" }
```

Returned by `register`, `login`, `google`, `email/verify`, `GET /me`, `PATCH /me` and `/me/export`.
`emailVerified` is informational: nothing in the app is blocked on it.

### Routes

| Method | Path | Auth | Body | Success |
|---|---|---|---|---|
| POST | `/auth/google` | none | `{ idToken: string }` (1–4096 chars) | `200 { user: User, tokens: Tokens, isNewUser: boolean }` — always 200, also when the account was created |
| POST | `/auth/email/verify` | Bearer | `{ code: string }` (exactly 6 digits; surrounding spaces trimmed) | `200 { user: User }` — idempotent once verified |
| POST | `/auth/email/resend` | Bearer | `{}` (ignored) | `202 { accepted: true }` |
| DELETE | `/me` | Bearer | `{ password }` **or** `{ confirm: "DELETE" }` | `204` |

### `POST /auth/google`

Verifies the Google ID token (RS256 against Google's JWKS; `iss` is `accounts.google.com` or
`https://accounts.google.com`; `aud` must be one of `GOOGLE_CLIENT_IDS`, the **Web** client ID the
Android app uses as `serverClientId`; `email_verified` must be `true`; `sub` required). Rate limited
like `/auth/login`.

Account resolution:
1. account whose Google `sub` matches → sign in (`isNewUser: false`);
2. else account with the same email (case-insensitive) → Google is linked, email marked verified
   (`isNewUser: false`). **If that account's email was not verified**, its password is removed and
   all its sessions are revoked first (account pre-hijacking defence); the user can set a password
   again with forgot/reset. A verified account keeps its password and sessions;
3. else a new account: `displayName` from `given_name`, then `name`, then the email local part
   (max 60 chars), no password, email verified (`isNewUser: true`).

| Status | `error.code` | When |
|---|---|---|
| 400 | `validation_failed` | missing / empty / > 4096-char `idToken` |
| 401 | `unauthorized` | bad signature, wrong audience or issuer, expired, malformed (`Invalid Google ID token`); `email_verified` not true (`Your Google account email is not verified`) |
| 409 | `conflict` | the email already belongs to an account linked to a **different** Google `sub`; or a concurrent sign-in race that persisted after retries |
| 429 | `rate_limited` | per-IP auth rate limit (`Retry-After`) |
| 503 | `google_not_configured` | `GOOGLE_CLIENT_IDS` unset on the server |

### Email verification

On `POST /auth/register` the server emails a 6-digit code valid for 30 minutes (registration
succeeds even if the email cannot be sent). Only the newest code is valid.

`POST /auth/email/verify`:

| Status | `error.code` | When |
|---|---|---|
| 200 | — | code correct (or the email was already verified) → `{ user }` with `emailVerified: true` |
| 400 | `validation_failed` | not exactly 6 digits (does not count as an attempt) |
| 400 | `verification_code_invalid` | wrong code; `error.details = { attemptsRemaining: number }` (4, 3, 2, 1) |
| 410 | `verification_code_expired` | the newest code expired, or there is no code — request a new one |
| 429 | `verification_attempts_exceeded` | the 5th wrong code, and every later try on that code (even the right one); `Retry-After` = seconds until a resend is allowed |
| 401 | `unauthorized` | missing/invalid bearer token |

`POST /auth/email/resend` (always sends a **new** code; the previous one stops working):

| Status | `error.code` | When |
|---|---|---|
| 202 | — | `{ accepted: true }` (also when delivery later fails; failures are only logged) |
| 409 | `conflict` | email already verified |
| 429 | `rate_limited` | less than 60 s since the last code, or 5 codes in the last hour (the registration email counts); `Retry-After` in seconds |
| 401 | `unauthorized` | missing/invalid bearer token |

### `DELETE /me`

- `user.hasPassword === true` → `{ "password": "…" }` (unchanged). `{ confirm }` alone ⇒ `401`.
- `user.hasPassword === false` (Google-only) → `{ "confirm": "DELETE" }` (exact, uppercase).
  A `password` ⇒ `401`.
- Neither field, or `confirm` with any other value ⇒ `400 validation_failed`.

Deleting also frees the Google identity: signing in with the same Google account later creates a
new, empty account.

### Other behaviour

- `POST /auth/login` on an account without a password returns the same `401 unauthorized`
  "Invalid email or password" as a wrong password.
- `POST /auth/password/forgot` / `reset` work for Google-only accounts and add a password.
- `GET /health` → `checks.google = { status: 'ok' | 'disabled' }`.
- New error codes: `google_not_configured`, `verification_code_invalid`,
  `verification_code_expired`, `verification_attempts_exceeded`.

## Profile & shared lists additions

Additive: existing fields keep their shape; `User`, `Partner` and `/me/export` gain fields.

### Profile fields

```ts
User    { ...as above, birthday: string|null, timeZone: string|null, shareLocalTime: boolean }
Partner { id, displayName, avatarMediaId: string|null,
          birthday: string|null,   // 'YYYY-MM-DD' or '--MM-DD' (year private)
          timeZone: string|null }  // null when unset OR that partner has shareLocalTime = false
```

`Partner` has this shape everywhere it appears: `GET /me`, `GET /couple`, `POST /invitations/accept`
and the `partner.updated` event payload. Accounts that never set the fields read
`birthday: null, timeZone: null, shareLocalTime: true`.

`PATCH /me` body (all optional, `.strict()`; unknown keys ⇒ 400):

```ts
{ displayName?: string, avatarMediaId?: string|null,
  birthday?: string|null,       // 'YYYY-MM-DD' (real date, year ≥ 1900, not in the future) or '--MM-DD' (Feb 29 allowed); null clears
  timeZone?: string|null,       // IANA id (e.g. 'Asia/Kolkata'); fixed offsets like '+05:30' rejected; null clears
  shareLocalTime?: boolean }
```

→ `200 { user: User }`. Errors: `400 validation_failed` (e.g. `2026-02-30`, `--13-01`, `Mars/Olympus`),
`401`, `404 not_found` / `409 media_not_ready` (avatar, unchanged). `timeZone` is stored as sent,
except that letter case is fixed (`europe/berlin` → `Europe/Berlin`).

`partner.updated` `{ partner: Partner }` is emitted (to the couple, when paired) whenever
`displayName`, `avatarMediaId`, `birthday`, `timeZone` or `shareLocalTime` actually changes value;
a PATCH that changes nothing emits nothing.

### Shared lists

Both partners read and edit everything in their couple. All routes: Bearer + active couple
(`409 not_paired` otherwise). An id from another couple (or a deleted list) ⇒ `404 not_found`; a
malformed id ⇒ `400 validation_failed`.

```ts
List     { id, coupleId, createdBy, updatedBy, title, kind: 'note'|'checklist', body: string,
           items: ListItem[], pinned: boolean, version: number /* ≥ 1 */, createdAt, updatedAt }
ListItem { id: string, text: string, done: boolean, doneBy: string|null, doneAt: string|null }
```

| Method | Path | Body | Success |
|---|---|---|---|
| GET | `/lists` | | `200 { items: List[] }` — pinned first, then `updatedAt` desc; max 200; deleted excluded |
| POST | `/lists` | `{ title, kind, body?, items?: [{ text, done? }], pinned? }` | `201 { list }` (`version: 1`) |
| PATCH | `/lists/:id` | `{ title?, body?, items?: [{ id?, text, done }], pinned?, expectedVersion? }` | `200 { list }` (`version` + 1) |
| DELETE | `/lists/:id` | | `204` (soft delete) |

Limits: `title` trimmed, 1–120; `body` ≤ 10000 (may be `''`, default `''`); `items` ≤ 200; item
`text` trimmed, 1–300; item `id` (PATCH) `[A-Za-z0-9_-]{1,32}`, unique within the request; `kind`
cannot be changed; ≤ 200 lists per couple (`409 conflict` on create beyond that).
Top-level bodies are strict (unknown keys ⇒ 400). Inside an item, extra keys such as `doneBy` /
`doneAt` (and `id` on POST) are accepted and ignored, so clients may echo items back as received.

Item rules (server owns `id` when omitted, `doneBy`, `doneAt`):
- `items` in PATCH **replaces the whole array** (order as sent; omitted items are removed).
- New item (no `id`, or an `id` not in the list): server assigns a 12-char id if missing; if
  `done: true` ⇒ `doneBy` = caller, `doneAt` = now, else both `null`. Same on POST.
- Existing `id`, `done` unchanged ⇒ `doneBy`/`doneAt` preserved (text may change).
- Flips to `done: true` ⇒ `doneBy` = caller, `doneAt` = now. Flips to `false` ⇒ both `null`.

Concurrency: with `expectedVersion` ≠ current version ⇒
`409 { error: { code: 'version_conflict', message, details: { list: List /* current */ } } }`.
Without `expectedVersion` the PATCH applies to the latest state (last write wins).

Rate limit: POST/PATCH/DELETE share **120 per user per minute** (`RATE_LIMIT_LIST_WRITES_PER_MIN`)
⇒ `429 rate_limited` with `Retry-After`. GET is only subject to the global per-IP limit.

| Status | `error.code` | When |
|---|---|---|
| 400 | `validation_failed` | body/limits above, malformed id |
| 401 | `unauthorized` | missing/invalid token |
| 404 | `not_found` | list not in the caller's couple, or deleted |
| 409 | `not_paired` | no active couple |
| 409 | `version_conflict` | PATCH `expectedVersion` mismatch (`details.list` = current) |
| 409 | `conflict` | 200-lists-per-couple cap (POST) |
| 429 | `rate_limited` | list write budget exceeded |

Events (both members, like all events):

| Event type | `payload` |
|---|---|
| `list.created` / `list.updated` | `{ list: List }` |
| `list.deleted` | `{ listId }` |

`GET /me/export` adds `lists: List[]` (non-deleted, bounded to 1000, counted in `truncated`).

Later additions (format fields `marker`/`checkboxes`, `addedBy`/`addedAt` on items, `GET /lists/:id`,
`POST /lists/:id/convert`, `clientKey` on create): see "Lists, calendar, wake together (2026-10-29)".

Note for servers whose MongoDB user lacks `dbAdmin`: a database created by an earlier version keeps
an `events` validator that rejects `list.*`. The list request still succeeds; the event is sent live
over the WebSocket but is not stored (absent from `GET /events` and sync replay; no FCM push).
Clients should refresh `GET /lists` on app open / reconnect rather than rely on replay alone.

## Auto-memories, view-once photos, widget board, SOS, weather city

Additive: every existing field keeps its shape. Rows written before these features lack the new
fields; the server reads them as the defaults below, so clients always receive the full shape.

### Changed shapes

```ts
Message { ...as above,
          viewOnce: boolean,          // false for everything except view-once photos
          openedAt: string|null,      // when the recipient opened a view-once photo
          viewOnceExpired: boolean }  // view-once photo purged unopened (openedAt stays null)
Memory  { ...as above, sourceMessageId: string|null }   // chat photo it came from; null = manual memory
User    { ...as above, city: City|null, shareWeather: boolean /* default true */ }
Partner { ...as above, city: City|null }                 // null when unset OR that partner has shareWeather = false
City    { name: string, latitude: number, longitude: number }   // coordinates rounded to 2 decimals
WidgetBoard { authorId, note: string|null, emoji: string|null, mood: string|null,
              photoMediaId: string|null, updatedAt }
Sos     { id, coupleId, senderId, recipientId, message: string|null, createdAt, acknowledgedAt: string|null }
```

`GET /me/export` adds `sos: Sos[]` (bounded to 1000, counted in `truncated`) and
`widgetBoards: WidgetBoard[]` (both boards of the couple). View-once messages are exported with
`mediaId: null` (the row itself is kept).

### Auto-memories from chat photos

- `POST /messages` with `type: 'photo'` and no `viewOnce` creates a memory
  `{ mediaId, caption: null, takenAt: message.createdAt, createdBy: sender, sourceMessageId: message.id }`
  and emits `memory.created` (actor = sender) right after `message.created`. Idempotent replays create nothing.
- `DELETE /messages/:id` also soft-deletes that memory and emits `memory.deleted` (actor = sender).
- `DELETE /memories/:id` only soft-deletes the memory; the media object is never deleted by it, so the
  chat photo keeps working.
- `POST /memories` with a view-once media ⇒ `400 validation_failed`.

### View-once photos

`POST /messages` accepts `viewOnce?: boolean`. `viewOnce: true` with any `type` other than `photo`
⇒ `400 validation_failed` (`viewOnce: false` is accepted for every type). Additional errors:

| Status | `error.code` | When |
|---|---|---|
| 400 | `validation_failed` | the media was already sent as view-once (any new message reusing it) |
| 409 | `conflict` | `viewOnce: true` with a media already used by a live message, memory or widget board |

View-once messages never create a memory. Their media:

- `GET /media/:id/url` ⇒ `403 { error: { code: 'view_once' } }` for **both** members (always, also after opening).
- cannot be used as a memory or widget photo (`400`).

`POST /messages/:id/open` (no body; Bearer + active couple; recipient only):

→ `200 { url: string, expiresAt: string, message: Message }`. First call sets `openedAt` and emits
`message.opened` `{ messageId, openedAt }` (actor = recipient). `url` is a signed GET valid for
`VIEW_ONCE_URL_TTL_SECONDS` (default 120 s). Download it immediately; there is no second URL.

| Status | `error.code` | When |
|---|---|---|
| 400 | `validation_failed` | message is not a view-once photo |
| 403 | `forbidden` | caller is the sender |
| 404 | `not_found` | not in the caller's couple, deleted, or malformed id |
| 410 | `view_once_consumed` | already opened (also the loser of two concurrent opens) |
| 410 | `view_once_expired` | purged unopened (`viewOnceExpired: true`) |
| 409 | `not_paired` | no active couple |

Server-side lifecycle (cleanup worker, every `CLEANUP_INTERVAL_MS`): opened view-once media is deleted
(storage object removed, media `status: deleted`) once `openedAt + TTL + 60 s` has passed; unopened
view-once photos older than `VIEW_ONCE_UNOPENED_DAYS` (default 7) are deleted and their message reports
`viewOnceExpired: true`, `openedAt: null`. The message row stays in the history.

### Widget board

Each partner decides what the OTHER partner's home-screen widgets show. Bearer + active couple.

| Method | Path | Body | Success |
|---|---|---|---|
| GET | `/widget-board` | | `200 { mine: WidgetBoard\|null, forMe: WidgetBoard\|null }` — `mine` = what I set for my partner, `forMe` = what my partner set for me |
| PUT | `/widget-board` | `{ note?, emoji?, mood?, photoMediaId? }` (≥ 1 key) | `200 { board: WidgetBoard }` |

PUT rules: omitted keys keep their value; `null` clears; `note`/`mood` are trimmed and `''` clears.
`note` ≤ 140, `mood` ≤ 40, `emoji` 1–2 grapheme clusters (same rule as pings). Strict body.
`photoMediaId` must be a **ready** media of the same couple, category `photo` or `wallpaper`, not
view-once; either partner's upload is fine. The partner fetches it with the normal `GET /media/:id/url`.
Rate limit: 30 PUTs per user per minute (`RATE_LIMIT_WIDGET_WRITES_PER_MIN`).

| Status | `error.code` | When |
|---|---|---|
| 400 | `validation_failed` | empty body, limits, unknown key, photo unknown / not ready / wrong category / view-once |
| 403 | `forbidden` | `photoMediaId` belongs to another couple |
| 409 | `not_paired` | no active couple |
| 429 | `rate_limited` | budget exceeded (`Retry-After`) |

Each PUT emits `widget.updated` `{ board: WidgetBoard }` (actor = author); the outbox pushes it to the
other member like any other event. Boards are deleted on unlink and on account deletion. A board's
`photoMediaId` is not cleared if that media is later deleted (e.g. the chat message is unsent); the
URL call then answers `409 media_not_ready`, so render a placeholder.

### SOS

Bearer + active couple.

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/sos` | `{ message?: string }` (trimmed, ≤ 140; `''` ⇒ null; strict) | `201 { sos: Sos }` |
| GET | `/sos` | `?limit=1..20` (default 20) | `200 { items: Sos[] }` newest first |
| POST | `/sos/:id/ack` | | `200 { sos: Sos }` recipient only; idempotent |

| Status | `error.code` | When |
|---|---|---|
| 400 | `validation_failed` | message > 140, unknown key, `limit` out of range |
| 403 | `forbidden` | ack by the sender |
| 404 | `not_found` | ack of an SOS from another couple / unknown / malformed id |
| 409 | `not_paired` | no active couple |
| 429 | `rate_limited` | more than 3 SOS by the same sender in 10 minutes (`Retry-After` = seconds until a slot frees) |

Events: `sos.raised` `{ sos: Sos }` (actor = sender); `sos.acknowledged` `{ sosId, acknowledgedAt }`
(actor = recipient, emitted once). **Push:** `sos.raised` is pushed even when the recipient has
`notificationsEnabled: false` or is inside quiet hours. It is still skipped when push is disabled on the
server (`push_disabled`) or the recipient has no device tokens (`no_tokens`). Every other event follows the
push policy in "Push delivery and delivery health" below. The SOS message is never logged.

### Weather city

`PATCH /me` additionally accepts:

```ts
{ city?: { name: string /* trimmed, 1–80 */, latitude: number /* -90..90 */, longitude: number /* -180..180 */ } | null,
  shareWeather?: boolean }
```

`city` is strict (unknown keys ⇒ 400) and the coordinates must be JSON numbers. They are rounded to
2 decimals (`25.594095` ⇒ `25.59`). `null` clears. Changing `city` or `shareWeather` emits
`partner.updated` `{ partner: Partner }`; a PATCH that changes nothing emits nothing.

### New event types

| Event type | `payload` |
|---|---|
| `message.opened` | `{ messageId, openedAt }` |
| `widget.updated` | `{ board: WidgetBoard }` |
| `sos.raised` | `{ sos: Sos }` |
| `sos.acknowledged` | `{ sosId, acknowledgedAt }` |

Push payload is unchanged: `{ type, eventId, coupleId, seq }`. On a database whose old `events`
validator still has the strict type enum (no `dbAdmin`), these types are delivered live over the
WebSocket only (not stored, no replay, no push); the HTTP request still succeeds. Fresh databases use
the dotted-name pattern and accept them.

New error codes: `view_once` (403), `view_once_consumed` (410), `view_once_expired` (410).

## Games (rock–paper–scissors with truth or dare) and shared chat wallpaper

Additive. All routes: Bearer + active couple (`409 not_paired` otherwise).

### Why games are not chat messages

The `messages` collection validator has had a strict `type: { enum: ['text','emoji','photo','voice'] }`
since v1. The production DB user cannot run `collMod`, so the stored validator on the live database would
reject `type: 'game'`. Games therefore live in their own `games` collection (new collections get the new
validator via `createCollection`, which readWrite allows) and **messages are untouched**. Clients render
games inside the chat by merging `GET /games` (and `game.updated` events) into the message timeline by
`createdAt` (a game's card can then update in place by `id`).

### Shapes

```ts
RpsMove = 'rock'|'paper'|'scissors'|'thread'
GameStatus = 'waiting'|'draw'|'revealed'|'challenged'|'chosen'|'awaiting_confirmation'|'completed'|'cancelled'

// HTTP responses: viewer-specific
Game { id, coupleId, kind: string /* 'rps' here; see More games */, createdBy, createdAt, updatedAt,
       status: GameStatus,
       myMove: RpsMove|null,          // my move in the current round
       partnerMoved: boolean,         // partner has moved in the current round
       partnerMove: RpsMove|null,     // null until BOTH moved this round (never revealed by cancel)
       winnerId: string|null, loserId: string|null,   // set when status reaches 'revealed'
       challenge: GameChallenge | null,               // null until the winner sets it
       answer: string|null,           // the loser's answer; null before 'awaiting_confirmation'
       completedAt: string|null,      // set by confirm
       round: number /* ≥ 1, +1 per rematch */ }

GameChallenge {                       // viewer-specific, see "Blind visibility"
  truth: string|null,                 // winner: always; loser: only after choosing 'truth'
  dare: string|null,                  // winner: always; loser: only after choosing 'dare'
  chosen: 'truth'|'dare'|null,        // the loser's blind pick
  by: string,                         // the winner (who wrote both)
  at: string,                         // when the challenge was set
  chosenAt: string|null,
  doneAt: string|null,                // last complete; cleared by a reject
  confirmedAt: string|null,           // winner confirmed (status completed)
  rejectNote: string|null }           // winner's note on the last reject; cleared by the next complete

// game.updated payload: viewer-neutral, NEVER contains truth/dare texts or the answer
GameEventView { id, coupleId, kind, createdBy, createdAt, updatedAt, status,
                movedBy: string[],                            // user ids that moved this round (order of moving)
                moves: { userId: string, move: RpsMove }[] | null,   // both moves once both moved, else null
                winnerId, loserId, completedAt, round,        // same as Game
                challenge: { chosen: 'truth'|'dare'|null, hasChallenge: true,
                             chosenAt: string|null, doneAt: string|null, confirmedAt: string|null } | null }
                // no `answer` key
```

To get the viewer view from an event: `myMove`/`partnerMove` = `moves?.find(userId == me / != me)?.move`
(null while `moves` is null); `partnerMoved` = `movedBy` contains the partner. The texts and the answer are
only in HTTP responses: on any `game.updated` whose `challenge` is non-null, **re-fetch `GET /games/:id`**.

### Blind visibility

Enforced server-side in every HTTP response (`GET /games/:id`, `GET /games`, write responses, `details.game`
of errors, `GET /me/export`):

| Viewer | `challenge.truth` / `challenge.dare` | `answer` |
|---|---|---|
| winner | both texts, always | from `awaiting_confirmation` on |
| loser, before `choose` | both `null` | `null` |
| loser, after `choose` | only the chosen text; the other stays `null` forever (also after completion or cancel) | from `awaiting_confirmation` on |

`answer` is shown (to both) in `awaiting_confirmation` and `completed`, and in `cancelled` if the game was
cancelled while awaiting confirmation; a reject clears it.

### Lifecycle

```
waiting ──both moved──▶ draw ──rematch──▶ waiting (round + 1, moves cleared)
        └─────────────▶ revealed ──challenge (winner: truth + dare)──▶ challenged
challenged ──choose (loser, blind)──▶ chosen ──complete (loser)──▶ awaiting_confirmation
awaiting_confirmation ──confirm (winner)──▶ completed
awaiting_confirmation ──reject (winner, optional note)──▶ chosen   (answer cleared, doneAt cleared)
any status except completed ──cancel (either)──▶ cancelled
```

Rock beats scissors, scissors beat paper and thread, paper beats rock, thread beats rock and paper. At most
one **open** game (status not `completed`/`cancelled`) per couple.

### Endpoints

| Method | Path | Body | Success | Who / when |
|---|---|---|---|---|
| GET | `/games/suggestions` | | `200 { truths: string[20], dares: string[20] }` | anyone paired |
| POST | `/games` | `{ kind: 'rps' }` | `201 { game: Game }` (`status: waiting`, `round: 1`) | no open game in the couple |
| GET | `/games` | `?limit=1..50` (default 20) | `200 { items: Game[] }` newest first (`createdAt` desc) | |
| GET | `/games/:id` | | `200 { game: Game }` | |
| POST | `/games/:id/move` | `{ move: RpsMove }` | `200 { game: Game }` | either player, status `waiting`, once per round |
| POST | `/games/:id/rematch` | (none) | `200 { game: Game }` | either player, status `draw` |
| POST | `/games/:id/challenge` | `{ truth: string, dare: string }` (each trimmed, 1–200) | `200 { game: Game }` (`challenged`) | **winner**, status `revealed` |
| POST | `/games/:id/choose` | `{ kind: 'truth'\|'dare' }` | `200 { game: Game }` (`chosen`) | **loser**, status `challenged` |
| POST | `/games/:id/complete` | optional: none, `{}` or `{ answer?: string }` (trimmed ≤ 500; `''` ⇒ null) | `200 { game: Game }` (`awaiting_confirmation`) | **loser**, status `chosen`; chosen `truth` ⇒ non-empty `answer` required (400 otherwise); for a dare it is optional |
| POST | `/games/:id/confirm` | (none) | `200 { game: Game }` (`completed`, `completedAt` + `challenge.confirmedAt` set) | **winner**, status `awaiting_confirmation` |
| POST | `/games/:id/reject` | optional: none, `{}` or `{ note?: string }` (trimmed ≤ 200; `''` ⇒ null) | `200 { game: Game }` (back to `chosen`; `answer` and `doneAt` cleared, `rejectNote` set) | **winner**, status `awaiting_confirmation` |
| POST | `/games/:id/cancel` | (none) | `200 { game: Game }` (`cancelled`) | either player, status ≠ `completed`; idempotent (already cancelled ⇒ 200, no event) |

All bodies are strict (unknown keys ⇒ 400; the old `{ kind, text }` challenge body is rejected). Bodiless
POSTs must be sent without a JSON `Content-Type` (or with `{}`). Suggestions are ≤ 200 chars, so they can be
sent as `truth`/`dare` unchanged. A truth answer submitted after a reject must again be non-empty.

| Status | `error.code` | When |
|---|---|---|
| 400 | `validation_failed` | bad body / `kind` / `move` / text, answer or note length / missing `truth` or `dare` / `complete` of a chosen truth without a non-empty `answer` (`details: [{ path: '/answer' }]`) / `limit`; malformed `:id` |
| 403 | `forbidden` | right status but wrong player: challenge, confirm, reject by the loser; choose, complete by the winner |
| 404 | `not_found` | game unknown or in another couple |
| 409 | `game_in_progress` | `POST /games` while an open game exists; `details.game` = that game (viewer view) |
| 409 | `already_moved` | second move by the same player in a round; `details.game` = current game |
| 409 | `invalid_game_state` | action not allowed in the current status (move when not waiting, rematch when not draw, challenge when not revealed, choose when not challenged, complete when not chosen, confirm/reject when not awaiting_confirmation, cancel when completed); `details.game` = current game (viewer view) |
| 409 | `not_paired` | no active couple |
| 429 | `rate_limited` | more than 30 game writes per user per minute (`RATE_LIMIT_GAME_WRITES_PER_MIN`; all POSTs above except suggestions share it), `Retry-After` |

Status is checked before the role, so e.g. the winner calling `complete` on a `revealed` game gets
`409 invalid_game_state`, and on a `chosen` game gets `403 forbidden`.

### Event

| Event type | `payload` |
|---|---|
| `game.updated` | `{ game: GameEventView, action: 'created'\|'moved'\|'revealed'\|'draw'\|'rematch'\|'challenged'\|'chosen'\|'done'\|'confirmed'\|'rejected'\|'cancelled' }` |

Emitted once per successful write (actor = caller): `challenge` ⇒ `'challenged'`, `choose` ⇒ `'chosen'`,
`complete` ⇒ `'done'`, `confirm` ⇒ `'confirmed'`, `reject` ⇒ `'rejected'`. The second move of a round emits
`'revealed'` or `'draw'` with both `moves`; a first move emits `'moved'` with `moves: null` and only
`movedBy`. A move is never present in any payload (event, `GET /events`, WebSocket, push) before both
players moved, and the truth/dare texts, the answer and the reject note are never present in any event at
all. Push goes through the normal outbox to the other member, at the priority its `action` gets in "Push
delivery and delivery health" below (push data is the usual `{ type, eventId, coupleId, seq, pr }`). (If two moves race, both requests
succeed and the later event may also say `'moved'` while already carrying the revealed state: render from
`status`.)

`GET /me/export` adds `games: Game[]` (viewer-specific, newest first, bounded to 1000, counted in `truncated`).

### More games: tic-tac-toe, trivia duel, This or That

Additive (games stream). Everything above still holds for `rps`, and older apps keep working: `{ kind: 'rps' }`,
`/move` and every `Game` field they read are unchanged. New kinds: `tictactoe`, `trivia`, `this_or_that`.
Still **one open game per couple, of any kind** (`coupleId_open_unique`); the app's picker offers "End it and start".

**Tolerant reader.** In responses `kind` is a plain string (requests still validate the enum): responses are
serialized through the Zod schemas, so an enum there would turn `GET /games`, `GET /games/:id` and
`GET /me/export` into a 500 as soon as the database held a kind the running build doesn't know (for example
after a rollback). Clients must render an unknown kind as "Update Risaina to play this game".

**Prize.** `Game.prize: 'truth_or_dare' | 'none'` (absent on older games ⇒ `'truth_or_dare'`). With
`'truth_or_dare'` a decided round goes to `revealed` and the blind truth or dare above follows, for any
competitive kind. With `'none'` ("just for fun") a decided round goes straight to `completed` (winner and loser
set, `challenge: null`). This or That has no winner and always reports `'none'`.

```ts
// added to Game (HTTP, viewer-specific); rps fields stay present for every kind (myMove: null, partnerMoved: false, ...)
Game += { prize: 'truth_or_dare'|'none',
          ttt: TicTacToe | null,      // tictactoe only
          quiz: Quiz | null }         // trivia and this_or_that only

TicTacToe { board: ('x'|'o'|null)[9] /* index = row*3+col */, xId, turnId: string|null /* null once over */,
            lastCell: number|null, line: number[3]|null /* winning cells */, moveCount: 0..9,
            myMark: 'x'|'o', myTurn: boolean }

Quiz { source: 'opentdb'|'builtin'|'curated',
       attribution: string|null,      // "Questions from Open Trivia Database (opentdb.com), CC BY-SA 4.0. Category names shortened." when source is opentdb
       category: { id, name }|null,   // trivia: where the questions came from
       requestedCategoryId: number|null,  // trivia: what was asked for; null = any (send it back for "Play again")
       difficulty: 'easy'|'medium'|'hard'|null, deck: string|null,
       total: number,                 // trivia 5, this_or_that 7
       items: QuizItem[],             // see visibility below
       myAnswered, partnerAnswered,   // counts, always
       myFinished, partnerFinished,   // booleans, always
       myScore: number|null,          // trivia only, always
       partnerScore: number|null,     // trivia, once BOTH finished
       matches: number|null }         // this_or_that, once BOTH finished

QuizItem { index, text: string|null /* null for This or That */, choices: string[] /* trivia 4, this_or_that 2 */,
           category: string|null, difficulty: string|null,
           myChoice: number|null,
           correct: number|null,      // trivia: only for items the viewer already answered
           partnerChoice: number|null }  // only once BOTH finished

// added to GameEventView (events, viewer-neutral): never question text, choices, correct answers or picks
GameEventView += { prize, ttt: TicTacToe minus myMark/myTurn | null,
                   quiz: { source, categoryName, deck, total,
                           progress: { userId, answered, finished }[],
                           scores: { userId, score }[] | null,   // trivia, once both finished
                           matches: number|null } | null }
```

**Quiz visibility** (every HTTP response, `details.game` of errors and `GET /me/export`):

| | Trivia duel | This or That |
|---|---|---|
| `items` | the answered ones plus the current one (all once both finished; only answered ones after a cancel) | all 7 prompts from the start (they aren't secret) |
| `correct` | only for items the viewer answered | always null |
| `partnerChoice`, `partnerScore`, `matches` | only once both finished, never after a midway cancel | same |

**Endpoints** (same auth and shared write rate limit as above):

| Method | Path | Body | Success | Notes |
|---|---|---|---|---|
| GET | `/games/catalog` | | `200 { kinds: {kind,title,emoji,blurb,competitive}[], trivia: {questionCount, difficulties, categories: {id,name}[], attribution, attributionUrl, licenseUrl}, thisOrThat: {promptCount, decks: {id,name,emoji}[]} }` | On an older server this path hits `/games/:id` and answers `404`, which the app reads as "rps only" |
| POST | `/games` | one of `{ kind:'rps', prize?:'none' }`, `{ kind:'tictactoe', prize?:'none' }`, `{ kind:'trivia', prize?:'none', category?: 9\|11\|12\|14\|15\|17\|21\|22\|23\|27, difficulty?: 'easy'\|'medium'\|'hard' }`, `{ kind:'this_or_that', deck?: 'mix'\|'everyday'\|'us'\|'travel_food'\|'dreams' }` | `201 { game }` | Strict per kind. `prize` accepts only `'none'`: the default is expressed by leaving it out, so a "Play again" body is valid on every server version. Trivia waits up to `TRIVIA_TIMEOUT_MS` for questions |
| POST | `/games/:id/play` | one of `{ kind:'rps', move }`, `{ kind:'tictactoe', cell: 0..8 }`, `{ kind:'trivia', item: 0..4, choice: 0..3 }`, `{ kind:'this_or_that', picks: (0\|1)[] }` | `200 { game }` | `kind` must match the game. Trivia: `item` must be the next unanswered one, so a retried answer gets `409 already_moved`, never the next question. This or That: all picks in one request, one per prompt, in order |
| POST | `/games/:id/end` | (none) | `200 { game }` (`completed`, no winner) | Either player, status `draw` only; calls the draw off |
| POST | `/games/:id/rematch` | (none) | `200 { game }` | Status `draw` (rps, tictactoe, trivia). Tic-tac-toe: board cleared, X swaps (the previous O moves first). Trivia: 5 new questions, both players reset. This or That never draws |

Order of checks on `/play` and `/move`: the kind (`400`, message "Update Risaina to play this game",
`details: [{ path: '/kind' }]`), then the status (`409 invalid_game_state`), then the turn or role, then the move.
So an older app tapping on a newer kind of game, even a finished one, is always told to update.

| Status | `error.code` | When |
|---|---|---|
| 409 | `not_your_turn` | tic-tac-toe move by the player whose turn it isn't; `details.game` |
| 409 | `invalid_move` | tic-tac-toe cell already taken; trivia `item` ahead of the next unanswered one ("Answer question n first"); `details.game` |
| 409 | `already_moved` | trivia `item` already answered; This or That picks already sent |
| 400 | `validation_failed` | kind mismatch (path `/kind`); This or That `picks` of the wrong length (path `/picks`); option on the wrong kind |

**Rules.**
- Tic-tac-toe: the invitee plays X and moves first; X swaps on each rematch. A line ⇒ `revealed` (or
  `completed` with prize `'none'`), a full board ⇒ `draw`. Each move is one conditional write on
  `moveCount` and `turnId`, so two taps can never both land.
- Trivia duel: 5 questions fetched **server-side** from Open Trivia Database (one curated category; "any"
  picks a random curated one; base64; 3.5 s timeout; one call per 5.5 s per process). On a timeout, network
  error, rate limit (HTTP 429 with `response_code` 5), bad answer or within the throttle it falls back to a
  built-in set written in-house (`source: 'builtin'`). Answers are sequential and final; when both finished,
  the higher score wins, equal scores are a `draw`.
- This or That: 7 curated PG prompts from a deck, avoiding the couple's last 5 rounds when possible. When both
  sent their picks the game is `completed` with `quiz.matches` (no winner, no prize).

**Events.** Still `game.updated`. New actions: `answered` (one trivia answer that didn't finish the player's
set), `finished` (this player is done, the partner isn't), `matched` (This or That, both done) and `ended` (a
draw called off). Whether an action is pushed is decided by the outbox push policy (delivery stream), not by
the games module. Under that policy `answered`, `cancelled` and `ended` are stored and sent live over the
WebSocket but not pushed; the others are pushed to the partner. Until the policy is merged, every
`game.updated` is pushed at high priority, these three included, and they show no notification. FCM may
deprioritize an app whose high-priority pushes don't lead to a visible notification, so the games backend must
not reach production before the delivery policy.

**Rollout.** Deploy the tolerant-reader commit first, then the games backend, then the APK on both phones.
Before any rollback of the games backend, cancel open games of the new kinds: an older build's
`findOpen` would still see them and block new rps games.

### Shared chat wallpaper

```ts
Couple.sharedPreferences = { ...flat client keys (string|number|boolean),
                             chatWallpaper: ChatWallpaper | null }     // always present; null = app default
ChatWallpaper { style: 'DEFAULT'|'PAPER'|'HEARTS'|'NIGHT'|'GLOW'|'PHOTO',
                photoMediaId: string|null,    // non-null only for PHOTO
                setBy: string, setAt: string }
```

`PATCH /couple` additionally accepts `chatWallpaper: { style, photoMediaId? } | null` (either member;
`null` resets to the default; strict object):

- `style: 'PHOTO'` requires `photoMediaId`: a **ready** media of the same couple, category `photo` or
  `wallpaper`, not view-once (either partner's upload). Other styles must omit it or send `null`.
- Errors: `400 validation_failed` (unknown style, PHOTO without id, id with a non-PHOTO style, media unknown /
  not ready / wrong category / view-once), `403 forbidden` (media of another couple) — same rules as the
  widget-board photo.
- Both members download the photo with `GET /media/:id/url`.
- Emits `couple.updated { couple: Couple }` (as every `PATCH /couple`).
- `PATCH /couple { sharedPreferences }` still replaces the flat map and keeps the wallpaper; a
  `chatWallpaper` key inside `sharedPreferences` is ignored, so clients may echo the map back as received.
- Like widget photos, the wallpaper is not cleared if its media is later deleted; `GET /media/:id/url`
  then answers `409 media_not_ready` — fall back to `DEFAULT`.

New error codes: `game_in_progress` (409), `already_moved` (409), `invalid_game_state` (409).

## Unlink purge

`POST /couple/unlink { confirm: true }` and `DELETE /me` (for a paired user) end the couple and **delete
everything it created**. The response is still `204`; nothing can be recovered afterwards (offer
`GET /me/export` before unlinking).

- Deleted at once: messages, pings, memories, milestones, lists, games, widget boards, SOS alerts,
  live-location sessions, calendar entries, wake-together alarms, invitations of the couple, and every
  event of the couple **except** the final `couple.unlinked`.
- Media: all of the couple's media becomes unavailable at once; storage objects and then their media
  records are deleted in the background (failures are retried by the cleanup worker). Both members'
  `avatarMediaId` is cleared when the avatar was uploaded in this couple (avatars live under the couple's
  storage prefix); the partner receives no `partner.updated` for this (the couple is gone).
- The couple record is reduced to `status: 'ended'`, `memberIds`, `endedAt`, `endedBy`;
  `relationshipStartDate`, `nextMeetingDate`, `nextMeetingLabel` become `null`, `sharedPreferences` `{}`
  and the chat wallpaper is removed.
- `couple.unlinked { coupleId, endedAt, by }` is still delivered to the partner: live over the WebSocket
  (followed by `{ type: 'revoked' }` + close 4403) and as a push through the outbox.
- For **7 days** after the unlink, a former member who has not paired again can still read that one event:
  `GET /events?since=<seq>` answers `200 { items: [couple.unlinked] (if seq > since), latestSeq }` (instead
  of `409 not_paired`), and on the WebSocket (`ready.coupleId` is `null`) a `sync` replays it before
  `synced`. Only `couple.unlinked` is ever returned for the ended couple. After 7 days the event is deleted
  and `GET /events` is `409 not_paired` again. Once paired again, the feed is the new couple's.
- Re-pairing works as usual with a fresh invitation; the new couple starts empty (new `coupleId`, `seq`
  from 1).
- Idempotent: a second unlink is `409 not_paired`; the server re-runs the purge for 7 days to remove
  anything written concurrently with the unlink.

## Push delivery and delivery health (stream: delivery)

### Push data
FCM data-only message: `{ type, eventId, coupleId, seq, pr, ref? }`. Never content.
- `pr`: the priority the server sent (`h` high, `n` normal). The app compares it with what arrived
  (`RemoteMessage.priority` vs `originalPriority`) to spot FCM lowering this phone's pushes.
- `ref`: an opaque id, only for `message.created` (the message id) and `ping.created` (the ping id). The
  app uses it to post a quick, generic notification before it syncs, under the id the detailed one
  replaces in place, and to skip that quick alert when an earlier sync already brought the item.

### Priority per event type (`services/push/policy.ts`, `pushPolicyFor(type, payload)`)
FCM lowers an app's high-priority pushes to normal (per phone, judged over about 7 days) when they rarely
end in a visible notification. So only events the recipient SEES are high.

| Priority | Event types | FCM `android` options |
|---|---|---|
| high | `ping.created`, `message.created`, `couple.unlinked`, `widget.updated` (the app posts a quiet "<partner> updated your widget"), `location.started` (except a panic share linked to its SOS), `game.updated` with `action` ∈ created, moved, revealed, draw, rematch, challenged, chosen, done, confirmed, rejected, finished, matched, `alarm.created` / `alarm.updated` with `notify: true` (see "Wake together") | `priority: high`, ttl 1 h, no collapse key |
| high, bypasses preferences | `sos.raised`, `sos.resolved` | same |
| normal (silent: the app syncs and redraws widgets) | `ping.delivered`, `ping.seen`, `message.read`, `message.opened`, `message.deleted`, `media.ready`, `list.*`, `memory.*`, `milestone.*`, `partner.updated`, `couple.updated`, `sos.acknowledged`, `sos.delivered`, `location.stopped`, `location.started` for a panic share with an `sosId` (the app posts nothing for it; the SOS alarm is the alert), `game.updated` with any other or no action, `calendar.*` (also with `notify: true`: the app posts that notice when the sync applies the event), `alarm.created` / `alarm.updated` without `notify: true`, `alarm.deleted`, `alarm.woke`, and any type not listed | `priority: normal`, ttl 24 h, `collapseKey: "sync"` (a later silent push replaces an undelivered one) |
| none (stored, never pushed; `push.state: skipped`, `skipReason: not_pushed`) | `game.updated` with `action` ∈ answered, cancelled, ended | |

Recipient preferences (changed): with `notificationsEnabled: false` a high push is sent as a silent
**normal** push instead of being skipped, so the widgets still update; the app posts nothing. Quiet hours no
longer change the push at all: it stays high and the app posts on its silent "quiet" channels (unless
`allowPushInQuietHours`). Before, both cases were skipped for good and froze the widgets. SOS types always
stay high. Later, when FCM `AndroidConfigV2` is out of preview: high-with-notification maps to
`remote_notification` (mutable content) and normal maps to `background_sync`.

### Devices: test push and health
| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/devices/test-push` | `{ deviceId?, delaySeconds: 0–60 = 0 }` | `200 { scheduled, devices, reason: null \| 'push_disabled' \| 'no_tokens' }` |
| GET | `/devices/health` | | `200 { mine: DeviceHealth[], partner: DeviceHealth[] }` |

`test-push` sends a high `{ type: "test.push", eventId: "test-<id>", coupleId: "", seq: "0", pr: "h" }`
straight to the caller's own phones (only `deviceId` when given; never the partner; never stored as an
event; ttl 10 min). The app answers it with a visible "Test notification" even with notifications switched
off in-app, because the person asked for it. 6 per user per 10 minutes (`429 rate_limited`).

`DeviceHealth`: `{ deviceId (own phones only, else null), model, sdkInt: number|null, appVersion, updatedAt,
lastPushSentAt, lastPushAckAt, lastPushPriority: 'high'|'normal'|null, lastPushDowngraded, healthFlags: string[] }`. Tokens
are never returned. `appVersion` is the build id (`0.2.0+<git sha>`), so it shows which build a phone runs.

### Push acknowledgement (on `GET /events`)
The app says "this phone received a push" on the `GET /events` request its sync makes anyway (no extra
request inside the few seconds Android gives the push handler):
`X-Risaina-Device: <deviceId>`, `X-Risaina-Push: <priority>/<originalPriority>` (`high`|`normal` each),
optional `X-Risaina-Health: flag,flag` (≤ 16 snake_case codes such as `battery_optimized`,
`standby_rare`, `background_restricted`, `data_saver`, `notifications_off`, `channel_off`,
`force_stopped`). The server stores `lastPushAckAt`, `lastPushPriority`, `lastPushDowngraded` (sent high,
arrived normal) and `healthFlags` on that device row. Malformed headers are ignored; they never fail the
request.

### Refresh grace window
`POST /auth/refresh` accepts an optional `deviceId` (the app's install id). Presenting a refresh token that
was already rotated normally revokes the whole family. Exception: within **60 s** of the rotation, from the
same `deviceId`, while the session it was rotated into has never been used (no request with its access
token, no refresh), the server revokes that unused session and issues a fresh pair in the same family. This
covers a phone frozen or killed after the server rotated its token but before it stored the answer. The
server stores only hashes, so it cannot resend the earlier pair. A session's first authenticated request
sets its `lastUsedAt`.

## Safety: SOS receipts, "I'm safe now", test alarm and live location

Bearer + active couple unless noted. The SOS message and every coordinate are private content: they
are never logged (`REDACT_PATHS` covers `fix`, `location`, `lat`, `lng`), and coordinates never appear
in events or pushes.

### SOS additions

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/sos` | `{ message?, panic?: boolean, idempotencyKey?: string (1–64) }` (strict) | `201 { sos }`. A repeat with the same `idempotencyKey` returns the first SOS and does not count against the limit; it emits `sos.raised` only if the first request stored the SOS but never emitted it (a failed emit, a restart). `503` with `Retry-After` while another request is emitting it |
| POST | `/sos/:id/delivered` | optional device state (below) | `200 { sos }`, recipient only, idempotent: sets `deliveredAt` once, emits `sos.delivered` and stops re-pushes |
| POST | `/sos/:id/resolve` | | `200 { sos }`, sender only ("I'm safe now"), idempotent: sets `resolvedAt`, emits `sos.resolved` once and ends live sharing linked to that SOS (`location.stopped`, reason `resolved`) |
| POST | `/sos/test` | `{ delaySeconds?: 0–60 }` (default 15) | `202 { queued, devices }`: a high-priority `{ type: 'sos.test' }` push to **my own** phones after the delay. No database write, no event. 3 per user per 10 minutes. Bearer only (no couple needed) |

`Sos` gains `panic: boolean` (absent in old documents ⇒ `false`), `deliveredAt`, `resolvedAt` and
`delivery` (what the recipient's phone reported, or null). The delivered body carries device state
only, never content, so a quiet alarm can be explained afterwards on a phone without adb:
`{ priority, originalPriority, latencyMs, alarmMuted, alarmVolume (0–100), ringerMode, interruptionFilter,
notificationsEnabled, canUseFullScreenIntent, fgsStarted }`, every field optional and nullable. Unknown
keys are dropped, not refused.

Errors: `403 forbidden` (delivered by the sender, resolve by the recipient), `404 not_found` (unknown or
another couple's SOS), `429 rate_limited` (tests).

**Re-push (SosRepushWorker, every 30 s):** an SOS that is not delivered, acknowledged or resolved is
pushed again at most once a minute, for its first 10 minutes, up to 6 times, as a high-priority
`{ type: 'sos.raised', eventId, coupleId, seq, sosId, at }` (`at` = creation time in epoch ms) sent
through PushService directly. Each SOS is claimed with a conditional `findOneAndUpdate`, so two server
instances never double-send. The phone de-duplicates by SOS id and event seq, and never rings an SOS it
already rang, acknowledged or saw resolved, or one older than 6 hours.

### Live location

| Method | Path | Guards | Input | Success | Errors |
|---|---|---|---|---|---|
| GET | `/location/sharing` | couple | | `200 { mine: LiveLocation\|null, partner: LiveLocation\|null }` (expired sessions are null) | 401, 409 `not_paired` |
| POST | `/location/sharing` | couple; 20 manual starts per user per hour; panic starts have their own 20 per user per 10 minutes | `{ sessionId, mode: 'timed'\|'until_off', durationMinutes? (1–1440, required iff timed; a retry or Resume sends the time left), reason?: 'manual'\|'panic', sosId? (panic only), fix? }` | `201 { sharing }` new, `200` extended | 400; 404 (`sosId` not my SOS); 409 `location_not_sharing` (that `sessionId` was stopped), `not_paired`; 429 |
| PUT | `/location/sharing/fix` | couple; 12 per user per minute | `{ sessionId, fix }` | `200 { accepted }` | 400; 409 `location_not_sharing`, `not_paired`; 429 |
| DELETE | `/location/sharing` | bearer only (stopping always works) | query `sessionId?`, `reason?: 'stopped'\|'expired'` | `204` always | 401 |

- `fix` input: `{ lat (-90..90), lng (-180..180), accuracyM (0..1 000 000, clamped to 50 000), ageMs
  (0..600 000, how long ago the phone took it), batteryPct? (0..100) }`. `ageMs` comes from the phone's
  monotonic clock, so a phone with a wrong wall clock is still placed correctly; the server stores
  `at = now - ageMs`. Coordinates are rounded to 5 decimals. Battery is kept in both modes.
- The **phone makes `sessionId`** (an ObjectId hex) so a stop sent right after a start can name it. A stop
  that arrives before its own start leaves a 10-minute tombstone, and that start then answers `409
  location_not_sharing`. A stale stop never ends a newer session.
- Extending: a second POST for my live session updates it. A manual start never turns a panic session
  back into manual, and panic never shortens a session (a manual change can). `location.started` is
  emitted again only when the reason, the expiry (by more than a minute) or the SOS link changed.
- `PUT …/fix` always refreshes `lastSeenAt` and the idle purge, even when the fix is not newer than the
  stored one (`accepted: false`), so a phone that is sitting still still looks live. It writes no event
  and sends no push.
- `LiveLocation`: `{ sessionId, userId (sharer), reason, sosId|null, startedAt, expiresAt|null (until off),
  updatedAt, lastSeenAt, fix: { lat, lng, accuracyM, at, batteryPct|null } | null }`.
- **WebSocket frame** to the viewer only (never the sharer's own sockets), on every start, extend and
  check-in (so liveness updates even when the position did not change): `{ "type": "location",
  "location": LiveLocation }`. A check-in that loses a race with a stop sends no frame. Fire-and-forget: never stored,
  never replayed; a lost frame is replaced by the next one or by the GET the app makes on reconnect.
  Older apps ignore the unknown frame type.
- Sign-out, revoking all sessions and account deletion delete the user's sessions (partner gets
  `location.stopped`, reason `signed_out`); unlink purges both members' sessions.
- `GET /me/export` gains `liveLocation`: my own current session or null (never my partner's).

### Safety event types

| Event type | `payload` | Push |
|---|---|---|
| `sos.delivered` | `{ sosId, deliveredAt }` | normal (silent) |
| `sos.resolved` | `{ sosId, resolvedAt }` | high, skips mute settings (it stops a ringing alarm) |
| `location.started` | `{ sharing: LiveLocationSession }` (the `LiveLocation` fields without `lastSeenAt` and `fix`) | high (the partner is told sharing started); normal for a panic share linked to its SOS (the alarm is the alert) |
| `location.stopped` | `{ sessionId, userId, stoppedAt, reason: 'stopped'\|'expired'\|'resolved'\|'signed_out' }` | normal |

Push priorities are decided by the outbox's policy (delivery stream). The phone treats a push of
`{ type: 'sos.test' }` as a 5-second test alarm.

## Lists, calendar, wake together (2026-10-29)

Additive. Apps that do not know these features (v75, the current debug APK) keep working: they ignore
the new response fields and event types, never call the new routes, and every existing route, field and
event keeps its meaning. All new routes: Bearer + active couple (`409 not_paired` otherwise); a malformed
`:id` is `400`, an id that is unknown or in another couple is `404 not_found`.

New collections (`calendarEntries`, `alarms`) are created at boot with validators that have no enums and
no `additionalProperties`: the production DB user cannot change a validator later, so a future kind, mode
or field must already pass it. Zod in the API is the allow-list.

### Event types are plain strings in responses

`Event.type` in `GET /events` (and in every event frame) is a plain string in the response schema, not an
enum of the types this build knows. The values on the wire are unchanged. Before, one stored event of a
type the running build did not know (for example `calendar.created` after rolling the server back) made
`GET /events` answer 500 for the whole couple. Clients must ignore types they do not know (v75 does).

### Lists additions

```ts
List     { ...as above,
           marker: string,       // 'none' (default) | 'number' | 'bullet'; a plain string: tolerate unknown values
           checkboxes: boolean } // default true
ListItem { ...as above,
           addedBy: string|null, // who added the item; null on items from before this field
           addedAt: string|null }
```

- `POST /lists` and `PATCH /lists/:id` take optional `marker: 'none'|'number'|'bullet'` and `checkboxes:
  boolean`. Lists created before read as `'none'` / `true`. A PATCH changes only the fields it sends, so a
  v75 save (title/items only) keeps them. They are a format, not a new `kind` (`kind` stays
  `'note'|'checklist'`, and `PATCH { kind }` is still 400).
- `addedBy` / `addedAt` are server-owned, like `doneBy` / `doneAt`: stamped (caller, now) on POST items and
  on new items of a PATCH (no `id`, or an `id` the list does not have); an existing item keeps its own.
  Values sent by a client are ignored. An existing item also keeps any other field stored on it, so a field
  a later app adds survives a save from an app that sends only `id`/`text`/`done`.
- `POST /lists` takes an optional `clientKey: string` (1–64), chosen by the phone. A repeat with the same key
  in the same couple (even from the partner's account) answers `200 { list }` with the list the first request
  created and emits nothing; if that list has been deleted since: `404`. `clientKey` is not returned.
- `GET /lists/:id` → `200 { list }`; `404` when the list is deleted or not in the couple.
- `POST /lists/:id/convert { to: 'note'|'checklist', marker?, checkboxes?, expectedVersion? }` → `200 { list }`
  (`version` + 1, emits `list.updated { list }`, same 409 `version_conflict` rule as PATCH, same write budget):
  - note → checklist: each non-blank body line becomes an item, in order, after any items the note already
    holds. A leading bullet (`- `, `* `, `• `), number (`1.`, `2)`) and checkbox (`[ ]`, `[x]`, `☐`, `☑`, `✓`,
    `✅`) are stripped, in either order; a ticked box makes the item `done` (doneBy = caller). A line over
    300 characters becomes several items, cut at spaces. Once 200 items are reached, that line and every
    line after it stay in `body`, unchanged; otherwise `body` becomes `''`.
  - checklist → note: the items are written as lines below the existing body, drawn as the list was shown:
    `1. ` / `• ` by `marker`, then `☐ ` / `☑ ` when `checkboxes`, then the text (`1. ☑ milk`). Items whose line
    would take the body over 10,000 characters stay in `items`; the others are removed.
  - `to` = the current kind: only `marker` / `checkboxes` are applied.
  - `marker` / `checkboxes` in the body set the converted list's format (unchanged when omitted).
- POST, PATCH, DELETE and convert share the list write budget (`RATE_LIMIT_LIST_WRITES_PER_MIN`).

### Calendar

Events and dated to-dos the couple shares. The server stores wall-clock fields only and never works out
occurrences or reminder times: each phone expands repeats and schedules its own reminders. Occasions (the
anniversary, birthdays, the next visit, milestones) are not stored here; the app derives them.

```ts
CalendarEntry {
  id, coupleId, createdBy, updatedBy,
  kind: string,                 // 'event' | 'todo' (plain string in responses: tolerate unknown values)
  title: string,                // 1–120
  notes: string|null,           // ≤ 2000
  emoji: string|null,           // 1–2 emoji
  date: string,                 // YYYY-MM-DD: the first (or only) occurrence; a to-do's due date
  endDate: string|null,         // YYYY-MM-DD, all-day only: date ≤ endDate ≤ date + 366 days
  time: string|null,            // HH:mm; null = all day
  durationMinutes: number|null, // 1–10080, timed only
  timeZone: string|null,        // IANA zone the time is anchored to (timed only); null = each phone's own clock
  repeat: string,               // 'none' | 'weekly' | 'monthly' | 'yearly' (plain string in responses)
  repeatUntil: string|null,     // YYYY-MM-DD, inclusive, ≥ date
  reminders: number[],          // minutes before, ascending; all-day: before each phone's own all-day reminder time on the date
  remindUserIds: string[],      // members whose phones remind (a to-do's assignees); default both
  pinned: boolean,
  done: boolean, doneAt: string|null, doneBy: string|null,   // to-dos only
  source: { kind: 'message'|'list'|'list_item', messageId?, listId?, itemId? } | null,  // ids only, never copied text
  clientKey: string|null,
  version: number, createdAt, updatedAt }
```

| Method | Path | Body | Success |
|---|---|---|---|
| GET | `/calendar` | | `200 { items: CalendarEntry[] }`: all entries, by `date`, then `time` (all-day first) |
| POST | `/calendar` | `{ kind, title, date, notes?, emoji?, endDate?, time?, durationMinutes?, timeZone?, repeat? = 'none', repeatUntil?, reminders? = [], remindUserIds? = both, pinned? = false, source?, clientKey?, notifyPartner? = false }` | `201 { entry }` (`version: 1`); a repeated `clientKey` answers `200 { entry }` with the first entry and emits nothing |
| PATCH | `/calendar/:id` | any of the POST fields except `clientKey` (nullable ones accept `null` to clear), plus `done?`, `expectedVersion?`, `notifyPartner?` | `200 { entry }` (`version` + 1) |
| DELETE | `/calendar/:id` | | `204` (hard delete) |

Rules (400 `validation_failed`, with `details: [{ path, message }]`), checked on the entry **as it will be
stored**, so a PATCH that clears `time` must also clear `timeZone` and `durationMinutes`:
- `timeZone` and `durationMinutes` need a `time`; `endDate` is for all-day entries only, from `date` to
  `date` + 366 days; `repeatUntil` ≥ `date`; a to-do cannot repeat (`repeat` must be `'none'`).
- `date`, `endDate`, `repeatUntil` must be real days (`2026-02-30` is refused). `timeZone` is an IANA id
  (fixed offsets like `+05:30` are refused); its letter case is fixed as for profiles.
- `reminders`: at most 4, unique, each 0–40320 (4 weeks); stored ascending. `remindUserIds`: members of the
  couple, unique. Blank `notes` are stored as `null`.
- `done` is for to-dos only (`400 'done is only for to-dos'` on an event). `doneBy`/`doneAt` are
  server-owned: set (caller, now) when a to-do is ticked, kept while it stays ticked (a second `done: true`
  keeps the first), cleared when unticked or when the entry becomes an event.
- `source` (strict, by `kind`): `{ kind: 'message', messageId }` must be a message of the couple that is not
  deleted (`404`) and not a view-once photo (`400`); `{ kind: 'list', listId }` and
  `{ kind: 'list_item', listId, itemId }` need a list of the couple that is not deleted (`404`); the item id is
  not looked up. A source that is deleted later stays linked (the app hides or greys the chip).

Concurrency: with `expectedVersion` ≠ current version ⇒
`409 { error: { code: 'version_conflict', details: { entry: CalendarEntry /* current */ } } }`. Without it,
the PATCH applies to the latest state.

| Status | `error.code` | When |
|---|---|---|
| 400 | `validation_failed` | body, rules above, malformed id |
| 404 | `not_found` | entry, source message or source list not in the couple (or deleted) |
| 409 | `version_conflict` | stale `expectedVersion` (`details.entry`) |
| 409 | `conflict` | more than 1000 entries per couple (POST) |
| 409 | `not_paired` | no active couple |
| 429 | `rate_limited` | more than 120 calendar writes per user per minute (`RATE_LIMIT_CALENDAR_WRITES_PER_MIN`; POST, PATCH and DELETE share it) |

Events (both members):

| Event type | `payload` |
|---|---|
| `calendar.created` | `{ entry: CalendarEntry, notify: boolean }` |
| `calendar.updated` | `{ entry: CalendarEntry, notify: boolean }` (also for ticking a to-do) |
| `calendar.deleted` | `{ entryId }` |

`notify` is `true` only when the request carried `notifyPartner: true`. Pushes stay normal (silent) even
then: the partner's app shows its own "<name> added … to your calendar" notice when it applies an event with
`notify: true` from the partner.

`GET /me/export` adds `calendar: CalendarEntry[]` (by date, bounded to 1000, counted in `truncated`).
Unlink and account deletion delete the couple's entries.

### Wake together

One shared alarm rule; each phone rings its own copy and reports back. Every ring is `time` on one of `days`
in the anchor `timeZone` (the creator's), or the single instant `onceAt` when `days` is empty, so both
phones ring at the same moment wherever they are.

```ts
Alarm {
  id, coupleId, createdBy, updatedBy,
  mode: string,               // 'together' (plain string in responses): do not schedule an unknown mode
  label: string|null,         // ≤ 40
  time: string,               // HH:mm in timeZone
  timeZone: string,           // IANA anchor zone
  days: number[],             // ISO weekdays 1 (Mon) … 7 (Sun), ascending; [] = once
  onceAt: string|null,        // the single ring of a one-off alarm (set exactly when days is [])
  enabled: boolean,
  snoozeMinutes: number,      // 1–30
  version: number,            // +1 on every alarm.created / alarm.updated; wake and silent reports keep it
  createdAt, updatedAt,
  members: AlarmMember[] }    // both members

AlarmMember {
  userId,
  ring: boolean,              // false: "not on my phone"
  skips: string[],            // rings this person skips (≤ 7, recent and future)
  ready: boolean|null,        // null: this phone never reported for this alarm (e.g. an older app)
  reason: string|null,        // why not ready, a snake_case code such as 'exact_alarms_off'
  armedFor: string|null,      // the next ring this phone has set
  reportedAt: string|null,
  lastWake: { occurrence, outcome: string /* 'up'|'missed' */, at, snoozes } | null }
```

| Method | Path | Body | Success |
|---|---|---|---|
| GET | `/alarms` | | `200 { items: Alarm[] }`, oldest first |
| POST | `/alarms` | `{ label?, time, timeZone, days, onceAt?, ringOnMyPhone? = true, snoozeMinutes? = 10, mode? = 'together' }` | `201 { alarm }`; the partner starts with `ring: true`, both with `ready: null` |
| PATCH | `/alarms/:id` | `{ expectedVersion (required), label?, time?, timeZone?, days?, onceAt?, enabled?, snoozeMinutes? }` | `200 { alarm }`; stale version ⇒ `409 version_conflict` with `details.alarm` |
| DELETE | `/alarms/:id` | | `204` |
| PUT | `/alarms/:id/me` | `{ ring?, skip?: { occurrence, skip: boolean } }` (at least one) | `200 { alarm }`; changes only the caller's member entry |
| POST | `/alarms/report` | `{ items: [{ alarmId, ready, reason?: string\|null, armedFor?: string\|null }] }` (≤ 10, unique ids) | `204` |
| POST | `/alarms/:id/wake` | `{ occurrence, outcome: 'up'\|'missed', at, snoozes: 0–20 }` | `200 { alarm }` |

Timestamps in bodies (`onceAt`, `occurrence`, `armedFor`, `at`) must carry `Z` or an offset
(`2026-11-02T01:00:00Z`, `2026-11-02T06:30+05:30`).

Rules (400 `validation_failed`):
- `days`: unique, 1–7. With `days: []`, `onceAt` is required, must be at `time` in `timeZone`, and must lie
  between a minute ago and 366 days ahead (that window is checked only when `onceAt` is sent, so a past
  one-off alarm can still be renamed). With days, `onceAt` must be `null` (send `onceAt: null` when switching
  a one-off alarm to repeating). The rules are checked on the alarm as it will be stored.
- `timeZone`: IANA only. `label`: ≤ 40, blank ⇒ `null`. `snoozeMinutes`: 1–30. `mode`: `'together'`.
- `PUT …/me` `skip.occurrence`: from a day ago to 366 days ahead; at most 7 skips per person (skips older than
  a day are dropped when the entry is written). A request that changes nothing answers 200 without a version
  or an event.
- `report` `reason`: a snake_case code of ≤ 32 characters. Unknown or deleted alarm ids, and other couples'
  alarms, are skipped silently.
- `wake` `occurrence`: within the last 7 days (up to a day ahead).

Behaviour:
- `PUT /alarms/:id/me` is the only way to change `ring` or `skips`, and only the caller's own. It emits
  `alarm.updated` with `change: 'member'`.
- `report`: the phone sends it after arming. Only a change of `ready` or `reason` emits `alarm.updated`
  (`change: 'member'`); `armedFor` and `reportedAt` alone are stored silently (no version, no event).
- `wake` is idempotent per member and occurrence: a repeat, or a report about an earlier ring than the one
  stored, changes nothing and emits nothing. It never silences the partner's phone. The version stays.
- `notify` on `alarm.created` / `alarm.updated` is `true` only when ALL hold for the recipient (the member who
  is not the actor): their `ring` is true, the alarm is `enabled`, the change is a create or a PATCH (never a
  member change), and their `ready !== null` (their phone reported for this alarm, so it runs a build that
  shows the notice). Only then is the push high. A new alarm's partner entry starts with `ready: null`, so
  the create of a brand-new alarm normally pushes as a silent sync; the partner's app shows its "set Wake
  together" notice when it applies the event.

| Status | `error.code` | When |
|---|---|---|
| 400 | `validation_failed` | body, rules above, malformed id |
| 404 | `not_found` | alarm not in the couple (PATCH, DELETE, PUT me, wake) |
| 409 | `version_conflict` | stale `expectedVersion` (`details.alarm`) |
| 409 | `conflict` | more than 10 alarms per couple (POST) |
| 409 | `not_paired` | no active couple |
| 429 | `rate_limited` | more than 60 alarm writes per user per minute (`RATE_LIMIT_ALARM_WRITES_PER_MIN`; every POST/PATCH/PUT/DELETE above, reports included) |

Events (both members):

| Event type | `payload` | Push |
|---|---|---|
| `alarm.created` | `{ alarm, notify }` | high when `notify`, else normal |
| `alarm.updated` | `{ alarm, change: 'edited'\|'enabled'\|'member', notify }` (`'enabled'`: a PATCH that changed only `enabled`) | high when `notify`, else normal |
| `alarm.deleted` | `{ alarmId }` | normal |
| `alarm.woke` | `{ alarmId, userId, occurrence, outcome, at, snoozes }` | normal |

`GET /me/export` adds `alarms: Alarm[]`. Unlink and account deletion delete the couple's alarms.

### Devices and outbox hardening

- `PUT /devices` accepts an optional `sdkInt` (integer 21–99, the phone's `Build.VERSION.SDK_INT`). It is
  stored only when sent (a later PUT without it keeps the value) and returned as `sdkInt: number|null` in
  `GET /devices/health` for my phones and my partner's. The body stays strict otherwise.
- The cleanup worker deletes a device row of an old install (a reinstall or cleared data registers a new
  `deviceId`) only when all hold: the row was created more than 3 days ago; pushes were sent to it and none
  was acknowledged since (it never acked, or its last push is newer than its last ack); its last ack (or,
  without one, its creation) is more than 3 days old; and another row of the same user acked a push within
  the last 24 hours. A user's only row is never deleted. A wrongly pruned phone registers again when the app
  next opens.
- The outbox stores a random `push.leaseId` when it leases an event and records the result only while the
  event still carries that id, so a send that outlived its 30 s lease cannot overwrite the result of the
  worker that leased the event again.
