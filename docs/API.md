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
| DELETE | `/me` | `{ password }` | `204` — cascades: sessions, devices, couple ended, own media deleted |
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
| POST | `/couple/unlink` | `{ confirm: true }` | `204` — couple `ended`, both notified, shared media scheduled for deletion |

## Devices

| Method | Path | Body | Response |
|---|---|---|---|
| PUT | `/devices` | `{ deviceId, platform: 'android', token, appVersion, model? }` | `204` upsert; token never returned |
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
| GET | `/events` | `?since=<seq>&limit=200` | `{ items: Event[], latestSeq }` |
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

Push (FCM data message) payload mirrors a minimal event: `{ type, eventId, coupleId, seq }`.

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
server (`push_disabled`) or the recipient has no device tokens (`no_tokens`). Every other event keeps the
old rules. The SOS message is never logged.

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
