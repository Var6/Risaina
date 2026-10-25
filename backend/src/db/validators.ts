import type { Db, Document } from 'mongodb';
import { COLLECTION_NAMES } from './collections.js';
import {
  EVENT_TYPES,
  LIST_KINDS,
  MEDIA_CATEGORIES,
  MESSAGE_TYPES,
  MILESTONE_KINDS,
  PING_TYPES,
} from './types.js';

/**
 * MongoDB `$jsonSchema` validators for the main collections. They are a safety net under the Zod
 * input validation and TypeScript types: a bug that writes a malformed document fails loudly
 * instead of silently corrupting data.
 */

const objectId = { bsonType: 'objectId' } as const;
const nullableObjectId = { bsonType: ['objectId', 'null'] } as const;
const date = { bsonType: 'date' } as const;
const nullableDate = { bsonType: ['date', 'null'] } as const;
const string = { bsonType: 'string' } as const;
const nullableString = { bsonType: ['string', 'null'] } as const;
const bool = { bsonType: 'bool' } as const;
const nullableInt = { bsonType: ['int', 'long', 'double', 'null'] } as const;

const hhmm = { bsonType: 'string', pattern: '^([01][0-9]|2[0-3]):[0-5][0-9]$' } as const;

const preferences = {
  bsonType: 'object',
  required: [
    'notificationsEnabled',
    'previewInNotifications',
    'quietHours',
    'allowPushInQuietHours',
    'pingCooldownSeconds',
    'widgetPrivacy',
    'reducedMotion',
  ],
  properties: {
    notificationsEnabled: bool,
    previewInNotifications: bool,
    quietHours: {
      bsonType: 'object',
      required: ['enabled', 'start', 'end', 'timeZone'],
      properties: { enabled: bool, start: hhmm, end: hhmm, timeZone: string },
    },
    allowPushInQuietHours: bool,
    pingCooldownSeconds: { bsonType: ['int', 'long', 'double'], minimum: 0 },
    widgetPrivacy: {
      bsonType: 'object',
      required: ['showPhoto', 'showNoteText', 'showEmoji'],
      properties: { showPhoto: bool, showNoteText: bool, showEmoji: bool },
    },
    reducedMotion: bool,
  },
};

