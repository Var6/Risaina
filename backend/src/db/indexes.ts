import type { Db, IndexDescription } from 'mongodb';
import { COLLECTION_NAMES } from './collections.js';

/**
 * Index definitions. Keep in sync with backend/docs/DATA_MODEL.md. Each index backs a real query
 * (noted in the name/comment); nothing speculative.
 */
export const INDEXES: Record<string, IndexDescription[]> = {
  [COLLECTION_NAMES.users]: [
    // login / register lookups; email is stored lowercased so uniqueness is case-insensitive
    { key: { email: 1 }, unique: true, name: 'email_unique' },
    { key: { activeCoupleId: 1 }, name: 'activeCoupleId', sparse: true },
    // POST /auth/google lookup; one account per Google identity (only documents that have one)
    {
      key: { googleSub: 1 },
      unique: true,
      name: 'googleSub_unique',
      partialFilterExpression: { googleSub: { $type: 'string' } },
    },
  ],
  [COLLECTION_NAMES.sessions]: [
    { key: { refreshHash: 1 }, unique: true, name: 'refreshHash_unique' },
    { key: { userId: 1, revokedAt: 1 }, name: 'userId_revokedAt' }, // revoke-all on logout/delete
    { key: { familyId: 1 }, name: 'familyId' }, // reuse detection revokes the family
    { key: { expiresAt: 1 }, name: 'expiresAt' }, // cleanup job
  ],
  [COLLECTION_NAMES.passwordResets]: [
    { key: { tokenHash: 1 }, unique: true, name: 'tokenHash_unique' },
    { key: { expiresAt: 1 }, name: 'expiresAt' }, // cleanup job
  ],
  [COLLECTION_NAMES.emailVerifications]: [
    { key: { userId: 1, createdAt: -1 }, name: 'userId_createdAt' }, // latest code, resend cooldown/hourly cap
    // TTL: removed one hour after expiry (the grace keeps the last hour of sends countable for the cap)
    { key: { expiresAt: 1 }, name: 'expiresAt_ttl', expireAfterSeconds: 3600 },
  ],
  [COLLECTION_NAMES.couples]: [
    { key: { memberIds: 1, status: 1 }, name: 'memberIds_status' }, // pending couple lookup
  ],
  [COLLECTION_NAMES.invitations]: [
    { key: { codeHash: 1 }, unique: true, name: 'codeHash_unique' },
    { key: { inviterId: 1, status: 1, createdAt: -1 }, name: 'inviter_status_createdAt' }, // GET /invitations/current
    { key: { coupleId: 1 }, name: 'coupleId' },
  ],
  [COLLECTION_NAMES.pings]: [
    { key: { coupleId: 1, createdAt: -1, _id: -1 }, name: 'coupleId_createdAt_desc' }, // GET /pings pagination
    { key: { senderId: 1, idempotencyKey: 1 }, unique: true, name: 'sender_idempotency_unique' },
    { key: { senderId: 1, coupleId: 1, createdAt: -1 }, name: 'sender_couple_createdAt' }, // cooldown + hourly cap
  ],
  [COLLECTION_NAMES.messages]: [
    { key: { coupleId: 1, createdAt: -1, _id: -1 }, name: 'coupleId_createdAt_desc' },
    { key: { senderId: 1, idempotencyKey: 1 }, unique: true, name: 'sender_idempotency_unique' },
    // view-once send: is this media already attached to another message?
    { key: { mediaId: 1 }, name: 'mediaId', partialFilterExpression: { mediaId: { $type: 'objectId' } } },
    // cleanup: unopened view-once photos older than N days
    {
      key: { openedAt: 1, createdAt: 1 },
      name: 'viewOnce_openedAt_createdAt',
      partialFilterExpression: { viewOnce: true },
    },
  ],
  [COLLECTION_NAMES.media]: [
    { key: { coupleId: 1, status: 1, createdAt: -1 }, name: 'coupleId_status_createdAt' },
    { key: { status: 1, createdAt: 1 }, name: 'status_createdAt' }, // abandoned-pending cleanup
    { key: { status: 1, objectDeleted: 1 }, name: 'status_objectDeleted' }, // deferred object deletion
    { key: { ownerId: 1 }, name: 'ownerId' }, // account deletion cascade
    // cleanup: opened view-once media whose download URL has expired
    { key: { openedAt: 1 }, name: 'viewOnce_openedAt', partialFilterExpression: { viewOnce: true } },
  ],
  [COLLECTION_NAMES.deviceTokens]: [
    { key: { userId: 1 }, name: 'userId' }, // outbox fan-out
    { key: { token: 1 }, unique: true, name: 'token_unique' },
    { key: { userId: 1, deviceId: 1 }, unique: true, name: 'userId_deviceId_unique' }, // PUT /devices upsert
  ],
  [COLLECTION_NAMES.memories]: [
    { key: { coupleId: 1, deletedAt: 1, createdAt: -1, _id: -1 }, name: 'coupleId_deletedAt_createdAt' },
    {
      key: { coupleId: 1, deletedAt: 1, pinned: 1, createdAt: -1 },
      name: 'coupleId_deletedAt_pinned_createdAt',
    },
    // message delete → its auto-created memory
    {
      key: { sourceMessageId: 1 },
      name: 'sourceMessageId',
      partialFilterExpression: { sourceMessageId: { $type: 'objectId' } },
    },
  ],
  [COLLECTION_NAMES.milestones]: [{ key: { coupleId: 1, date: 1 }, name: 'coupleId_date' }],
  [COLLECTION_NAMES.lists]: [
    // GET /lists: pinned first, then most recently updated, excluding soft-deleted
    {
      key: { coupleId: 1, deletedAt: 1, pinned: -1, updatedAt: -1 },
      name: 'coupleId_deletedAt_pinned_updatedAt',
    },
    // POST /lists retry key: one list per key per couple (only lists created with one)
    {
      key: { coupleId: 1, clientKey: 1 },
      unique: true,
      name: 'coupleId_clientKey_unique',
      partialFilterExpression: { clientKey: { $type: 'string' } },
    },
  ],
  [COLLECTION_NAMES.widgetBoards]: [
    // one board per author per couple; GET /widget-board reads both of the couple
    { key: { coupleId: 1, authorId: 1 }, unique: true, name: 'coupleId_authorId_unique' },
    { key: { authorId: 1 }, name: 'authorId' }, // account deletion cascade
  ],
  [COLLECTION_NAMES.sos]: [
    { key: { coupleId: 1, createdAt: -1 }, name: 'coupleId_createdAt_desc' }, // GET /sos
    { key: { senderId: 1, createdAt: -1 }, name: 'senderId_createdAt_desc' }, // 3 per 10 min limit
    // safety: SosRepushWorker scans recent undelivered alerts; retried POST /sos returns the first
    { key: { createdAt: -1 }, name: 'createdAt_desc' },
    {
      key: { senderId: 1, idempotencyKey: 1 },
      unique: true,
      name: 'sender_idempotency_unique',
      partialFilterExpression: { idempotencyKey: { $type: 'string' } },
    },
  ],
  [COLLECTION_NAMES.games]: [
    { key: { coupleId: 1, createdAt: -1 }, name: 'coupleId_createdAt_desc' }, // GET /games, export
    // at most one open game per couple (race-safe POST /games)
    {
      key: { coupleId: 1 },
      unique: true,
      name: 'coupleId_open_unique',
      partialFilterExpression: { open: true },
    },
  ],
  [COLLECTION_NAMES.liveLocations]: [
    // one live session per sharer per couple; stopped tombstones (live: false) do not count
    {
      key: { coupleId: 1, userId: 1 },
      unique: true,
      name: 'coupleId_userId_live_unique',
      partialFilterExpression: { live: true },
    },
    { key: { userId: 1 }, name: 'userId' }, // DELETE without a couple, sign-out, account deletion
    { key: { coupleId: 1 }, name: 'coupleId' }, // GET for both members, unlink purge
    // TTL backstop: timed expiry, 12 h without a check-in, and the 10-minute tombstone
    { key: { purgeAt: 1 }, name: 'purgeAt_ttl', expireAfterSeconds: 0 },
  ],
  [COLLECTION_NAMES.events]: [
    { key: { coupleId: 1, seq: 1 }, unique: true, name: 'coupleId_seq_unique' }, // sync replay, GET /events
    { key: { 'push.state': 1, 'push.leaseUntil': 1 }, name: 'push_state_leaseUntil' }, // outbox worker lease
    // cleanup: drop an unlinked couple's final event after the 7-day retention (tiny partial index)
    {
      key: { createdAt: 1 },
      name: 'unlinked_createdAt',
      partialFilterExpression: { type: 'couple.unlinked' },
    },
  ],
};

export async function ensureIndexes(db: Db): Promise<void> {
  for (const [name, specs] of Object.entries(INDEXES)) {
    if (specs.length === 0) continue;
    await db.collection(name).createIndexes(specs);
  }
}
