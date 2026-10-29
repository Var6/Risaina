import type { ObjectId } from 'mongodb';

/** MongoDB document shapes. DTO (wire) shapes live in modules/shared/schemas.ts. */

export interface QuietHours {
  enabled: boolean;
  start: string; // HH:mm
  end: string; // HH:mm
  timeZone: string; // IANA
}

export interface WidgetPrivacy {
  showPhoto: boolean;
  showNoteText: boolean;
  showEmoji: boolean;
}

export interface Preferences {
  notificationsEnabled: boolean;
  previewInNotifications: boolean;
  quietHours: QuietHours;
  allowPushInQuietHours: boolean;
  pingCooldownSeconds: number;
  widgetPrivacy: WidgetPrivacy;
  reducedMotion: boolean;
}

export const DEFAULT_PREFERENCES: Preferences = {
  notificationsEnabled: true,
  previewInNotifications: true,
  quietHours: { enabled: false, start: '22:00', end: '07:00', timeZone: 'UTC' },
  allowPushInQuietHours: false,
  pingCooldownSeconds: 15,
  widgetPrivacy: { showPhoto: true, showNoteText: true, showEmoji: true },
  reducedMotion: false,
};

/** A user's city for the partner's weather widget. Coordinates are rounded to 2 decimals (~1 km). */
export interface City {
  name: string; // 1–80
  latitude: number; // -90..90
  longitude: number; // -180..180
}

export interface UserDoc {
  _id: ObjectId;
  email: string; // lowercased; unique
  displayName: string;
  // null for Google-only accounts, after an unverified account was claimed via Google sign-in,
  // and once the account is deleted
  passwordHash: string | null;
  // Google `sub` claim; absent until the account signs in with Google (unique when present)
  googleSub?: string;
  // null/absent until the email is verified (code email or Google sign-in). Legacy docs lack it.
  emailVerifiedAt?: Date | null;
  avatarMediaId: ObjectId | null;
  activeCoupleId: ObjectId | null;
  preferences: Preferences;
  // Profile fields added after v1. Optional in the validator (Atlas cannot collMod) and absent on
  // older documents: read missing as birthday/timeZone null and shareLocalTime true.
  birthday?: string | null; // 'YYYY-MM-DD', or '--MM-DD' when the year is private
  timeZone?: string | null; // IANA id
  shareLocalTime?: boolean; // false hides timeZone from the partner
  // Weather city (added later; optional, absent ⇒ city null, shareWeather true).
  city?: City | null;
  shareWeather?: boolean; // false hides city from the partner

  createdAt: Date;
  updatedAt: Date;
  deletedAt: Date | null;
}

export interface SessionDoc {
  _id: ObjectId; // = `sid` claim of access tokens issued for this session
  userId: ObjectId;
  familyId: ObjectId; // all rotations of one login share a family
  refreshHash: string; // sha256(refreshToken), unique
  expiresAt: Date;
  createdAt: Date;
  lastUsedAt: Date | null;
  rotatedAt: Date | null; // set when this refresh token was exchanged (token now stale)
  revokedAt: Date | null; // set on logout / reuse detection / account deletion
  revokedReason: string | null;
  deviceId: string | null;
  userAgent: string | null;
  // The session this refresh token was rotated into (optional; absent on older sessions). Lets a
  // refresh whose response never reached the phone be retried within a short grace window.
  replacedBy?: ObjectId | null;
}

export interface PasswordResetDoc {
  _id: ObjectId;
  userId: ObjectId;
  tokenHash: string;
  expiresAt: Date;
  usedAt: Date | null;
  createdAt: Date;
}

export interface EmailVerificationDoc {
  _id: ObjectId;
  userId: ObjectId;
  codeHash: string; // HMAC-SHA256(userId:code) hex; the 6-digit code itself is never stored
  expiresAt: Date; // TTL index (with grace, so the hourly resend cap can count recent sends)
  attempts: number; // wrong guesses against this code
  createdAt: Date;
}

export type CoupleStatus = 'pending' | 'active' | 'ended';

export type SharedPreferences = Record<string, string | number | boolean>;

