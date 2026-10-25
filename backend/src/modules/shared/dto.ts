import type {
  CoupleDoc,
  EventDoc,
  ListDoc,
  MediaDoc,
  MemoryDoc,
  MessageDoc,
  MilestoneDoc,
  PingDoc,
  Preferences,
  SosDoc,
  UserDoc,
  WidgetBoardDoc,
} from '../../db/types.js';
import { iso, isoOrNull } from '../../lib/time.js';
import type {
  CoupleDto,
  EventDto,
  ListDto,
  MediaDto,
  MemoryDto,
  MessageDto,
  MilestoneDto,
  PartnerDto,
  PingDto,
  PreferencesDto,
  SosDto,
  UserDto,
  WidgetBoardDto,
} from './schemas.js';

export type {
  CoupleDto,
  EventDto,
  ListDto,
  MediaDto,
  MemoryDto,
  MessageDto,
  MilestoneDto,
  PartnerDto,
  PingDto,
  PreferencesDto,
  SosDto,
  UserDto,
  WidgetBoardDto,
};

export function toUserDto(u: UserDoc): UserDto {
  return {
    id: u._id.toHexString(),
    displayName: u.displayName,
    avatarMediaId: u.avatarMediaId ? u.avatarMediaId.toHexString() : null,
    createdAt: iso(u.createdAt),
    emailVerified: u.emailVerifiedAt instanceof Date,
    hasPassword: typeof u.passwordHash === 'string' && u.passwordHash.length > 0,
    birthday: u.birthday ?? null,
    timeZone: u.timeZone ?? null,
    shareLocalTime: u.shareLocalTime ?? true,
    city: u.city ? { ...u.city } : null,
    shareWeather: u.shareWeather ?? true,
  };
}

/** What the partner may see. timeZone is withheld when the user turned shareLocalTime off. */
export function toPartnerDto(u: UserDoc): PartnerDto {
  return {
    id: u._id.toHexString(),
    displayName: u.displayName,
    avatarMediaId: u.avatarMediaId ? u.avatarMediaId.toHexString() : null,
    birthday: u.birthday ?? null,
    timeZone: (u.shareLocalTime ?? true) ? (u.timeZone ?? null) : null,
    city: (u.shareWeather ?? true) && u.city ? { ...u.city } : null,
  };
}

export function toListDto(l: ListDoc): ListDto {
  return {
    id: l._id.toHexString(),
    coupleId: l.coupleId.toHexString(),
    createdBy: l.createdBy.toHexString(),
    updatedBy: l.updatedBy.toHexString(),
    title: l.title,
    kind: l.kind,
    body: l.body,
    items: l.items.map((i) => ({
      id: i.id,
      text: i.text,
      done: i.done,
      doneBy: i.doneBy ? i.doneBy.toHexString() : null,
      doneAt: isoOrNull(i.doneAt),
    })),
    pinned: l.pinned,
    version: l.version,
    createdAt: iso(l.createdAt),
    updatedAt: iso(l.updatedAt),
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
    viewOnce: m.viewOnce ?? false,
    openedAt: isoOrNull(m.openedAt),
    viewOnceExpired: m.viewOnceExpiredAt instanceof Date,
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
    sourceMessageId: m.sourceMessageId ? m.sourceMessageId.toHexString() : null,
  };
}

export function toWidgetBoardDto(b: WidgetBoardDoc): WidgetBoardDto {
  return {
    authorId: b.authorId.toHexString(),
    note: b.note,
    emoji: b.emoji,
    mood: b.mood,
    photoMediaId: b.photoMediaId ? b.photoMediaId.toHexString() : null,
    updatedAt: iso(b.updatedAt),
  };
}

export function toSosDto(s: SosDoc): SosDto {
  return {
    id: s._id.toHexString(),
    coupleId: s.coupleId.toHexString(),
    senderId: s.senderId.toHexString(),
    recipientId: s.recipientId.toHexString(),
    message: s.message,
    createdAt: iso(s.createdAt),
    acknowledgedAt: isoOrNull(s.acknowledgedAt),
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
