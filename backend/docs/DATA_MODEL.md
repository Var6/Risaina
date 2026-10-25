# Risaina — Data model (MongoDB)

Source of truth: `src/db/types.ts` (document shapes), `src/db/indexes.ts` (indexes),
`src/db/validators.ts` (`$jsonSchema` validators). All three are applied at boot by
`prepareDatabase()` (validators first — this also creates the collections — then indexes).

Conventions: `_id` is an `ObjectId` and is rendered as a 24-hex string in the API. Timestamps are
BSON dates (ISO-8601 UTC on the wire). Nullable fields are stored explicitly as `null` so the
validators can require them. Nothing here is ever accepted from clients as a storage key or
couple id: couple scope always comes from the authenticated user.

## Collections

### `users`
| Field | Type | Notes |
|---|---|---|
| `email` | string | **lowercased**; unique. Case-insensitivity is achieved by normalising before write/lookup |
| `displayName` | string 1–60 | |
| `passwordHash` | string \| null | argon2id (m=19456 KiB, t=2, p=1). `null` for Google-only accounts, for an unverified account claimed through Google sign-in, and after account deletion. Password login on `null` fails like a wrong password |
| `googleSub` | string (optional) | Google `sub` claim; **absent** until the account signs in with Google; removed (`$unset`) on account deletion |
| `emailVerifiedAt` | date \| null (optional) | set by `POST /auth/email/verify` or Google sign-in; absent on rows created before this field existed (treated as unverified) |
| `avatarMediaId` | ObjectId \| null | must reference a `ready` `avatar` media owned by the user |
| `activeCoupleId` | ObjectId \| null | set only while the couple is `active`; the `requireCouple` guard reads this |
| `preferences` | object | see `Preferences` in API.md; defaults in `DEFAULT_PREFERENCES` |
| `birthday` | string \| null (optional) | `YYYY-MM-DD` or `--MM-DD` (year private). Absent on older rows ⇒ read as `null`; cleared on deletion |
| `timeZone` | string \| null (optional) | IANA id. Absent ⇒ `null`; cleared on deletion |
| `shareLocalTime` | bool (optional) | absent ⇒ `true`. `false` hides `timeZone` from the partner DTO |
| `createdAt` / `updatedAt` / `deletedAt` | date / date / date\|null | deletion anonymises the row rather than removing it (referential integrity for events) |

Indexes: `{email:1}` unique (`email_unique`); `{activeCoupleId:1}` sparse; `{googleSub:1}` unique with
`partialFilterExpression: {googleSub: {$type: 'string'}}` (`googleSub_unique`).

### `sessions`
One document per refresh token. Rotation creates a new document in the same `familyId`.
| Field | Notes |
|---|---|
| `_id` | equals the `sid` claim in access tokens issued for this session |
| `userId`, `familyId` | family = one login and all its rotations |
| `refreshHash` | sha256(refresh token); unique. The plaintext token is never stored |
| `expiresAt` | 30-day sliding expiry (reset on each rotation) |
| `rotatedAt` | set when this refresh token was exchanged. Presenting it again ⇒ reuse ⇒ whole family revoked |
| `revokedAt`, `revokedReason` | `logout` \| `refresh_reuse` \| `password_reset` \| `account_deleted` \| `google_claimed_unverified` |
| `deviceId`, `userAgent`, `lastUsedAt` | metadata |

Indexes: `{refreshHash:1}` unique; `{userId:1, revokedAt:1}` (revoke-all); `{familyId:1}` (reuse detection); `{expiresAt:1}` (cleanup).

### `passwordResets`
`userId`, `tokenHash` (sha256, unique), `expiresAt` (30 min default), `usedAt`, `createdAt`.
Consumed atomically with `findOneAndUpdate` so a token can be used once.

Indexes: `{tokenHash:1}` unique; `{expiresAt:1}`.

### `emailVerifications`
`userId`, `codeHash` (HMAC-SHA256 of `userId:code` keyed from `JWT_SECRET`; the 6-digit code is never
stored), `expiresAt` (30 min default), `attempts` (wrong guesses; 5 kills the code), `createdAt`.
Only the newest document per user is live; older ones stay until TTL so the hourly resend cap can
count them. All of a user's documents are deleted on successful verification, Google linking and
account deletion.

Indexes: `{userId:1, createdAt:-1}`; `{expiresAt:1}` TTL with `expireAfterSeconds: 3600`
(`expiresAt_ttl`).

