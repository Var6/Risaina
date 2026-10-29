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
| `purgedAt` | optional; set (with `status: ended`) when an active couple is unlinked — see "Unlink purge" |

Indexes: `{memberIds:1, status:1}` (pending-couple lookup, membership checks, recently-unlinked feed lookup).

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
| `objectDeleted` | storage object removed. Set immediately when deletion succeeds, otherwise the cleanup worker retries. Media of an unlinked (purged) couple is deleted outright once its object is gone |
| `readyAt`, `deletedAt` | |

Indexes: `{coupleId:1, status:1, createdAt:-1}`; `{status:1, createdAt:1}` (abandoned-pending cleanup); `{status:1, objectDeleted:1}` (deferred deletes); `{ownerId:1}` (account-deletion cascade).

### `deviceTokens`
`userId, deviceId (≤128), platform ('android'), token, appVersion, model|null, createdAt, updatedAt`.
Write-only from the client's perspective: no endpoint ever returns `token`.

Indexes: `{userId:1}` (outbox fan-out); `{token:1}` unique; `{userId:1, deviceId:1}` unique (upsert key).

Optional `sdkInt` (Android API level, stored only when the app sends it). The cleanup worker deletes a row
of an old install (created > 3 days ago, pushed to without an ack since, silent > 3 days) only while another
row of the same user acked within 24 h, so a user's only row is never deleted.

### `memories`
`coupleId, createdBy, mediaId (ready photo in the same couple), caption|null (≤500), takenAt|null, pinned, createdAt, updatedAt, deletedAt`.

Indexes: `{coupleId:1, deletedAt:1, createdAt:-1, _id:-1}`; `{coupleId:1, deletedAt:1, pinned:1, createdAt:-1}` (`?pinned=` filter).

### `milestones`
`coupleId, createdBy, title (≤120), date (YYYY-MM-DD), kind (anniversary|meeting|custom), visibility ('shared'), createdAt, updatedAt`. Hard-deleted; ≤ 500 per couple.

Indexes: `{coupleId:1, date:1}`.

### `lists`
`coupleId, createdBy, updatedBy, title (1–120), kind (note|checklist), body (≤10000, may be ''), items[≤200] { id (12-char random, or a client id ≤32 chars), text (1–300), done, doneBy: ObjectId|null, doneAt: date|null }, pinned, version (≥1, +1 per PATCH; compare-and-set filter), createdAt, updatedAt, deletedAt|null`. Soft-deleted; ≤ 200 non-deleted per couple.

Indexes: `{coupleId:1, deletedAt:1, pinned:-1, updatedAt:-1}` (`GET /lists`, export).

Later optional fields (never `required`; the live validator accepts them as extra fields):
`marker` ('none'|'number'|'bullet', plain string; missing ⇒ 'none'), `checkboxes` (missing ⇒ true),
`clientKey` (create retry key; index `{coupleId:1, clientKey:1}` unique partial `{clientKey: {$type:
'string'}}`), and on items `addedBy` / `addedAt` (missing ⇒ null). Items keep any other stored field
through a PATCH.

