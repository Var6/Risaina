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
  createdAt: Date;
  updatedAt: Date;
  activatedAt: Date | null;
  endedAt: Date | null;
  endedBy: ObjectId | null;
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
