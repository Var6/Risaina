import type { Db, Document } from 'mongodb';
import { COLLECTION_NAMES } from './collections.js';
import { EVENT_TYPES, MEDIA_CATEGORIES, MESSAGE_TYPES, MILESTONE_KINDS, PING_TYPES } from './types.js';

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
        passwordHash: nullableString,
        avatarMediaId: nullableObjectId,
        activeCoupleId: nullableObjectId,
        preferences,
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
  [COLLECTION_NAMES.events]: {
    $jsonSchema: {
      bsonType: 'object',
      required: ['coupleId', 'seq', 'type', 'actorId', 'payload', 'createdAt', 'push'],
      properties: {
        coupleId: objectId,
        seq: { bsonType: ['int', 'long', 'double'], minimum: 1 },
        type: { enum: [...EVENT_TYPES] },
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

/** Create collections with validators (or update validators on existing collections). */
export async function ensureValidators(db: Db): Promise<void> {
  const existing = new Set((await db.listCollections({}, { nameOnly: true }).toArray()).map((c) => c.name));
  for (const [name, validator] of Object.entries(VALIDATORS)) {
    if (existing.has(name)) {
      await db.command({ collMod: name, validator, validationLevel: 'strict', validationAction: 'error' });
    } else {
      await db.createCollection(name, { validator, validationLevel: 'strict', validationAction: 'error' });
    }
  }
}

export async function ensureSchema(db: Db): Promise<void> {
  await ensureValidators(db);
}
