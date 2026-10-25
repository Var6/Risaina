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
  ],
  [COLLECTION_NAMES.media]: [
    { key: { coupleId: 1, status: 1, createdAt: -1 }, name: 'coupleId_status_createdAt' },
    { key: { status: 1, createdAt: 1 }, name: 'status_createdAt' }, // abandoned-pending cleanup
    { key: { status: 1, objectDeleted: 1 }, name: 'status_objectDeleted' }, // deferred object deletion
    { key: { ownerId: 1 }, name: 'ownerId' }, // account deletion cascade
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
  ],
  [COLLECTION_NAMES.milestones]: [{ key: { coupleId: 1, date: 1 }, name: 'coupleId_date' }],
  [COLLECTION_NAMES.lists]: [
    // GET /lists: pinned first, then most recently updated, excluding soft-deleted
    {
      key: { coupleId: 1, deletedAt: 1, pinned: -1, updatedAt: -1 },
      name: 'coupleId_deletedAt_pinned_updatedAt',
    },
  ],
  [COLLECTION_NAMES.events]: [
    { key: { coupleId: 1, seq: 1 }, unique: true, name: 'coupleId_seq_unique' }, // sync replay, GET /events
    { key: { 'push.state': 1, 'push.leaseUntil': 1 }, name: 'push_state_leaseUntil' }, // outbox worker lease
  ],
};

export async function ensureIndexes(db: Db): Promise<void> {
  for (const [name, specs] of Object.entries(INDEXES)) {
    if (specs.length === 0) continue;
    await db.collection(name).createIndexes(specs);
  }
}
