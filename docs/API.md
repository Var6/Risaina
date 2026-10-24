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