export const CHAT_WALLPAPER_STYLES = ['DEFAULT', 'PAPER', 'HEARTS', 'NIGHT', 'GLOW', 'PHOTO'] as const;
export type ChatWallpaperStyle = (typeof CHAT_WALLPAPER_STYLES)[number];

/** Shared chat wallpaper (same for both partners). Exposed as couple.sharedPreferences.chatWallpaper. */
export interface ChatWallpaper {
  style: ChatWallpaperStyle;
  photoMediaId: ObjectId | null; // set only for PHOTO
  setBy: ObjectId;
  setAt: Date;
}

export interface CoupleDoc {
  _id: ObjectId;
  status: CoupleStatus;
  memberIds: ObjectId[]; // 1 while pending, exactly 2 once active
  createdBy: ObjectId;
  eventSeq: number; // monotonically increasing per couple, allocated with $inc
  relationshipStartDate: string | null; // YYYY-MM-DD
  nextMeetingDate: Date | null;
  nextMeetingLabel: string | null;
  sharedPreferences: SharedPreferences;
  // Added later: optional in the validator, absent on older documents (read as null). Stored as a
  // top-level field so `PATCH /couple { sharedPreferences }` (which replaces the flat map) keeps it.
  chatWallpaper?: ChatWallpaper | null;
  createdAt: Date;
  updatedAt: Date;
  activatedAt: Date | null;
  endedAt: Date | null;
  endedBy: ObjectId | null;
  // Set (with status 'ended') when an active couple is unlinked: its couple-scoped data is purged
  // (couples/purge.ts) and the cleanup worker re-runs that idempotent purge while the final
  // `couple.unlinked` event is retained. Optional; absent on couples ended before purging existed.
  purgedAt?: Date | null;
}

export type InvitationStatus = 'open' | 'accepted' | 'revoked';

export interface InvitationDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  inviterId: ObjectId;
  codeHash: string; // sha256(code), unique; the plaintext code is never stored
  status: InvitationStatus;
  expiresAt: Date;
  createdAt: Date;
  acceptedBy: ObjectId | null;
  acceptedAt: Date | null;
  revokedAt: Date | null;
}

export const PING_TYPES = [
  'thinking_of_you',
  'miss_you',
  'hug',
  'kiss',
  'goodnight',
  'good_morning',
  'custom',
] as const;
export type PingType = (typeof PING_TYPES)[number];
export type PingStatus = 'accepted' | 'delivered' | 'seen';

export interface PingDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  senderId: ObjectId;
  recipientId: ObjectId;
  type: PingType;
  emoji: string;
  message: string | null;
  status: PingStatus;
  createdAt: Date;
  deliveredAt: Date | null;
  seenAt: Date | null;
  idempotencyKey: string;
}

export const MESSAGE_TYPES = ['text', 'emoji', 'photo', 'voice'] as const;
export type MessageType = (typeof MESSAGE_TYPES)[number];

export interface MessageDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  senderId: ObjectId;
  recipientId: ObjectId;
  type: MessageType;
  text: string | null;
  mediaId: ObjectId | null;
  createdAt: Date;
  editedAt: Date | null;
  deletedAt: Date | null;
  readAt: Date | null;
  idempotencyKey: string;
  // View-once photos (added after v1). Optional in the validator (Atlas cannot collMod) and absent
  // on older documents: read missing viewOnce as false, openedAt / viewOnceExpiredAt as null.
  viewOnce?: boolean;
  openedAt?: Date | null; // set by the recipient's first POST /messages/:id/open
  viewOnceExpiredAt?: Date | null; // set by the cleanup worker when the photo was purged unopened
}

export const MEDIA_CATEGORIES = ['photo', 'voice', 'avatar', 'wallpaper'] as const;
export type MediaCategory = (typeof MEDIA_CATEGORIES)[number];
export type MediaStatus = 'pending' | 'ready' | 'deleted';

