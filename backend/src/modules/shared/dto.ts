import type {
  CoupleDoc,
  EventDoc,
  GameDoc,
  ListDoc,
  LiveLocationDoc,
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
  GameDto,
  GameEventViewDto,
  ListDto,
  LiveLocationDto,
  LiveLocationSessionDto,
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
    sharedPreferences: {
      ...c.sharedPreferences,
      chatWallpaper: c.chatWallpaper
        ? {
            style: c.chatWallpaper.style,
            photoMediaId: c.chatWallpaper.photoMediaId ? c.chatWallpaper.photoMediaId.toHexString() : null,
            setBy: c.chatWallpaper.setBy.toHexString(),
            setAt: iso(c.chatWallpaper.setAt),
          }
        : null,
    } as CoupleDto['sharedPreferences'], // index signature vs typed key: TS cannot express the mix
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
    panic: s.panic === true,
    deliveredAt: isoOrNull(s.deliveredAt ?? null),
    resolvedAt: isoOrNull(s.resolvedAt ?? null),
    delivery: s.delivery
      ? {
          alarmMuted: s.delivery.alarmMuted ?? null,
          fgsStarted: s.delivery.fgsStarted ?? null,
          notificationsEnabled: s.delivery.notificationsEnabled ?? null,
          interruptionFilter: s.delivery.interruptionFilter ?? null,
        }
      : null,
  };
}

export function toLiveLocationSessionDto(l: LiveLocationDoc): LiveLocationSessionDto {
  return {
    sessionId: l._id.toHexString(),
    userId: l.userId.toHexString(),
    reason: l.reason,
    sosId: l.sosId ? l.sosId.toHexString() : null,
    startedAt: iso(l.startedAt),
    expiresAt: isoOrNull(l.expiresAt),
    updatedAt: iso(l.updatedAt),
  };
}

export function toLiveLocationDto(l: LiveLocationDoc): LiveLocationDto {
  return {
    ...toLiveLocationSessionDto(l),
    lastSeenAt: iso(l.lastSeenAt),
    fix: l.fix
      ? {
          lat: l.fix.lat,
          lng: l.fix.lng,
          accuracyM: Math.round(l.fix.accuracyM),
          at: iso(l.fix.at),
          batteryPct: l.fix.batteryPct,
        }
      : null,
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

function gameCommon(g: GameDoc) {
  return {
    id: g._id.toHexString(),
    coupleId: g.coupleId.toHexString(),
    kind: g.kind,
    createdBy: g.createdBy.toHexString(),
    createdAt: iso(g.createdAt),
    updatedAt: iso(g.updatedAt),
    status: g.status,
    winnerId: g.winnerId ? g.winnerId.toHexString() : null,
    loserId: g.loserId ? g.loserId.toHexString() : null,
    completedAt: isoOrNull(g.completedAt),
    round: g.round,
  };
}

/** A round's moves are revealed only once both players moved. */
export function gameRevealed(g: GameDoc): boolean {
  return g.moves.length >= 2;
}

/** Statuses in which the loser's answer is shown (to both players). */
const ANSWER_VISIBLE: ReadonlySet<GameDoc['status']> = new Set([
  'awaiting_confirmation',
  'completed',
  'cancelled', // only non-null when cancelled while awaiting confirmation (a reject clears it)
]);

/**
 * Blind truth-or-dare: the winner always sees both texts; anyone else (the loser) sees neither
 * until they choose, then only the chosen one — the other text is never revealed to them.
 */
export function toGameChallengeDto(g: GameDoc, viewerId: string): GameDto['challenge'] {
  const c = g.challenge;
  if (!c) return null;
  const isWinner = g.winnerId !== null && g.winnerId.toHexString() === viewerId;
  return {
    // `?? null`: a game from the earlier single-challenge shape has no truth/dare fields
    truth: isWinner || c.chosen === 'truth' ? (c.truth ?? null) : null,
    dare: isWinner || c.chosen === 'dare' ? (c.dare ?? null) : null,
    chosen: c.chosen ?? null,
    by: c.by.toHexString(),
    at: iso(c.at),
    chosenAt: isoOrNull(c.chosenAt),
    doneAt: isoOrNull(c.doneAt),
    confirmedAt: isoOrNull(c.confirmedAt),
    rejectNote: c.rejectNote ?? null,
  };
}

/** Viewer-specific DTO: my move, whether my partner moved, and their move only once revealed. */
export function toGameDto(g: GameDoc, viewerId: string): GameDto {
  const mine = g.moves.find((m) => m.userId.toHexString() === viewerId);
  const theirs = g.moves.find((m) => m.userId.toHexString() !== viewerId);
  return {
    ...gameCommon(g),
    challenge: toGameChallengeDto(g, viewerId),
    answer: ANSWER_VISIBLE.has(g.status) ? g.answer : null,
    myMove: mine?.move ?? null,
    partnerMoved: theirs !== undefined,
    partnerMove: gameRevealed(g) && theirs ? theirs.move : null,
  };
}

/**
 * Viewer-neutral DTO for events: who moved, never which move until both moved, and never the
 * truth/dare texts or the answer (only whether a challenge exists and its progress timestamps).
 */
export function toGameEventView(g: GameDoc): GameEventViewDto {
  const c = g.challenge;
  return {
    ...gameCommon(g),
    challenge: c
      ? {
          chosen: c.chosen ?? null,
          hasChallenge: true,
          chosenAt: isoOrNull(c.chosenAt),
          doneAt: isoOrNull(c.doneAt),
          confirmedAt: isoOrNull(c.confirmedAt),
        }
      : null,
    movedBy: g.moves.map((m) => m.userId.toHexString()),
    moves: gameRevealed(g) ? g.moves.map((m) => ({ userId: m.userId.toHexString(), move: m.move })) : null,
  };
}