export const VALIDATORS: Record<string, Document> = {
  [COLLECTION_NAMES.users]: {
    $jsonSchema: {
      bsonType: 'object',
      required: [
        'email',
        'displayName',
        'passwordHash',
        'avatarMediaId',
        'activeCoupleId',
        'preferences',
        'createdAt',
        'updatedAt',
        'deletedAt',
      ],
      properties: {
        email: { bsonType: 'string', pattern: '^[^A-Z]*$' }, // stored lowercased
        displayName: { bsonType: 'string', minLength: 1, maxLength: 60 },
        passwordHash: nullableString, // null: Google-only / claimed-unverified / deleted
        googleSub: { bsonType: 'string', minLength: 1, maxLength: 255 }, // optional
        emailVerifiedAt: nullableDate, // optional (legacy documents lack it)
        avatarMediaId: nullableObjectId,
        activeCoupleId: nullableObjectId,
        preferences,
        // optional profile fields (added after v1; never in `required`, older documents lack them)
        birthday: { bsonType: ['string', 'null'], pattern: '^(\\d{4}|-)-\\d{2}-\\d{2}$' },
        timeZone: { bsonType: ['string', 'null'], minLength: 1, maxLength: 64 },
        shareLocalTime: bool,
        createdAt: date,
        updatedAt: date,
        deletedAt: nullableDate,
      },
    },
  },
  [COLLECTION_NAMES.sessions]: {
    $jsonSchema: {
      bsonType: 'object',
      required: ['userId', 'familyId', 'refreshHash', 'expiresAt', 'createdAt'],
      properties: {
        userId: objectId,
        familyId: objectId,
        refreshHash: { bsonType: 'string', pattern: '^[a-f0-9]{64}$' },
        expiresAt: date,
        createdAt: date,
        lastUsedAt: nullableDate,
        rotatedAt: nullableDate,
        revokedAt: nullableDate,
        revokedReason: nullableString,
        deviceId: nullableString,
        userAgent: nullableString,
      },
    },
  },
  [COLLECTION_NAMES.passwordResets]: {
    $jsonSchema: {
      bsonType: 'object',
      required: ['userId', 'tokenHash', 'expiresAt', 'createdAt'],
      properties: {
        userId: objectId,
        tokenHash: string,
        expiresAt: date,
        usedAt: nullableDate,
        createdAt: date,
      },
    },
  },
  [COLLECTION_NAMES.emailVerifications]: {
    $jsonSchema: {
      bsonType: 'object',
      required: ['userId', 'codeHash', 'expiresAt', 'attempts', 'createdAt'],
      properties: {
        userId: objectId,
        codeHash: { bsonType: 'string', pattern: '^[a-f0-9]{64}$' },
        expiresAt: date,
        attempts: { bsonType: ['int', 'long', 'double'], minimum: 0 },
        createdAt: date,
      },
    },
  },
  [COLLECTION_NAMES.couples]: {
    $jsonSchema: {
      bsonType: 'object',
      required: ['status', 'memberIds', 'createdBy', 'eventSeq', 'createdAt', 'updatedAt'],
      properties: {
        status: { enum: ['pending', 'active', 'ended'] },
        memberIds: { bsonType: 'array', minItems: 1, maxItems: 2, items: objectId, uniqueItems: true },
        createdBy: objectId,
        eventSeq: { bsonType: ['int', 'long', 'double'], minimum: 0 },
        relationshipStartDate: { bsonType: ['string', 'null'], pattern: '^\\d{4}-\\d{2}-\\d{2}$' },
        nextMeetingDate: nullableDate,
        nextMeetingLabel: nullableString,
        sharedPreferences: { bsonType: 'object' },
        createdAt: date,
        updatedAt: date,
        activatedAt: nullableDate,
        endedAt: nullableDate,
        endedBy: nullableObjectId,
      },
    },
  },
  [COLLECTION_NAMES.invitations]: {
    $jsonSchema: {
      bsonType: 'object',
      required: ['coupleId', 'inviterId', 'codeHash', 'status', 'expiresAt', 'createdAt'],
      properties: {
        coupleId: objectId,
        inviterId: objectId,
        codeHash: { bsonType: 'string', pattern: '^[a-f0-9]{64}$' },
        status: { enum: ['open', 'accepted', 'revoked'] },
        expiresAt: date,
        createdAt: date,
        acceptedBy: nullableObjectId,
        acceptedAt: nullableDate,
        revokedAt: nullableDate,
      },
    },
  },
  [COLLECTION_NAMES.pings]: {
    $jsonSchema: {
      bsonType: 'object',
      required: [
        'coupleId',
        'senderId',
        'recipientId',
        'type',
        'emoji',
        'status',
        'createdAt',
        'idempotencyKey',
      ],
      properties: {
        coupleId: objectId,
        senderId: objectId,
        recipientId: objectId,
        type: { enum: [...PING_TYPES] },
        emoji: { bsonType: 'string', minLength: 1 },
        message: { bsonType: ['string', 'null'], maxLength: 140 },
        status: { enum: ['accepted', 'delivered', 'seen'] },
        createdAt: date,
        deliveredAt: nullableDate,
        seenAt: nullableDate,
        idempotencyKey: string,
      },
    },
  },
  [COLLECTION_NAMES.messages]: {
    $jsonSchema: {
      bsonType: 'object',
      required: ['coupleId', 'senderId', 'recipientId', 'type', 'createdAt', 'idempotencyKey'],
      properties: {
        coupleId: objectId,
        senderId: objectId,
        recipientId: objectId,
        type: { enum: [...MESSAGE_TYPES] },
        text: { bsonType: ['string', 'null'], maxLength: 2000 },
        mediaId: nullableObjectId,
        createdAt: date,
        editedAt: nullableDate,
        deletedAt: nullableDate,
        readAt: nullableDate,
        idempotencyKey: string,
      },
    },
  },
  [COLLECTION_NAMES.media]: {
    $jsonSchema: {
      bsonType: 'object',
      required: [
        'coupleId',
        'ownerId',
        'category',
        'mimeType',
        'sizeBytes',
        'status',
        'storageKey',
        'createdAt',
        'objectDeleted',
      ],
      properties: {
        coupleId: objectId,
        ownerId: objectId,
        category: { enum: [...MEDIA_CATEGORIES] },
        mimeType: string,
        sizeBytes: { bsonType: ['int', 'long', 'double'], minimum: 0 },
        durationMs: nullableInt,
        width: nullableInt,
        height: nullableInt,
        status: { enum: ['pending', 'ready', 'deleted'] },
        storageKey: {
          bsonType: 'string',
          pattern: '^couples/[a-f0-9]{24}/(photo|voice|avatar|wallpaper)/[a-f0-9]{24}\\.[a-z0-9]+$',
        },
        createdAt: date,
        readyAt: nullableDate,
        deletedAt: nullableDate,
        objectDeleted: bool,
      },
    },
  },
  [COLLECTION_NAMES.deviceTokens]: {
    $jsonSchema: {
      bsonType: 'object',
      required: ['userId', 'deviceId', 'platform', 'token', 'appVersion', 'createdAt', 'updatedAt'],
      properties: {
        userId: objectId,
        deviceId: { bsonType: 'string', minLength: 1, maxLength: 128 },
        platform: { enum: ['android'] },
        token: { bsonType: 'string', minLength: 1 },
        appVersion: string,
        model: nullableString,
        createdAt: date,
        updatedAt: date,
      },
    },
  },
  [COLLECTION_NAMES.memories]: {
    $jsonSchema: {
      bsonType: 'object',
      required: ['coupleId', 'createdBy', 'mediaId', 'pinned', 'createdAt', 'updatedAt'],
      properties: {
        coupleId: objectId,
        createdBy: objectId,
        mediaId: objectId,
        caption: { bsonType: ['string', 'null'], maxLength: 500 },
        takenAt: nullableDate,
        pinned: bool,
        createdAt: date,
        updatedAt: date,
        deletedAt: nullableDate,
      },
    },
  },
  [COLLECTION_NAMES.milestones]: {
    $jsonSchema: {
      bsonType: 'object',
      required: ['coupleId', 'createdBy', 'title', 'date', 'kind', 'visibility', 'createdAt', 'updatedAt'],
      properties: {
        coupleId: objectId,
        createdBy: objectId,
        title: { bsonType: 'string', minLength: 1, maxLength: 120 },
        date: { bsonType: 'string', pattern: '^\\d{4}-\\d{2}-\\d{2}$' },
        kind: { enum: [...MILESTONE_KINDS] },
        visibility: { enum: ['shared'] },
        createdAt: date,
        updatedAt: date,
      },
    },
  },
  [COLLECTION_NAMES.lists]: {
    $jsonSchema: {
      bsonType: 'object',
      required: [
        'coupleId',
        'createdBy',
        'updatedBy',
        'title',
        'kind',
        'body',
        'items',
        'pinned',
        'version',
        'createdAt',
        'updatedAt',
        'deletedAt',
      ],
      properties: {
        coupleId: objectId,
        createdBy: objectId,
        updatedBy: objectId,
        title: { bsonType: 'string', minLength: 1, maxLength: 120 },
        kind: { enum: [...LIST_KINDS] },
        body: { bsonType: 'string', maxLength: 10000 },
        items: {
          bsonType: 'array',
          maxItems: 200,
          items: {
            bsonType: 'object',
            required: ['id', 'text', 'done', 'doneBy', 'doneAt'],
            properties: {
              id: { bsonType: 'string', minLength: 1, maxLength: 32 },
              text: { bsonType: 'string', minLength: 1, maxLength: 300 },
              done: bool,
              doneBy: nullableObjectId,
              doneAt: nullableDate,
            },
          },
        },
        pinned: bool,
        version: { bsonType: ['int', 'long', 'double'], minimum: 1 },
        createdAt: date,
        updatedAt: date,
        deletedAt: nullableDate,
      },
    },
  },
  [COLLECTION_NAMES.events]: {
    $jsonSchema: {
      bsonType: 'object',
      required: ['coupleId', 'seq', 'type', 'actorId', 'payload', 'createdAt', 'push'],
      properties: {
        coupleId: objectId,
        seq: { bsonType: ['int', 'long', 'double'], minimum: 1 },
        // A dotted-name pattern rather than an enum of EVENT_TYPES: adding an event type must not need
        // a validator change (collMod), which a readWrite-only Atlas user cannot run. The app's
        // EventType union / Zod enum is the real allow-list.
        type: { bsonType: 'string', pattern: '^[a-z][a-z_]*\\.[a-z][a-z_]*$' },
        actorId: objectId,
        payload: { bsonType: 'object' },
        createdAt: date,
        push: {
          bsonType: 'object',
          required: ['state', 'attempts'],
          properties: {
            state: { enum: ['pending', 'sent', 'skipped', 'failed'] },
            attempts: { bsonType: ['int', 'long', 'double'], minimum: 0 },
            leaseUntil: nullableDate,
            lastError: nullableString,
            skipReason: nullableString,
            sentAt: nullableDate,
          },
        },
      },
    },
  },
};