export interface MediaDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  ownerId: ObjectId;
  category: MediaCategory;
  mimeType: string;
  sizeBytes: number; // declared by client; verified on complete
  durationMs: number | null;
  width: number | null;
  height: number | null;
  status: MediaStatus;
  storageKey: string; // couples/{coupleId}/{category}/{mediaId}.{ext}
  createdAt: Date;
  readyAt: Date | null;
  deletedAt: Date | null;
  objectDeleted: boolean; // storage object removed (cleanup worker flips this)
  // Set when the media is attached to a view-once message (optional; absent ⇒ false / null).
  // View-once media is never downloadable through GET /media/:id/url.
  viewOnce?: boolean;
  openedAt?: Date | null; // mirrors the message's openedAt so cleanup can find it on media alone
}

export interface DeviceTokenDoc {
  _id: ObjectId;
  userId: ObjectId;
  deviceId: string;
  platform: 'android';
  token: string; // FCM registration token; never returned by any endpoint
  appVersion: string;
  model: string | null;
  createdAt: Date;
  updatedAt: Date;
  // Delivery health (stream: delivery). Optional in the validator (Atlas cannot collMod) and absent
  // until the first push / ack: read missing as null / false / [].
  lastPushSentAt?: Date | null; // the outbox's last push FCM accepted for this token
  lastPushSentPriority?: 'high' | 'normal' | null;
  lastPushAckAt?: Date | null; // the app said it received a push (X-Risaina-Push header on GET /events)
  lastPushPriority?: 'high' | 'normal' | null; // priority that push arrived with on the phone
  lastPushDowngraded?: boolean; // sent high, arrived normal: FCM deprioritised this phone
  healthFlags?: string[]; // short codes the app reports, e.g. 'battery_optimized', 'standby_rare'
  sdkInt?: number | null; // Android API level, sent by newer apps on PUT /devices (absent: unknown)
}

export interface MemoryDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  createdBy: ObjectId;
  mediaId: ObjectId;
  caption: string | null;
  takenAt: Date | null;
  pinned: boolean;
  createdAt: Date;
  updatedAt: Date;
  deletedAt: Date | null;
  // Chat photo this memory was auto-created from (optional; absent on manual / older memories).
  sourceMessageId?: ObjectId | null;
}

export const MILESTONE_KINDS = ['anniversary', 'meeting', 'custom'] as const;
export type MilestoneKind = (typeof MILESTONE_KINDS)[number];

export interface MilestoneDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  createdBy: ObjectId;
  title: string;
  date: string; // YYYY-MM-DD
  kind: MilestoneKind;
  visibility: 'shared';
  createdAt: Date;
  updatedAt: Date;
}

export const EVENT_TYPES = [
  'ping.created',
  'ping.delivered',
  'ping.seen',
  'message.created',
  'message.read',
  'message.deleted',
  'message.opened',
  'media.ready',
  'memory.created',
  'memory.updated',
  'memory.deleted',
  'milestone.created',
  'milestone.updated',
  'milestone.deleted',
  'couple.updated',
  'couple.unlinked',
  'partner.updated',
  'list.created',
  'list.updated',
  'list.deleted',
  'widget.updated',
  'sos.raised',
  'sos.acknowledged',
  'game.updated',
  // safety: SOS delivery receipt / "I'm safe", live location start and stop (never coordinates)
  'sos.delivered',
  'sos.resolved',
  'location.started',
  'location.stopped',
  // shared calendar (events and dated to-dos)
  'calendar.created',
  'calendar.updated',
  'calendar.deleted',
  // wake together (shared alarms)
  'alarm.created',
  'alarm.updated',
  'alarm.deleted',
  'alarm.woke',
] as const;
export type EventType = (typeof EVENT_TYPES)[number];

export const LIST_KINDS = ['note', 'checklist'] as const;
export type ListKind = (typeof LIST_KINDS)[number];
/**
 * How a list's rows are drawn: a format on top of `kind`, not a new kind (the live `lists`
 * validator's kind enum cannot be widened, and v75 reads an unknown kind as a note).
 */
export const LIST_MARKERS = ['none', 'number', 'bullet'] as const;
export type ListMarker = (typeof LIST_MARKERS)[number];