### `couples`
| Field | Notes |
|---|---|
| `status` | `pending` (inviter only) → `active` (exactly two members) → `ended` |
| `memberIds` | ObjectId[]; validator enforces 1–2 unique items; activation uses `{memberIds: {$size: 1}}` + `$push` so two members is guaranteed atomically |
| `createdBy` | inviter |
| `eventSeq` | **per-couple monotonic sequence**; `events.seq` is allocated with `findOneAndUpdate({$inc: {eventSeq: 1}})` |
| `relationshipStartDate` | `YYYY-MM-DD` \| null |
| `nextMeetingDate`, `nextMeetingLabel` | date \| null, string \| null |
| `sharedPreferences` | small flat map (≤ 20 keys of string/number/boolean) |
| `activatedAt`, `endedAt`, `endedBy` | lifecycle |

Indexes: `{memberIds:1, status:1}` (pending-couple lookup, membership checks).

### `invitations`
| Field | Notes |
|---|---|
| `coupleId`, `inviterId` | |
| `codeHash` | sha256 of the 10-character Crockford base32 code; unique. Plaintext shown once at creation |
| `status` | `open` \| `accepted` \| `revoked` (expiry is evaluated from `expiresAt`, 24 h default) |
| `acceptedBy`, `acceptedAt`, `revokedAt` | |

Single use is enforced by `findOneAndUpdate({_id, status:'open', expiresAt > now} → accepted)`.
Accept attempts are limited in-process to 5 / 15 min per `userId:ip`.

Indexes: `{codeHash:1}` unique; `{inviterId:1, status:1, createdAt:-1}` (`GET /invitations/current`); `{coupleId:1}`.

### `pings`
`coupleId, senderId, recipientId, type, emoji, message|null (≤140), status (accepted|delivered|seen), createdAt, deliveredAt, seenAt, idempotencyKey`.

Indexes: `{coupleId:1, createdAt:-1, _id:-1}` (history pagination); `{senderId:1, idempotencyKey:1}` unique (replay returns the original); `{senderId:1, coupleId:1, createdAt:-1}` (cooldown + hourly cap).

### `messages`
`coupleId, senderId, recipientId, type (text|emoji|photo|voice), text|null (≤2000), mediaId|null, createdAt, editedAt, deletedAt, readAt, idempotencyKey`.
Soft delete sets `deletedAt` and clears `text`; the row stays as a tombstone.

Indexes: `{coupleId:1, createdAt:-1, _id:-1}`; `{senderId:1, idempotencyKey:1}` unique.

### `media`
| Field | Notes |
|---|---|
| `coupleId`, `ownerId`, `category` | `photo` \| `voice` \| `avatar` \| `wallpaper` |
| `mimeType`, `sizeBytes`, `durationMs`, `width`, `height` | declared by client; `sizeBytes` is overwritten with the verified size on complete |
| `status` | `pending` → `ready` (after HEAD + magic-byte verification) → `deleted` |
| `storageKey` | server-generated `couples/{coupleId}/{category}/{mediaId}.{ext}`; validator enforces the pattern; never returned to clients |
| `objectDeleted` | storage object removed. Set immediately when deletion succeeds, otherwise the cleanup worker retries |
| `readyAt`, `deletedAt` | |

Indexes: `{coupleId:1, status:1, createdAt:-1}`; `{status:1, createdAt:1}` (abandoned-pending cleanup); `{status:1, objectDeleted:1}` (deferred deletes); `{ownerId:1}` (account-deletion cascade).

### `deviceTokens`
`userId, deviceId (≤128), platform ('android'), token, appVersion, model|null, createdAt, updatedAt`.
Write-only from the client's perspective: no endpoint ever returns `token`.

Indexes: `{userId:1}` (outbox fan-out); `{token:1}` unique; `{userId:1, deviceId:1}` unique (upsert key).

### `memories`
`coupleId, createdBy, mediaId (ready photo in the same couple), caption|null (≤500), takenAt|null, pinned, createdAt, updatedAt, deletedAt`.

Indexes: `{coupleId:1, deletedAt:1, createdAt:-1, _id:-1}`; `{coupleId:1, deletedAt:1, pinned:1, createdAt:-1}` (`?pinned=` filter).

