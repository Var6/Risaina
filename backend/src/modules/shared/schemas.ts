import { z } from 'zod';
import {
  EVENT_TYPES,
  LIST_KINDS,
  MEDIA_CATEGORIES,
  MESSAGE_TYPES,
  MILESTONE_KINDS,
  PING_TYPES,
} from '../../db/types.js';
import { OBJECT_ID_REGEX } from '../../lib/ids.js';
import { parseHHmm } from '../../lib/quiet-hours.js';
import { graphemeCount } from '../../lib/text.js';
import { isValidBirthday, isValidTimeZone } from '../../lib/time.js';

/** Wire (DTO) schemas from API.md "Shapes". Response bodies are serialized through these, so
 *  unknown/internal fields (hashes, tokens, storage keys) are stripped by construction. */

export const IdString = z
  .string()
  .regex(OBJECT_ID_REGEX, 'must be a 24-hex object id')
  .meta({ example: '665f1c2e9b1d4a3f8c2e7a10' });
export const IsoDateTime = z
  .string()
  .meta({ description: 'ISO-8601 UTC timestamp', example: '2026-10-25T12:00:00.000Z' });
export const IsoDateTimeNullable = IsoDateTime.nullable();
export const CalendarDate = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/, 'must be YYYY-MM-DD')
  .refine((s) => !Number.isNaN(new Date(`${s}T00:00:00Z`).getTime()), 'must be a valid calendar date');
export const HHmm = z.string().refine((s) => parseHHmm(s) !== null, 'must be HH:mm (24 h)');
export const TimeZone = z.string().min(1).max(64).refine(isValidTimeZone, 'must be a valid IANA time zone');
export const UuidV4 = z.uuid({ version: 'v4' });
export const Birthday = z
  .string()
  .refine(isValidBirthday, 'must be a real date as YYYY-MM-DD, or --MM-DD to keep the year private')
  .meta({
    description: "'YYYY-MM-DD', or '--MM-DD' when the year is private (Feb 29 allowed). Not in the future.",
    example: '1996-04-12',
  });
/** IANA zone id for the user profile. Fixed offsets like '+05:30' are rejected (Intl accepts them). */
export const ProfileTimeZone = z
  .string()
  .min(1)
  .max(64)
  .refine((s) => /^[A-Za-z]/.test(s) && isValidTimeZone(s), 'must be a valid IANA time zone')
  .meta({ example: 'Asia/Kolkata' });

export const ErrorEnvelope = z
  .object({
    error: z.object({
      code: z.string(),
      message: z.string(),
      details: z.unknown().optional(),
    }),
  })
  .meta({ id: 'ErrorEnvelope' });

export const NoContent = z.null().meta({ description: 'No content' });

export const PaginationQuery = z.object({
  limit: z.coerce.number().int().min(1).max(100).default(30),
  before: z
    .string()
    .min(1)
    .max(200)
    .optional()
    .meta({ description: 'Opaque cursor from a previous nextCursor' }),
});

export const IdParams = z.object({ id: IdString });

// --- Users / couple -------------------------------------------------------------------------

export const User = z
  .object({
    id: IdString,
    displayName: z.string(),
    avatarMediaId: IdString.nullable(),
    createdAt: IsoDateTime,
    emailVerified: z.boolean().meta({
      description:
        'True once the email was confirmed (6-digit code) or came from Google. Informational only.',
    }),
    hasPassword: z.boolean().meta({
      description:
        'False for Google-only accounts. Decides DELETE /me confirmation: { password } vs { confirm: "DELETE" }.',
    }),
    birthday: z.string().nullable().meta({ description: "'YYYY-MM-DD' or '--MM-DD' (year private)" }),
    timeZone: z.string().nullable().meta({ description: 'IANA time zone id' }),
    shareLocalTime: z.boolean().meta({ description: 'When false the partner sees timeZone: null' }),
  })
  .meta({ id: 'User' });

export const Partner = z
  .object({
    id: IdString,
    displayName: z.string(),
    avatarMediaId: IdString.nullable(),
    birthday: z.string().nullable().meta({ description: "'YYYY-MM-DD' or '--MM-DD' (year private)" }),
    timeZone: z
      .string()
      .nullable()
      .meta({ description: 'IANA id; null when unset or the partner turned shareLocalTime off' }),
  })
  .meta({ id: 'Partner' });

export const SharedPreferences = z
  .record(z.string().min(1).max(40), z.union([z.string().max(200), z.number(), z.boolean()]))
  .refine((o) => Object.keys(o).length <= 20, 'at most 20 shared preference keys')
  .meta({ id: 'SharedPreferences' });

export const Couple = z
  .object({
    id: IdString,
    status: z.enum(['pending', 'active', 'ended']),
    memberIds: z.array(IdString),
    relationshipStartDate: CalendarDate.nullable(),
    nextMeetingDate: IsoDateTimeNullable,
    nextMeetingLabel: z.string().nullable(),
    sharedPreferences: z.record(z.string(), z.union([z.string(), z.number(), z.boolean()])),
    createdAt: IsoDateTime,
  })
  .meta({ id: 'Couple' });

export const Tokens = z
  .object({
    accessToken: z.string(),
    accessExpiresAt: IsoDateTime,
    refreshToken: z.string(),
    refreshExpiresAt: IsoDateTime,
  })
  .meta({ id: 'Tokens' });

export const QuietHours = z.object({
  enabled: z.boolean(),
  start: HHmm,
  end: HHmm,
  timeZone: TimeZone,
});

export const WidgetPrivacy = z.object({
  showPhoto: z.boolean(),
  showNoteText: z.boolean(),
  showEmoji: z.boolean(),
});