export interface ListItem {
  id: string; // 12-char random, server-assigned when the client omits it
  text: string;
  done: boolean;
  doneBy: ObjectId | null;
  doneAt: Date | null;
  // Who added the item and when (server-owned, added later). Absent on older items: read as null.
  addedBy?: ObjectId | null;
  addedAt?: Date | null;
}

export interface ListDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  createdBy: ObjectId;
  updatedBy: ObjectId;
  title: string;
  kind: ListKind;
  body: string;
  items: ListItem[];
  pinned: boolean;
  version: number; // starts at 1, +1 per PATCH (optimistic concurrency via expectedVersion)
  createdAt: Date;
  updatedAt: Date;
  deletedAt: Date | null;
  // Format and idempotent create (added later; optional in the validator, absent on older lists):
  // read missing marker as 'none' and checkboxes as true.
  marker?: ListMarker;
  checkboxes?: boolean;
  clientKey?: string; // the creating phone's retry key (unique per couple while present)
}

// --- Calendar ------------------------------------------------------------------------------

export const CALENDAR_KINDS = ['event', 'todo'] as const;
export type CalendarKind = (typeof CALENDAR_KINDS)[number];
export const CALENDAR_REPEATS = ['none', 'weekly', 'monthly', 'yearly'] as const;
export type CalendarRepeat = (typeof CALENDAR_REPEATS)[number];
export const CALENDAR_SOURCE_KINDS = ['message', 'list', 'list_item'] as const;
export type CalendarSourceKind = (typeof CALENDAR_SOURCE_KINDS)[number];

/** Where an entry was made from. Ids only, never copied text, so a deleted message leaves nothing. */
export interface CalendarSource {
  kind: CalendarSourceKind; // plain string in the validator
  messageId?: ObjectId; // kind 'message'
  listId?: ObjectId; // kinds 'list' and 'list_item'
  itemId?: string; // kind 'list_item'
}

/**
 * One shared calendar entry: an event or a dated to-do. Milestones, the anniversary, birthdays and
 * the next visit are NOT copied here; the app derives them. The server stores wall-clock fields and
 * never computes occurrences or reminder times: each phone schedules its own reminders.
 */
export interface CalendarEntryDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  createdBy: ObjectId;
  updatedBy: ObjectId;
  kind: CalendarKind; // plain string in the validator (Zod is the allow-list)
  title: string; // 1–120
  notes: string | null; // ≤ 2000
  emoji: string | null; // 1–2 graphemes
  date: string; // YYYY-MM-DD: the first (or only) occurrence; a to-do's due date
  endDate: string | null; // YYYY-MM-DD, all-day only, date..date+366
  time: string | null; // HH:mm; null = all day
  durationMinutes: number | null; // 1–10080, timed only
  timeZone: string | null; // IANA anchor zone, timed only; null = each phone's own clock
  repeat: CalendarRepeat; // plain string in the validator
  repeatUntil: string | null; // YYYY-MM-DD, inclusive
  reminders: number[]; // minutes before, 0..40320, ≤ 4, unique, ascending
  remindUserIds: ObjectId[]; // members whose phones remind (also a to-do's assignees)
  pinned: boolean;
  doneAt: Date | null; // to-dos only
  doneBy: ObjectId | null;
  source: CalendarSource | null;
  clientKey: string | null; // the creating phone's retry key (unique per couple while a string)
  version: number; // starts at 1, +1 per change (optimistic concurrency via expectedVersion)
  createdAt: Date;
  updatedAt: Date;
}

// --- Wake together (shared alarms) ---------------------------------------------------------

/** 'together': both phones ring at the same instant (the time in the alarm's anchor zone). */
export const ALARM_MODES = ['together'] as const;
export type AlarmMode = (typeof ALARM_MODES)[number];
export const WAKE_OUTCOMES = ['up', 'missed'] as const;
export type WakeOutcome = (typeof WAKE_OUTCOMES)[number];
/** What an alarm.updated changed: the shared rule, only `enabled`, or one member's own entry. */
export type AlarmChange = 'edited' | 'enabled' | 'member';

