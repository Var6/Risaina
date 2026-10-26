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
] as const;
export type EventType = (typeof EVENT_TYPES)[number];

export const LIST_KINDS = ['note', 'checklist'] as const;
export type ListKind = (typeof LIST_KINDS)[number];

export interface ListItem {
  id: string; // 12-char random, server-assigned when the client omits it
  text: string;
  done: boolean;
  doneBy: ObjectId | null;
  doneAt: Date | null;
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

export const GAME_KINDS = ['rps'] as const;
export type GameKind = (typeof GAME_KINDS)[number];
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
}