/** Collections whose validator could not be updated (missing dbAdmin privilege). */
export const validatorWarnings: string[] = [];

/** Create collections with validators (or update validators on existing collections). */
export async function ensureValidators(db: Db): Promise<void> {
  const existing = new Set((await db.listCollections({}, { nameOnly: true }).toArray()).map((c) => c.name));
  for (const [name, validator] of Object.entries(VALIDATORS)) {
    if (existing.has(name)) {
      try {
        await db.command({ collMod: name, validator, validationLevel: 'strict', validationAction: 'error' });
      } catch (err) {
        // Atlas users with only readWrite cannot run collMod. Keep serving with the existing
        // validator and say exactly what to fix (docs/GO_LIVE.md → "Database permissions").
        if (isPermissionError(err)) {
          validatorWarnings.push(name);
          if (name === COLLECTION_NAMES.events) await detectStaleEventTypes(db);
          continue;
        }
        throw err;
      }
    } else {
      try {
        await db.createCollection(name, { validator, validationLevel: 'strict', validationAction: 'error' });
      } catch (err) {
        // createCollection is part of readWrite, so this should not happen; if a restricted role
        // refuses the validator option, still create the collection so the feature works.
        if (!isPermissionError(err)) throw err;
        validatorWarnings.push(name);
        await db.createCollection(name);
      }
    }
  }
}