export interface AlarmWake {
  occurrence: Date; // the ring this is about
  outcome: WakeOutcome; // plain string in the validator
  at: Date; // when the person got up (or the alarm gave up)
  snoozes: number; // 0..20
}

/** One person's side of a shared alarm. Only that person changes it (PUT /alarms/:id/me, reports). */
export interface AlarmMember {
  userId: ObjectId;
  ring: boolean; // false: "not on my phone"
  skips: Date[]; // occurrences this person skips (recent and future only, at most 7)
  ready: boolean | null; // null: this phone never reported for this alarm (e.g. an older app)
  reason: string | null; // why not ready, a short code such as 'exact_alarms_off'
  armedFor: Date | null; // the next ring this phone has set
  reportedAt: Date | null;
  lastWake: AlarmWake | null;
}

/**
 * A "Wake together" alarm. The server only stores the rule; each phone rings its own copy. Every
 * occurrence is `time` on one of `days` (ISO weekdays) in the anchor `timeZone`, or the single
 * instant `onceAt` when `days` is empty, so both phones ring at the same moment.
 */
export interface AlarmDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  createdBy: ObjectId;
  updatedBy: ObjectId;
  mode: AlarmMode; // plain string in the validator
  label: string | null; // ≤ 40
  time: string; // HH:mm in timeZone
  timeZone: string; // IANA anchor zone (the creator's)
  days: number[]; // ISO weekdays 1–7, ascending, unique; [] = once
  onceAt: Date | null; // set exactly when days is empty
  enabled: boolean;
  snoozeMinutes: number; // 1–30
  version: number; // +1 per alarm.created/updated; wake and silent readiness reports keep it
  createdAt: Date;
  updatedAt: Date;
  members: AlarmMember[]; // both couple members
}

/** What one partner shows on the OTHER partner's home-screen widgets (one per author per couple). */
export interface WidgetBoardDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  authorId: ObjectId; // who wrote it; the partner is the audience
  note: string | null; // ≤ 140
  emoji: string | null; // 1–2 graphemes
  mood: string | null; // ≤ 40
  photoMediaId: ObjectId | null; // ready photo|wallpaper media of the same couple, not view-once
  createdAt: Date;
  updatedAt: Date;
}

/** An SOS alert to the partner. Pushed even through quiet hours / disabled notifications. */
export interface SosDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  senderId: ObjectId;
  recipientId: ObjectId;
  message: string | null; // ≤ 140; private content, never logged
  createdAt: Date;
  acknowledgedAt: Date | null;
  // Safety fields (added later). All optional: the live validator cannot change (no collMod) and
  // older documents lack them. Read missing as panic false and every date null.
  panic?: boolean; // stored only when true: a "panic" SOS that also shares live location
  deliveredAt?: Date | null; // the recipient's phone started ringing (POST /sos/:id/delivered)
  delivery?: SosDeliveryInfo | null; // device state reported with the delivered receipt (no content)
  resolvedAt?: Date | null; // the sender said "I'm safe now" (POST /sos/:id/resolve)
  idempotencyKey?: string; // client retry key (unique per sender when present)
  raisedEventId?: ObjectId; // the sos.raised event, set right after it is emitted (re-push)
  raisedSeq?: number;
  raiseLeaseUntil?: Date | null; // one request is emitting sos.raised until then (unset once raised)
  pushCount?: number; // re-pushes sent by SosRepushWorker (the outbox's first push is not counted)
  lastPushAt?: Date | null;
}

/** What the recipient's phone reported when the SOS started ringing. Device state only. */
export interface SosDeliveryInfo {
  priority: string | null; // FCM delivered priority (high / normal), null when not from a push
  originalPriority: string | null;
  latencyMs: number | null; // receive time minus FCM sent time
  alarmMuted: boolean | null; // AudioManager.isStreamMute(STREAM_ALARM) at ring time
  alarmVolume: number | null;
  ringerMode: string | null; // normal / vibrate / silent
  interruptionFilter: string | null; // all / priority / alarms / none
  notificationsEnabled: boolean | null;
  canUseFullScreenIntent: boolean | null;
  fgsStarted: boolean | null; // false: the foreground service was refused and a fallback rang
}

