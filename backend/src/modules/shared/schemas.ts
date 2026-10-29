import { z } from 'zod';
import {
  CHALLENGE_KINDS,
  CHAT_WALLPAPER_STYLES,
  EVENT_TYPES,
  GAME_KINDS,
  GAME_PRIZES,
  GAME_STATUSES,
  LIST_KINDS,
  LIST_MARKERS,
  LIVE_LOCATION_REASONS,
  MEDIA_CATEGORIES,
  MESSAGE_TYPES,
  MILESTONE_KINDS,
  PING_TYPES,
  RPS_MOVES,
  TRIVIA_DIFFICULTIES,
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

const round2 = (n: number) => Math.round(n * 100) / 100;

/** PATCH /me input: coordinates are rounded to 2 decimals server-side (~1 km). */
export const CityInput = z
  .object({
    name: z.string().trim().min(1).max(80),
    latitude: z.number().min(-90).max(90).transform(round2),
    longitude: z.number().min(-180).max(180).transform(round2),
  })
  .strict();

export const City = z
  .object({ name: z.string(), latitude: z.number(), longitude: z.number() })
  .meta({ id: 'City', description: 'Coordinates rounded to 2 decimals' });

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
    city: City.nullable().meta({ description: 'Weather city; null when unset' }),
    shareWeather: z.boolean().meta({ description: 'When false the partner sees city: null' }),
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
    city: City.nullable().meta({ description: 'null when unset or the partner turned shareWeather off' }),
  })
  .meta({ id: 'Partner' });

export const SharedPreferences = z
  .record(z.string().min(1).max(40), z.union([z.string().max(200), z.number(), z.boolean()]))
  .refine((o) => Object.keys(o).length <= 20, 'at most 20 shared preference keys')
  .meta({ id: 'SharedPreferences' });

export const ChatWallpaperStyle = z.enum(CHAT_WALLPAPER_STYLES);

export const ChatWallpaper = z
  .object({
    style: ChatWallpaperStyle,
    photoMediaId: IdString.nullable().meta({
      description: 'Set only for PHOTO; fetch with GET /media/:id/url',
    }),
    setBy: IdString,
    setAt: IsoDateTime,
  })
  .meta({ id: 'ChatWallpaper' });