### `widgetBoards`
`coupleId, authorId, note (≤140)|null, emoji (1–2 graphemes)|null, mood (≤40)|null, photoMediaId|null, createdAt, updatedAt`.
One per author per couple (what the author shows on the partner's widgets). Deleted on unlink and
account deletion.

Indexes: `{coupleId:1, authorId:1}` unique; `{authorId:1}` (account deletion).

### `sos`
`coupleId, senderId, recipientId, message (≤140)|null, createdAt, acknowledgedAt|null`. Never
logged. Hard-deleted on unlink (see "Unlink purge").

Indexes: `{coupleId:1, createdAt:-1}` (`GET /sos`, export); `{senderId:1, createdAt:-1}` (3 per 10 min).

### `games`
`coupleId, kind ('rps'; more kinds below), createdBy, memberIds [2], status, open (bool: status not completed/cancelled),
round (≥1), moves [{userId, move, at}] (current round, ≤2), winnerId|null, loserId|null, challenge|null,
answer (≤500)|null, createdAt, updatedAt, completedAt|null, cancelledAt|null, cancelledBy|null`.

`status` ∈ `waiting | draw | revealed | challenged | chosen | awaiting_confirmation | completed | cancelled`.
`challenge = { truth (1–200), dare (1–200), by (winner), at, chosen: 'truth'|'dare'|null, chosenAt|null,
doneAt|null, confirmedAt|null, rejectNote (≤200)|null }` — the winner writes both texts, the loser picks
blind. Text visibility is enforced only in the DTO builders (`toGameDto` per viewer, `toGameEventView` for
events without any text), never by projection, so every read path goes through them.

The validator checks `kind`, `status`, `moves[].move` and `challenge.chosen` as plain strings (the Zod
schemas are the allow-list), so new kinds/statuses/moves never need collMod; inside `challenge` it requires
only `by` and `at` (earlier `{ kind, text }` dev documents stay updatable, e.g. cancellable). A separate collection (not a
`messages.type`): the live `messages` validator has a strict `type` enum that cannot be widened without
collMod. Hard-deleted on unlink. State changes are conditional `findOneAndUpdate`s on the expected status
(plus `updatedAt` for the steps after `challenged`), so concurrent requests cannot skip or repeat a
transition.

Indexes: `{coupleId:1, createdAt:-1}` (`GET /games`, export); `{coupleId:1}` unique partial `{open: true}`
(one open game per couple, race-safe).

**More games (games stream): tic-tac-toe, trivia duel, This or That.** `kind` ∈ `rps | tictactoe | trivia |
this_or_that`. Three new OPTIONAL fields, never in `required` (the live validator can't change, and it sets
no `additionalProperties`, so it accepts them); every required field above is still written for every kind
(`moves: []`, `challenge: null`, ...). `moves` can't hold tic-tac-toe moves: the live validator caps it at 2.

| Field | Shape | Missing reads as |
|---|---|---|
| `prize` | `'truth_or_dare' \| 'none'` (this_or_that always `'none'`) | `'truth_or_dare'` |
| `ttt` | `{ board: ('x'\|'o'\|null)[9], xId, turnId\|null, moveCount 0..9, lastCell\|null, line: number[3]\|null }` | `null` (not tic-tac-toe) |
| `quiz` | `{ source 'opentdb'\|'builtin'\|'curated', category {id,name}\|null, requestedCategory?: number\|null (null = any), difficulty\|null, deck\|null, items: QuizItem[1..10], players: QuizPlayer[2], matches\|null }` | `null` (not a quiz) |

`QuizItem = { id ('otdb_'+sha256(text)[0..16] | 'tb_..' | curated 'ev-07'), text|null, choices (4 or 2, already
shuffled), correct (index)|null, category|null, difficulty|null }`. `QuizPlayer = { userId, picks: number[]
(in order), score, finishedAt|null }`, in `memberIds` order. Trivia answers are one conditional
`findOneAndUpdate` on `quiz.players.$elemMatch { userId, picks: { $size: item } }` (so answers are sequential,
and a retry can't answer the next question); the game resolves with a second conditional write on
`quiz.players: { $not: { $elemMatch: { finishedAt: null } } }`, so only the second finisher resolves it.
Tic-tac-toe moves are conditional on `status`, `round`, `ttt.moveCount` and `ttt.turnId`.

Example (tic-tac-toe, X to move):
`{ kind: 'tictactoe', status: 'waiting', open: true, round: 1, prize: 'truth_or_dare', moves: [], challenge: null,
ttt: { board: [null, null, null, null, 'x', null, 'o', null, null], xId: B, turnId: B, moveCount: 2, lastCell: 6,
line: null }, quiz: null, ... }`.

No new index: the "avoid recent questions/prompts" reads (`{coupleId, kind}`, newest 30 trivia or 5 This or
That games, projecting `quiz.items.id`) use `{coupleId:1, createdAt:-1}`. `test/fixtures/games-validator.live.json`
is a frozen copy of the deployed validator; a test inserts every document shape the new games write into a
collection created with it.

### Later optional fields (never `required`; a readWrite-only DB user cannot collMod)

| Collection | Field | Missing reads as |
|---|---|---|
| `users` | `city: { name 1–80, latitude, longitude (2 decimals) } \| null`, `shareWeather: bool` | `null`, `true` |
| `messages` | `viewOnce: bool`, `openedAt: date\|null`, `viewOnceExpiredAt: date\|null` | `false`, `null`, `null` |
| `media` | `viewOnce: bool`, `openedAt: date\|null` (copied from the message for cleanup) | `false`, `null` |
| `memories` | `sourceMessageId: ObjectId\|null` (auto-memory from a chat photo) | `null` |
| `couples` | `chatWallpaper: { style DEFAULT\|PAPER\|HEARTS\|NIGHT\|GLOW\|PHOTO, photoMediaId\|null, setBy, setAt } \| null` (exposed as `sharedPreferences.chatWallpaper`) | `null` |
| `couples` | `purgedAt: date\|null` (unlink purge marker) | `null` (not purged: ended before purging existed, or never active) |
| `sessions` | `replacedBy: ObjectId\|null` (the session this refresh token was rotated into; refresh grace window, API.md "Refresh grace window") | `null` (no grace) |
| `deviceTokens` | `lastPushSentAt: date\|null`, `lastPushSentPriority: 'high'\|'normal'\|null` (outbox: FCM accepted a push for this token) | `null` |
| `deviceTokens` | `lastPushAckAt: date\|null`, `lastPushPriority: 'high'\|'normal'\|null`, `lastPushDowngraded: bool`, `healthFlags: string[] (≤16, ≤40 chars)` (the phone's push ack on `GET /events`) | `null`, `null`, `false`, `[]` |

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
| `push.leaseId` | optional (later): random id of the current lease; the result is recorded only while it still matches, so a send that outlived its lease cannot overwrite a newer result |
| `push.lastError`, `push.skipReason`, `push.sentAt` | `skipReason` ∈ `push_disabled`, `no_recipient`, `recipient_missing`, `not_pushed` (the type's push policy is "none"), `no_tokens`, `tokens_unregistered`, `couple_missing`; older events may also carry `notifications_disabled` or `quiet_hours`, which are no longer written (a silent normal push is sent instead) |

Indexes: `{coupleId:1, seq:1}` unique (WS replay, `GET /events?since`); `{'push.state':1, 'push.leaseUntil':1}` (outbox lease query); `{createdAt:1}` partial `{type: 'couple.unlinked'}` (`unlinked_createdAt`: 7-day retention of an unlinked couple's final event).

## Unlink purge

When an active couple ends (`POST /couple/unlink`, or `DELETE /me` of a member) `CoupleService.endCouple`:

1. atomically sets `status: 'ended', endedAt, endedBy, purgedAt` (only from `active`, so it runs once);
2. clears both members' `activeCoupleId`, emits `couple.unlinked` (live to sockets, outbox push to the
   partner) and revokes the couple's sockets;
3. **database phase** (awaited, `couples/purge.ts` `purgeData`): marks all the couple's media `deleted`;
   clears a member's `avatarMediaId` when it references this couple's media; `deleteMany({coupleId})` on
   `messages, pings, memories, milestones, lists, games, widgetBoards, sos, liveLocations, calendarEntries,
   alarms, invitations`; deletes the
   couple's `events` except `type: 'couple.unlinked'`; resets `relationshipStartDate, nextMeetingDate,
   nextMeetingLabel` to null, `sharedPreferences` to `{}` and unsets `chatWallpaper`;
4. **storage phase** (background, `purgeMedia`): deletes each storage object then its media document; a
   failed delete is logged and leaves the document (`status: deleted, objectDeleted: false`).

Kept: the couple document (`status, memberIds, createdBy, eventSeq, createdAt, updatedAt, activatedAt,
endedAt, endedBy, purgedAt`) and its single `couple.unlinked` event. Every step is filter based, so the
purge is idempotent. The cleanup worker re-runs the database phase for couples with `purgedAt` in the last
7 days (catching writes that raced the unlink), deletes objects of `deleted` media (and, for a purged
couple, the document too), and deletes `couple.unlinked` events older than 7 days. Until then an
ex-member who has not paired again can still read that one event through `GET /events` and the WebSocket
`sync` (see API.md). Couples ended before purging existed (no `purgedAt`) are not retro-purged.

## Write ordering and consistency

No multi-document transactions are used (a standalone MongoDB is supported). Every mutation
writes the domain document first, then allocates `seq` and inserts the event. If the process
dies between the two writes the domain change exists without an event; clients still converge on
the next full fetch, and this window is accepted for a two-user app (see COUNCIL_DECISIONS D-04).
Idempotency keys and conditional `findOneAndUpdate` filters make every multi-step flow
(invitation accept, refresh rotation, ping delivery) safe under retries and races.

## Safety stream: `liveLocations` and later SOS fields

### `liveLocations` (new collection, created with its validator at boot)
One document per live sharing session holding **only the latest fix**: never a history. `_id` is the
session id (made by the sharer's phone).

`coupleId, userId (sharer), viewerId (partner), reason ('manual'|'panic', plain string in the validator;
Zod is the allow-list), sosId|null, live (bool), startedAt, expiresAt|null (null = until I turn it off),
purgeAt, fix|null, lastSeenAt, stoppedAt|null, updatedAt`; every field is required and always written.
`fix = { lat, lng (5 decimals), accuracyM (int, ≤ 50 000), at, batteryPct|null }`.

- `purgeAt` (TTL, `expireAfterSeconds: 0`) = the timed expiry, or 12 h after the last check-in, whichever
  is first, so a forgotten "until off" session on a dead phone disappears. Reads also check `expiresAt`
  and `purgeAt` because the TTL monitor can lag a minute or more.
- Stopping turns the document into a **tombstone**: `live: false`, `fix: null`, `stoppedAt`, `purgeAt` =
  now + 10 min. The coordinates are gone at once; the tombstone only refuses a late start with the same
  id. Sign-out, account deletion and unlink delete the documents outright.

Indexes: `{coupleId:1, userId:1}` unique partial `{live: true}` (one live session per sharer;
tombstones do not count); `{userId:1}` (stop without a couple, sign-out, deletion); `{coupleId:1}` (GET,
unlink purge); `{purgeAt:1}` TTL.

### Later optional SOS fields (never `required`; the live `sos` validator has no additionalProperties)

| Field | Missing reads as | Meaning |
|---|---|---|
| `panic: bool` | `false` | stored only when true |
| `deliveredAt: date\|null` | `null` | the recipient's phone started ringing |
| `delivery: object\|null` | `null` | device state reported with that receipt (no content) |
| `resolvedAt: date\|null` | `null` | the sender said "I'm safe now" |
| `idempotencyKey: string` | absent | client retry key |
| `raisedEventId: ObjectId`, `raisedSeq: int` | absent (no re-push) | the `sos.raised` event, written right after emit |
| `pushCount: int`, `lastPushAt: date` | `0`, never | re-push bookkeeping |
| `raiseLeaseUntil: date` | absent | while set (and until `raisedSeq` is written), one request is emitting `sos.raised`; a retry with the same `idempotencyKey` after it lapses emits it instead |

Extra `sos` indexes: `{createdAt:-1}` (re-push scan); `{senderId:1, idempotencyKey:1}` unique partial
`{idempotencyKey: {$type: 'string'}}`. `{deliveredAt: null}` and `{resolvedAt: null}` filters also match
documents written before the fields existed.

## Calendar and wake together (2026-10-29)

Both are new collections, created with their validators at boot. The validators use patterns and plain
strings, never enums, and set no `additionalProperties`: the production DB user can never change them, so
a later kind, mode or field must already pass. Zod in the API is the allow-list. Both are hard-deleted,
purged with the couple and exported.

### `calendarEntries`
`coupleId, createdBy, updatedBy, kind ('event'|'todo'), title (1–120), notes (≤2000)|null, emoji|null,
date (YYYY-MM-DD), endDate|null (all-day only), time (HH:mm)|null, durationMinutes|null, timeZone (IANA
anchor)|null, repeat ('none'|'weekly'|'monthly'|'yearly'), repeatUntil|null, reminders [minutes before,
≤4, ascending], remindUserIds [member ids], pinned, doneAt|null, doneBy|null (to-dos), source { kind
('message'|'list'|'list_item'), messageId?, listId?, itemId? }|null, clientKey|null, version (≥1, +1 per
change; compare-and-set filter), createdAt, updatedAt`. ≤ 1000 per couple. Wall-clock fields only: the
server never computes occurrences or reminder times.

Indexes: `{coupleId:1, date:1}` (`GET /calendar`, count, export, purge); `{coupleId:1, clientKey:1}` unique
partial `{clientKey: {$type: 'string'}}` (create retries); `{coupleId:1, 'source.messageId':1}` partial
`{'source.messageId': {$type: 'objectId'}}`.

### `alarms`
`coupleId, createdBy, updatedBy, mode ('together'), label (≤40)|null, time (HH:mm), timeZone (IANA anchor),
days [ISO weekdays 1–7, ascending; [] = once], onceAt|null (set exactly when days is []), enabled,
snoozeMinutes (1–30), version (+1 per alarm.created/updated; wake reports and silent readiness reports keep
it), createdAt, updatedAt, members [2] { userId, ring, skips [dates, ≤7], ready (bool|null: null = never
reported), reason|null, armedFor|null, reportedAt|null, lastWake { occurrence, outcome ('up'|'missed'),
at, snoozes }|null }`. ≤ 10 per couple. Member fields are written one by one (`members.$[me].<field>`) so
a silent readiness report never overwrites a concurrent change with a stale copy.

Indexes: `{coupleId:1, createdAt:1}` (`GET /alarms`, count, export, purge).