// --- Live location (safety) ----------------------------------------------------------------

export const LIVE_LOCATION_REASONS = ['manual', 'panic'] as const;
export type LiveLocationReason = (typeof LIVE_LOCATION_REASONS)[number];
export const LOCATION_STOP_REASONS = ['stopped', 'expired', 'resolved', 'signed_out'] as const;
export type LocationStopReason = (typeof LOCATION_STOP_REASONS)[number];

/** The sharer's latest position. Overwritten by every fix: there is never a history. */
export interface LocationFix {
  lat: number; // rounded to 5 decimals (~1 m)
  lng: number;
  accuracyM: number; // integer metres, 0..50000
  at: Date; // when the phone took the fix (server time minus the fix's age)
  batteryPct: number | null; // sharer's battery level, 0..100
}

/**
 * One live-sharing session. `_id` is the sessionId, generated by the phone so a stop can never be
 * overtaken by its own start. At most one `live` session per sharer per couple (partial unique
 * index). Stopping turns the session into a short-lived tombstone (`live: false`, `fix: null`,
 * purged within 10 minutes) so a late start with the same id is refused instead of recreating it.
 */
export interface LiveLocationDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  userId: ObjectId; // the sharer
  viewerId: ObjectId; // the partner (the only reader)
  reason: LiveLocationReason; // plain string in the validator (Zod is the allow-list)
  sosId: ObjectId | null; // the panic SOS this session belongs to
  live: boolean;
  startedAt: Date;
  expiresAt: Date | null; // null = until the sharer turns it off
  purgeAt: Date; // TTL index: expiry, idle purge, or tombstone end
  fix: LocationFix | null;
  lastSeenAt: Date; // last time the sharer's phone checked in (even with an unchanged fix)
  stoppedAt: Date | null;
  updatedAt: Date;
}

export type PushState = 'pending' | 'sent' | 'skipped' | 'failed';

export interface EventPush {
  state: PushState;
  attempts: number;
  leaseUntil: Date | null; // also used as "not before" for exponential backoff
  lastError: string | null;
  skipReason: string | null;
  sentAt: Date | null;
}

export interface EventDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  seq: number;
  type: EventType;
  actorId: ObjectId;
  payload: Record<string, unknown>;
  createdAt: Date;
  push: EventPush;
}

// --- Games (rock–paper–scissors with truth or dare) ---------------------------------------

export const GAME_KINDS = ['rps', 'tictactoe', 'trivia', 'this_or_that'] as const;
export type GameKind = (typeof GAME_KINDS)[number];
/** What the winner gets: the blind truth or dare (default), or nothing ("just for fun"). */
export const GAME_PRIZES = ['truth_or_dare', 'none'] as const;
export type GamePrize = (typeof GAME_PRIZES)[number];
export const TRIVIA_DIFFICULTIES = ['easy', 'medium', 'hard'] as const;
export type TriviaDifficulty = (typeof TRIVIA_DIFFICULTIES)[number];
export type TttMark = 'x' | 'o';
/** Rock (stone), paper, scissors, plus thread: thread beats rock and paper; scissors cut thread. */
export const RPS_MOVES = ['rock', 'paper', 'scissors', 'thread'] as const;
export type RpsMove = (typeof RPS_MOVES)[number];
export const GAME_STATUSES = [
  'waiting',
  'draw',
  'revealed',
  'challenged',
  'chosen',
  'awaiting_confirmation',
  'completed',
  'cancelled',
] as const;
export type GameStatus = (typeof GAME_STATUSES)[number];
export const CHALLENGE_KINDS = ['truth', 'dare'] as const;
export type ChallengeKind = (typeof CHALLENGE_KINDS)[number];

/**
 * The winner writes BOTH a truth and a dare; the loser picks one blind. Visibility is enforced by
 * the DTO builders (modules/shared/dto.ts): the loser only ever sees the text they chose, and
 * events never carry either text.
 */