export const Couple = z
  .object({
    id: IdString,
    status: z.enum(['pending', 'active', 'ended']),
    memberIds: z.array(IdString),
    relationshipStartDate: CalendarDate.nullable(),
    nextMeetingDate: IsoDateTimeNullable,
    nextMeetingLabel: z.string().nullable(),
    sharedPreferences: z
      .object({
        chatWallpaper: ChatWallpaper.nullable().meta({
          description: 'Shared chat wallpaper (same for both partners); null = app default',
        }),
      })
      .catchall(z.union([z.string(), z.number(), z.boolean()]))
      .meta({
        description:
          'Flat client keys (string|number|boolean) set with PATCH /couple { sharedPreferences } plus the typed, ' +
          'server-managed chatWallpaper (set with PATCH /couple { chatWallpaper })',
      }),
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
    viewOnce: z.boolean().meta({ description: 'View-once photo (only the recipient can open it, once)' }),
    openedAt: IsoDateTimeNullable.meta({ description: 'When the recipient opened a view-once photo' }),
    viewOnceExpired: z.boolean().meta({
      description: 'True when a view-once photo was purged unopened (openedAt stays null)',
    }),
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
    sourceMessageId: IdString.nullable().meta({
      description: 'Chat photo message this memory was auto-created from; null for manual memories',
    }),
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
export const ListMarker = z.enum(LIST_MARKERS);

export const ListItem = z
  .object({
    id: z.string(),
    text: z.string(),
    done: z.boolean(),
    doneBy: IdString.nullable(),
    doneAt: IsoDateTimeNullable,
    addedBy: IdString.nullable().meta({ description: 'Who added the item; null on items from before' }),
    addedAt: IsoDateTimeNullable.meta({ description: 'When it was added; null on items from before' }),
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
    // A plain string in RESPONSES (tolerant reader, as game kinds): an enum here would turn every
    // GET /lists into a 500 once the database holds a marker this build does not know.
    marker: z.string().meta({
      description:
        "Row marker: 'none' (default), 'number' or 'bullet'. Clients must tolerate unknown values.",
    }),
    checkboxes: z.boolean().meta({ description: 'Rows show checkboxes (default true)' }),
  })
  .meta({ id: 'List' });

// --- Widget board ---------------------------------------------------------------------------

export const WidgetBoard = z
  .object({
    authorId: IdString.meta({ description: 'Who set it; the audience is the other member' }),
    note: z.string().nullable(),
    emoji: z.string().nullable(),
    mood: z.string().nullable(),
    photoMediaId: IdString.nullable(),
    updatedAt: IsoDateTime,
  })
  .meta({ id: 'WidgetBoard' });

// --- SOS ------------------------------------------------------------------------------------

export const Sos = z
  .object({
    id: IdString,
    coupleId: IdString,
    senderId: IdString,
    recipientId: IdString,
    message: z.string().nullable(),
    createdAt: IsoDateTime,
    acknowledgedAt: IsoDateTimeNullable,
    // safety additions
    panic: z.boolean().meta({ description: 'A panic alert: the sender also shares live location' }),
    deliveredAt: IsoDateTimeNullable.meta({ description: "The recipient's phone started ringing" }),
    resolvedAt: IsoDateTimeNullable.meta({ description: 'The sender said they are safe now' }),
    delivery: z
      .object({
        alarmMuted: z.boolean().nullable(),
        fgsStarted: z.boolean().nullable(),
        notificationsEnabled: z.boolean().nullable(),
        interruptionFilter: z.string().nullable(),
      })
      .nullable()
      .meta({ description: "What the recipient's phone reported when it rang (device state only)" }),
  })
  .meta({ id: 'Sos' });

// --- Live location (safety) ----------------------------------------------------------------

export const LiveLocationReason = z.enum(LIVE_LOCATION_REASONS);

export const LocationFix = z
  .object({
    lat: z.number(),
    lng: z.number(),
    accuracyM: z.number().int(),
    at: IsoDateTime.meta({ description: 'When the phone took the fix (server clock)' }),
    batteryPct: z.number().int().nullable(),
  })
  .meta({ id: 'LocationFix' });

const liveCommon = {
  sessionId: IdString,
  userId: IdString.meta({ description: 'The sharer' }),
  reason: LiveLocationReason,
  sosId: IdString.nullable(),
  startedAt: IsoDateTime,
  expiresAt: IsoDateTimeNullable.meta({ description: 'null: until the sharer turns it off' }),
  updatedAt: IsoDateTime,
};

/** A live session with the latest fix. Only ever sent to the two members (HTTP and WS frames). */
export const LiveLocation = z
  .object({
    ...liveCommon,
    lastSeenAt: IsoDateTime.meta({ description: "Last check-in from the sharer's phone (liveness)" }),
    fix: LocationFix.nullable().meta({ description: 'null until the first fix arrives' }),
  })
  .meta({ id: 'LiveLocation' });

/** Event payload of location.started: never coordinates. */
export const LiveLocationSession = z.object(liveCommon).meta({ id: 'LiveLocationSession' });

// --- Games ----------------------------------------------------------------------------------

export const GameKind = z.enum(GAME_KINDS);
export const RpsMove = z.enum(RPS_MOVES);
export const GameStatus = z.enum(GAME_STATUSES);
export const ChallengeKind = z.enum(CHALLENGE_KINDS);
export const GamePrize = z
  .enum(GAME_PRIZES)
  .meta({ description: "What the winner gets: the blind truth or dare, or 'none' (just for fun)" });

/**
 * Viewer-specific challenge. The winner always sees both texts. The loser sees `truth` and `dare`
 * as null until they choose; afterwards only the chosen text (the other stays null forever).
 */
export const GameChallenge = z
  .object({
    truth: z.string().nullable().meta({ description: 'Winner: always. Loser: only once they chose truth' }),
    dare: z.string().nullable().meta({ description: 'Winner: always. Loser: only once they chose dare' }),
    chosen: ChallengeKind.nullable().meta({ description: "The loser's blind pick; null until chosen" }),
    by: IdString.meta({ description: 'The winner (who wrote both)' }),
    at: IsoDateTime,
    chosenAt: IsoDateTimeNullable,
    doneAt: IsoDateTimeNullable.meta({ description: 'Loser marked it done (cleared by a reject)' }),
    confirmedAt: IsoDateTimeNullable.meta({ description: 'Winner confirmed (status completed)' }),
    rejectNote: z
      .string()
      .nullable()
      .meta({ description: "Winner's note on the last reject; cleared by the next complete" }),
  })
  .meta({ id: 'GameChallenge' });

/** Text-free challenge summary carried by `game.updated` events. */
export const GameEventChallenge = z
  .object({
    chosen: ChallengeKind.nullable(),
    hasChallenge: z.literal(true),
    chosenAt: IsoDateTimeNullable,
    doneAt: IsoDateTimeNullable,
    confirmedAt: IsoDateTimeNullable,
  })
  .meta({ id: 'GameEventChallenge' });

const gameCommon = {
  id: IdString,
  coupleId: IdString,
  // A plain string in RESPONSES on purpose (tolerant reader): responses are serialized through
  // these schemas, so an enum here would turn every GET /games into a 500 the moment the database
  // holds a kind this build does not know (e.g. after a rollback). Requests still use GameKind.
  kind: z.string().meta({
    description: 'Game kind: rps, tictactoe, trivia or this_or_that. Clients must tolerate unknown kinds.',
  }),
  createdBy: IdString,
  createdAt: IsoDateTime,
  updatedAt: IsoDateTime,
  status: GameStatus,
  winnerId: IdString.nullable(),
  loserId: IdString.nullable(),
  completedAt: IsoDateTimeNullable,
  round: z.number().int().min(1),
  prize: GamePrize,
};

// Tic-tac-toe, Trivia duel and This or That (games stream). Nothing in tic-tac-toe is secret, so
// the event view carries the board. Quiz secrets (questions not reached yet, correct answers,
// the partner's picks and score) live only in the viewer DTO, which reveals them step by step.

const TttMark = z.enum(['x', 'o']);
const tttCommon = {
  board: z.array(TttMark.nullable()).length(9).meta({ description: '9 cells, index = row * 3 + col' }),
  xId: IdString.meta({ description: 'Plays X this round and moves first (the invitee in round 1)' }),
  turnId: IdString.nullable().meta({ description: 'Whose turn it is; null once the round is over' }),
  lastCell: z.number().int().min(0).max(8).nullable(),
  line: z.array(z.number().int().min(0).max(8)).nullable().meta({ description: 'The winning cells' }),
  moveCount: z.number().int().min(0).max(9),
};
export const TicTacToe = z
  .object({ ...tttCommon, myMark: TttMark, myTurn: z.boolean() })
  .meta({ id: 'TicTacToe' });
export const TicTacToeEvent = z.object(tttCommon).meta({ id: 'TicTacToeEvent' });

const QuizSource = z.enum(['opentdb', 'builtin', 'curated']);
const QuizCategory = z.object({ id: z.number().int(), name: z.string() });

export const QuizItem = z
  .object({
    index: z.number().int().min(0),
    text: z.string().nullable().meta({ description: 'Trivia question; null for This or That' }),
    choices: z.array(z.string()),
    category: z.string().nullable(),
    difficulty: z.enum(TRIVIA_DIFFICULTIES).nullable(),
    myChoice: z.number().int().nullable(),
    correct: z
      .number()
      .int()
      .nullable()
      .meta({ description: 'Trivia: the right choice, only for items the viewer already answered' }),
    partnerChoice: z.number().int().nullable().meta({ description: 'Only once both finished' }),
  })
  .meta({ id: 'QuizItem' });

export const Quiz = z
  .object({
    source: QuizSource,
    attribution: z
      .string()
      .nullable()
      .meta({ description: 'Credit to show on the card (CC BY-SA 4.0) when source is opentdb' }),
    category: QuizCategory.nullable(),
    requestedCategoryId: z.number().int().nullable().meta({
      description:
        'Trivia: the category asked for; null = any (a new round picks a curated one at random). Reuse it for "Play again".',
    }),
    difficulty: z.enum(TRIVIA_DIFFICULTIES).nullable(),
    deck: z.string().nullable(),
    total: z.number().int().min(1),
    items: z.array(QuizItem).meta({
      description:
        'Trivia: the answered items plus the current one (all once both finished). This or That: all prompts.',
    }),
    myAnswered: z.number().int().min(0),
    partnerAnswered: z.number().int().min(0),
    myFinished: z.boolean(),
    partnerFinished: z.boolean(),
    myScore: z.number().int().nullable().meta({ description: 'Trivia only' }),
    partnerScore: z.number().int().nullable().meta({ description: 'Trivia, once both finished' }),
    matches: z.number().int().nullable().meta({ description: 'This or That, once both finished' }),
  })
  .meta({ id: 'Quiz' });

export const QuizEvent = z
  .object({
    source: QuizSource,
    categoryName: z.string().nullable(),
    deck: z.string().nullable(),
    total: z.number().int().min(1),
    progress: z.array(z.object({ userId: IdString, answered: z.number().int(), finished: z.boolean() })),
    scores: z
      .array(z.object({ userId: IdString, score: z.number().int() }))
      .nullable()
      .meta({ description: 'Trivia, once both finished' }),
    matches: z.number().int().nullable().meta({ description: 'This or That, once both finished' }),
  })
  .meta({ id: 'QuizEvent' });

/** Viewer-specific game (HTTP responses). The partner's move stays hidden until both moved. */
export const Game = z
  .object({
    ...gameCommon,
    challenge: GameChallenge.nullable(),
    answer: z.string().nullable().meta({
      description: "The loser's answer; visible to both from awaiting_confirmation on (null before)",
    }),
    myMove: RpsMove.nullable(),
    partnerMoved: z.boolean(),
    partnerMove: RpsMove.nullable().meta({ description: 'null until both players moved this round' }),
    ttt: TicTacToe.nullable().meta({ description: 'Tic-tac-toe state; null for other kinds' }),
    quiz: Quiz.nullable().meta({ description: 'Trivia duel / This or That state; null for other kinds' }),
  })
  .meta({ id: 'Game' });

/**
 * Viewer-neutral game (game.updated event payload). Moves only appear once both moved; truth/dare
 * texts and the answer never appear (clients re-fetch GET /games/:id for their own view).
 */
export const GameEventView = z
  .object({
    ...gameCommon,
    challenge: GameEventChallenge.nullable(),
    movedBy: z.array(IdString).meta({ description: 'Who has moved this round (never which move)' }),
    moves: z
      .array(z.object({ userId: IdString, move: RpsMove }))
      .nullable()
      .meta({ description: 'Both moves of this round once both moved; otherwise null' }),
    ttt: TicTacToeEvent.nullable(),
    quiz: QuizEvent.nullable().meta({ description: 'Progress only: never questions, answers or picks' }),
  })
  .meta({ id: 'GameEventView' });

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
export type WidgetBoardDto = z.infer<typeof WidgetBoard>;
export type SosDto = z.infer<typeof Sos>;
export type ChatWallpaperDto = z.infer<typeof ChatWallpaper>;
export type GameDto = z.infer<typeof Game>;
export type GameEventViewDto = z.infer<typeof GameEventView>;
export type LiveLocationDto = z.infer<typeof LiveLocation>;
export type LiveLocationSessionDto = z.infer<typeof LiveLocationSession>;