export const Preferences = z
  .object({
    notificationsEnabled: z.boolean(),
    previewInNotifications: z.boolean(),
    quietHours: QuietHours,
    allowPushInQuietHours: z.boolean(),
    pingCooldownSeconds: z.number().int().min(0).max(3600),
    widgetPrivacy: WidgetPrivacy,
    reducedMotion: z.boolean(),
  })
  .meta({ id: 'Preferences' });

/** PUT /me/preferences accepts a partial document (nested objects may be partial too). */
export const PreferencesUpdate = z
  .object({
    notificationsEnabled: z.boolean().optional(),
    previewInNotifications: z.boolean().optional(),
    quietHours: QuietHours.partial().optional(),
    allowPushInQuietHours: z.boolean().optional(),
    pingCooldownSeconds: z.number().int().min(0).max(3600).optional(),
    widgetPrivacy: WidgetPrivacy.partial().optional(),
    reducedMotion: z.boolean().optional(),
  })
  .strict();

// --- Pings ----------------------------------------------------------------------------------

export const PingType = z.enum(PING_TYPES);
export const Emoji = z
  .string()
  .min(1)
  .max(16)
  .refine((s) => {
    const n = graphemeCount(s);
    return n >= 1 && n <= 2;
  }, 'emoji must be 1-2 grapheme clusters');

export const Ping = z
  .object({
    id: IdString,
    coupleId: IdString,
    senderId: IdString,
    recipientId: IdString,
    type: PingType,
    emoji: z.string(),
    message: z.string().nullable(),
    status: z.enum(['accepted', 'delivered', 'seen']),
    createdAt: IsoDateTime,
    deliveredAt: IsoDateTimeNullable,
    seenAt: IsoDateTimeNullable,
    idempotencyKey: z.string(),
  })
  .meta({ id: 'Ping' });

// --- Messages -------------------------------------------------------------------------------

export const MessageType = z.enum(MESSAGE_TYPES);

export const Message = z
  .object({
    id: IdString,
    coupleId: IdString,
    senderId: IdString,
    recipientId: IdString,
    type: MessageType,
    text: z.string().nullable(),
    mediaId: IdString.nullable(),
    createdAt: IsoDateTime,
    editedAt: IsoDateTimeNullable,
    deletedAt: IsoDateTimeNullable,
    readAt: IsoDateTimeNullable,
    idempotencyKey: z.string(),
  })
  .meta({ id: 'Message' });

// --- Media ----------------------------------------------------------------------------------

export const MediaCategory = z.enum(MEDIA_CATEGORIES);

export const Media = z
  .object({
    id: IdString,
    coupleId: IdString,
    ownerId: IdString,
    category: MediaCategory,
    mimeType: z.string(),
    sizeBytes: z.number().int(),
    durationMs: z.number().int().nullable(),
    width: z.number().int().nullable(),
    height: z.number().int().nullable(),
    status: z.enum(['pending', 'ready', 'deleted']),
    createdAt: IsoDateTime,
  })
  .meta({ id: 'Media' });

// --- Memories / milestones -----------------------------------------------------------------

export const Memory = z
  .object({
    id: IdString,
    coupleId: IdString,
    createdBy: IdString,
    mediaId: IdString,
    caption: z.string().nullable(),
    takenAt: IsoDateTimeNullable,
    pinned: z.boolean(),
    createdAt: IsoDateTime,
    deletedAt: IsoDateTimeNullable,
  })
  .meta({ id: 'Memory' });

export const MilestoneKind = z.enum(MILESTONE_KINDS);

export const Milestone = z
  .object({
    id: IdString,
    coupleId: IdString,
    createdBy: IdString,
    title: z.string(),
    date: CalendarDate,
    kind: MilestoneKind,
    visibility: z.literal('shared'),
    createdAt: IsoDateTime,
  })
  .meta({ id: 'Milestone' });

// --- Lists --------------------------------------------------------------------------------

export const ListKind = z.enum(LIST_KINDS);

export const ListItem = z
  .object({
    id: z.string(),
    text: z.string(),
    done: z.boolean(),
    doneBy: IdString.nullable(),
    doneAt: IsoDateTimeNullable,
  })
  .meta({ id: 'ListItem' });

export const List = z
  .object({
    id: IdString,
    coupleId: IdString,
    createdBy: IdString,
    updatedBy: IdString,
    title: z.string(),
    kind: ListKind,
    body: z.string(),
    items: z.array(ListItem),
    pinned: z.boolean(),
    version: z.number().int().min(1),
    createdAt: IsoDateTime,
    updatedAt: IsoDateTime,
  })
  .meta({ id: 'List' });

// --- Events ---------------------------------------------------------------------------------

export const EventType = z.enum(EVENT_TYPES);

export const Event = z
  .object({
    id: IdString,
    coupleId: IdString,
    seq: z.number().int().min(1),
    type: EventType,
    actorId: IdString,
    payload: z.record(z.string(), z.unknown()),
    createdAt: IsoDateTime,
  })
  .meta({ id: 'Event' });

export type UserDto = z.infer<typeof User>;
export type PartnerDto = z.infer<typeof Partner>;
export type CoupleDto = z.infer<typeof Couple>;
export type TokensDto = z.infer<typeof Tokens>;
export type PreferencesDto = z.infer<typeof Preferences>;
export type PingDto = z.infer<typeof Ping>;
export type MessageDto = z.infer<typeof Message>;
export type MediaDto = z.infer<typeof Media>;
export type MemoryDto = z.infer<typeof Memory>;
export type MilestoneDto = z.infer<typeof Milestone>;
export type EventDto = z.infer<typeof Event>;
export type ListDto = z.infer<typeof List>;
export type ListItemDto = z.infer<typeof ListItem>;