export interface GameChallenge {
  truth: string; // 1–200
  dare: string; // 1–200
  by: ObjectId; // the winner
  at: Date;
  chosen: ChallengeKind | null; // set by the loser (POST /games/:id/choose)
  chosenAt: Date | null;
  doneAt: Date | null; // last POST /complete; cleared by a reject
  confirmedAt: Date | null; // winner confirmed (status completed)
  rejectNote: string | null; // winner's note on the last reject; cleared by the next complete
}

/**
 * Games live in their own collection (not as chat messages): the `messages` validator stored on the
 * live database has a strict `type` enum that a readWrite-only user cannot widen, so clients merge
 * `GET /games` / `game.updated` into the chat timeline by `createdAt`.
 */
export interface GameDoc {
  _id: ObjectId;
  coupleId: ObjectId;
  kind: GameKind;
  createdBy: ObjectId;
  memberIds: ObjectId[]; // the two players (couple members at creation)
  status: GameStatus;
  open: boolean; // status ∉ {completed, cancelled}; unique per couple while true
  round: number; // starts at 1; +1 per rematch after a draw
  moves: { userId: ObjectId; move: RpsMove; at: Date }[]; // current round only, ≤ 2
  winnerId: ObjectId | null;
  loserId: ObjectId | null;
  challenge: GameChallenge | null;
  answer: string | null; // the loser's answer (required for a truth); cleared by a reject
  createdAt: Date;
  updatedAt: Date;
  completedAt: Date | null;
  cancelledAt: Date | null;
  cancelledBy: ObjectId | null;
  // Optional on purpose: the live `games` validator cannot change (no collMod), but it sets no
  // `additionalProperties`, so new optional fields are accepted while every field above stays
  // required and is always written (new kinds write `moves: []`, `challenge: null`, ...).
  prize?: GamePrize; // absent on older games ⇒ 'truth_or_dare'
  ttt?: TttState | null; // kind 'tictactoe' only
  quiz?: QuizState | null; // kinds 'trivia' and 'this_or_that' only
}

/** Tic-tac-toe round state. `moves` cannot hold it: the live validator allows at most 2 moves. */
export interface TttState {
  board: (TttMark | null)[]; // 9 cells, index = row * 3 + col
  xId: ObjectId; // plays X this round and moves first (the invitee in round 1; swaps each rematch)
  turnId: ObjectId | null; // whose turn; null once the round is over
  moveCount: number; // 0..9; also the optimistic-concurrency guard for moves
  lastCell: number | null;
  line: number[] | null; // the three winning cells, or null
}

/** One trivia question or This-or-That prompt. */
export interface QuizItem {
  id: string; // trivia: 'otdb_' + sha256(text)[0..16] or 'tb_NN'; this_or_that: curated id ('ev-07')
  text: string | null; // trivia question; null for This or That (the two choices are the prompt)
  choices: string[]; // trivia: 4, already shuffled; this_or_that: 2
  correct: number | null; // trivia: index into choices; this_or_that: null
  category: string | null; // trivia: per-question category label
  difficulty: TriviaDifficulty | null;
}

export interface QuizPlayer {
  userId: ObjectId;
  picks: number[]; // a choice index per item, in order; length = answered
  score: number; // trivia: correct answers; this_or_that: 0
  finishedAt: Date | null;
}

/** Shared by Trivia duel and This or That: same items for both, picks sealed until both finish. */
export interface QuizState {
  source: 'opentdb' | 'builtin' | 'curated';
  category: { id: number; name: string } | null; // trivia: the category the questions came from
  // trivia: the category asked for; null = "any", so a rematch or "Play again" picks a curated one at
  // random again instead of repeating the one "any" resolved to. Absent on the first builds ⇒ category.id.
  requestedCategory?: number | null;
  difficulty: TriviaDifficulty | null; // trivia: as requested (null = any)
  deck: string | null; // this_or_that deck id
  items: QuizItem[]; // trivia 5, this_or_that 7
  players: QuizPlayer[]; // exactly 2, in memberIds order
  matches: number | null; // this_or_that: equal picks, set once both finished
}