### `milestones`
`coupleId, createdBy, title (≤120), date (YYYY-MM-DD), kind (anniversary|meeting|custom), visibility ('shared'), createdAt, updatedAt`. Hard-deleted; ≤ 500 per couple.

Indexes: `{coupleId:1, date:1}`.

### `lists`
`coupleId, createdBy, updatedBy, title (1–120), kind (note|checklist), body (≤10000, may be ''), items[≤200] { id (12-char random, or a client id ≤32 chars), text (1–300), done, doneBy: ObjectId|null, doneAt: date|null }, pinned, version (≥1, +1 per PATCH; compare-and-set filter), createdAt, updatedAt, deletedAt|null`. Soft-deleted; ≤ 200 non-deleted per couple.

Indexes: `{coupleId:1, deletedAt:1, pinned:-1, updatedAt:-1}` (`GET /lists`, export).

### `widgetBoards`
`coupleId, authorId, note (≤140)|null, emoji (1–2 graphemes)|null, mood (≤40)|null, photoMediaId|null, createdAt, updatedAt`.
One per author per couple (what the author shows on the partner's widgets). Deleted on unlink and
account deletion.

Indexes: `{coupleId:1, authorId:1}` unique; `{authorId:1}` (account deletion).

### `sos`
`coupleId, senderId, recipientId, message (≤140)|null, createdAt, acknowledgedAt|null`. Never
logged. Kept after unlink (like pings).

Indexes: `{coupleId:1, createdAt:-1}` (`GET /sos`, export); `{senderId:1, createdAt:-1}` (3 per 10 min).

### Later optional fields (never `required`; a readWrite-only DB user cannot collMod)

| Collection | Field | Missing reads as |
|---|---|---|
| `users` | `city: { name 1–80, latitude, longitude (2 decimals) } \| null`, `shareWeather: bool` | `null`, `true` |
| `messages` | `viewOnce: bool`, `openedAt: date\|null`, `viewOnceExpiredAt: date\|null` | `false`, `null`, `null` |
| `media` | `viewOnce: bool`, `openedAt: date\|null` (copied from the message for cleanup) | `false`, `null` |
| `memories` | `sourceMessageId: ObjectId\|null` (auto-memory from a chat photo) | `null` |

Extra indexes: `messages {mediaId:1}` partial (`mediaId` is an ObjectId), `messages {openedAt:1, createdAt:1}`
partial `{viewOnce: true}` (unopened purge), `media {openedAt:1}` partial `{viewOnce: true}` (opened purge),
`memories {sourceMessageId:1}` partial. Indexes only need `createIndex` (part of readWrite).

### `events` (durable log + push outbox)
| Field | Notes |
|---|---|
| `coupleId`, `seq` | unique pair; `seq` is dense and strictly increasing per couple |
| `type` | one of the event types in API.md. The validator checks a dotted-name **pattern**, not an enum, so new types need no `collMod`. Databases created by older versions keep their strict enum until a `dbAdmin` user restarts the server once; until then new types (`list.*`) fail validation and the publisher delivers them live over WebSocket only (logged at error level) |
| `actorId` | the user who caused the event |
| `payload` | exactly the shapes in API.md "Event payload shapes" |
| `createdAt` | |
| `push.state` | `pending` \| `sent` \| `skipped` \| `failed` |
| `push.attempts` | incremented per send attempt (max 5) |
| `push.leaseUntil` | worker lease; also used as "not before" for exponential backoff (2 s · 2ⁿ) |
| `push.lastError`, `push.skipReason`, `push.sentAt` | `skipReason` ∈ `push_disabled`, `no_recipient`, `recipient_missing`, `notifications_disabled`, `quiet_hours`, `no_tokens`, `tokens_unregistered`, `couple_missing` |

Indexes: `{coupleId:1, seq:1}` unique (WS replay, `GET /events?since`); `{'push.state':1, 'push.leaseUntil':1}` (outbox lease query).

## Write ordering and consistency

No multi-document transactions are used (a standalone MongoDB is supported). Every mutation
writes the domain document first, then allocates `seq` and inserts the event. If the process
dies between the two writes the domain change exists without an event; clients still converge on
the next full fetch, and this window is accepted for a two-user app (see COUNCIL_DECISIONS D-04).
Idempotency keys and conditional `findOneAndUpdate` filters make every multi-step flow
(invitation accept, refresh rotation, ping delivery) safe under retries and races.