function isPermissionError(err: unknown): boolean {
  const code = (err as { code?: number }).code;
  return code === 13 || code === 8000;
}

/**
 * Event types the app emits but the database's (old, un-updatable) `events` validator rejects.
 * Filled at boot when collMod on `events` is denied and the stored validator still has the strict
 * `type: { enum }` of earlier versions. Inserts of these types fail with DocumentValidationFailure
 * (code 121) until a dbAdmin restarts the server once; see EventPublisher for the fallback.
 */
export const staleEventTypes: string[] = [];

export async function detectStaleEventTypes(db: Db): Promise<void> {
  staleEventTypes.length = 0;
  try {
    const [info] = await db.listCollections({ name: COLLECTION_NAMES.events }).toArray();
    const options = (info as { options?: { validator?: Document } } | undefined)?.options;
    const allowed = options?.validator?.$jsonSchema?.properties?.type?.enum;
    if (!Array.isArray(allowed)) return; // no enum (pattern or no validator): every type is accepted
    for (const t of EVENT_TYPES) if (!allowed.includes(t)) staleEventTypes.push(t);
  } catch {
    // listCollections denied too: unknown. The publisher's code-121 handling still applies.
  }
}

export async function ensureSchema(db: Db): Promise<void> {
  await ensureValidators(db);
}
