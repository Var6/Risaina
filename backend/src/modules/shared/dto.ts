import type {
  CoupleDoc,
  EventDoc,
  MediaDoc,
  MemoryDoc,
  MessageDoc,
  MilestoneDoc,
  PingDoc,
  Preferences,
  UserDoc,
} from '../../db/types.js';
import { iso, isoOrNull } from '../../lib/time.js';
import type {
  CoupleDto,
  EventDto,
  MediaDto,
  MemoryDto,
  MessageDto,
  MilestoneDto,
  PartnerDto,
  PingDto,
  PreferencesDto,
  UserDto,
} from './schemas.js';

export type {
  CoupleDto,
  EventDto,
  MediaDto,
  MemoryDto,
  MessageDto,
  MilestoneDto,
  PartnerDto,
  PingDto,
  PreferencesDto,
  UserDto,
};

export function toUserDto(u: UserDoc): UserDto {
  return {
    id: u._id.toHexString(),
    displayName: u.displayName,
    avatarMediaId: u.avatarMediaId ? u.avatarMediaId.toHexString() : null,
    createdAt: iso(u.createdAt),
  };
}

export function toPartnerDto(u: UserDoc): PartnerDto {
  return {
    id: u._id.toHexString(),
    displayName: u.displayName,
    avatarMediaId: u.avatarMediaId ? u.avatarMediaId.toHexString() : null,
  };
}

export function toPreferencesDto(p: Preferences): PreferencesDto {
  return {
    notificationsEnabled: p.notificationsEnabled,
    previewInNotifications: p.previewInNotifications,
    quietHours: { ...p.quietHours },
    allowPushInQuietHours: p.allowPushInQuietHours,
    pingCooldownSeconds: p.pingCooldownSeconds,
    widgetPrivacy: { ...p.widgetPrivacy },
    reducedMotion: p.reducedMotion,
  };
}

export function toCoupleDto(c: CoupleDoc): CoupleDto {
  return {
    id: c._id.toHexString(),
    status: c.status,
    memberIds: c.memberIds.map((m) => m.toHexString()),
    relationshipStartDate: c.relationshipStartDate,
    nextMeetingDate: isoOrNull(c.nextMeetingDate),
    nextMeetingLabel: c.nextMeetingLabel,
    sharedPreferences: { ...c.sharedPreferences },
    createdAt: iso(c.createdAt),
  };
}

export function toPingDto(p: PingDoc): PingDto {
  return {
    id: p._id.toHexString(),
    coupleId: p.coupleId.toHexString(),
    senderId: p.senderId.toHexString(),
    recipientId: p.recipientId.toHexString(),
    type: p.type,
    emoji: p.emoji,
    message: p.message,
    status: p.status,
    createdAt: iso(p.createdAt),
    deliveredAt: isoOrNull(p.deliveredAt),
    seenAt: isoOrNull(p.seenAt),
    idempotencyKey: p.idempotencyKey,
  };
}

export function toMessageDto(m: MessageDoc): MessageDto {
  return {
    id: m._id.toHexString(),
    coupleId: m.coupleId.toHexString(),
    senderId: m.senderId.toHexString(),
    recipientId: m.recipientId.toHexString(),
    type: m.type,
    text: m.text,
    mediaId: m.mediaId ? m.mediaId.toHexString() : null,
    createdAt: iso(m.createdAt),
    editedAt: isoOrNull(m.editedAt),
    deletedAt: isoOrNull(m.deletedAt),
    readAt: isoOrNull(m.readAt),
    idempotencyKey: m.idempotencyKey,
  };
}

export function toMediaDto(m: MediaDoc): MediaDto {
  return {
    id: m._id.toHexString(),
    coupleId: m.coupleId.toHexString(),
    ownerId: m.ownerId.toHexString(),
    category: m.category,
    mimeType: m.mimeType,
    sizeBytes: m.sizeBytes,
    durationMs: m.durationMs,
    width: m.width,
    height: m.height,
    status: m.status,
    createdAt: iso(m.createdAt),
  };
}

export function toMemoryDto(m: MemoryDoc): MemoryDto {
  return {
    id: m._id.toHexString(),
    coupleId: m.coupleId.toHexString(),
    createdBy: m.createdBy.toHexString(),
    mediaId: m.mediaId.toHexString(),
    caption: m.caption,
    takenAt: isoOrNull(m.takenAt),
    pinned: m.pinned,
    createdAt: iso(m.createdAt),
    deletedAt: isoOrNull(m.deletedAt),
  };
}

export function toMilestoneDto(m: MilestoneDoc): MilestoneDto {
  return {
    id: m._id.toHexString(),
    coupleId: m.coupleId.toHexString(),
    createdBy: m.createdBy.toHexString(),
    title: m.title,
    date: m.date,
    kind: m.kind,
    visibility: 'shared',
    createdAt: iso(m.createdAt),
  };
}

export function toEventDto(e: EventDoc): EventDto {
  return {
    id: e._id.toHexString(),
    coupleId: e.coupleId.toHexString(),
    seq: e.seq,
    type: e.type,
    actorId: e.actorId.toHexString(),
    payload: e.payload,
    createdAt: iso(e.createdAt),
  };
}
