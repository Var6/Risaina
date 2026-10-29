package com.risaina.app.core.data

import com.risaina.app.core.database.CalendarEntryEntity
import com.risaina.app.core.database.CoupleEntity
import com.risaina.app.core.database.MediaEntity
import com.risaina.app.core.database.MemoryEntity
import com.risaina.app.core.database.MessageEntity
import com.risaina.app.core.database.MilestoneEntity
import com.risaina.app.core.database.PartnerEntity
import com.risaina.app.core.database.PingEntity
import com.risaina.app.core.model.AlarmMember
import com.risaina.app.core.model.AlarmWake
import com.risaina.app.core.model.CalendarEntry
import com.risaina.app.core.model.CalendarKind
import com.risaina.app.core.model.CalendarRepeat
import com.risaina.app.core.model.CalendarSource
import com.risaina.app.core.model.CalendarSourceKind
import com.risaina.app.core.model.Couple
import com.risaina.app.core.model.CoupleStatus
import com.risaina.app.core.model.Media
import com.risaina.app.core.model.MediaCategory
import com.risaina.app.core.model.MediaStatus
import com.risaina.app.core.model.Memory
import com.risaina.app.core.model.Message
import com.risaina.app.core.model.MessageSendState
import com.risaina.app.core.model.MessageType
import com.risaina.app.core.model.Milestone
import com.risaina.app.core.model.MilestoneKind
import com.risaina.app.core.model.Partner
import com.risaina.app.core.model.Ping
import com.risaina.app.core.model.PingStatus
import com.risaina.app.core.model.PingType
import com.risaina.app.core.model.Preferences
import com.risaina.app.core.model.QuietHours
import com.risaina.app.core.model.User
import com.risaina.app.core.model.WakeAlarm
import com.risaina.app.core.model.WakeMode
import com.risaina.app.core.model.WakeOutcome
import com.risaina.app.core.model.WidgetPrivacy
import com.risaina.app.core.network.AlarmDto
import com.risaina.app.core.network.AlarmMemberDto
import com.risaina.app.core.network.AlarmWakeDto
import com.risaina.app.core.network.CalendarEntryDto
import com.risaina.app.core.network.CalendarSourceDto
import com.risaina.app.core.network.CoupleDto
import com.risaina.app.core.network.MediaDto
import com.risaina.app.core.network.MemoryDto
import com.risaina.app.core.network.MessageDto
import com.risaina.app.core.network.MilestoneDto
import com.risaina.app.core.network.PartnerDto
import com.risaina.app.core.network.PingDto
import com.risaina.app.core.network.PreferencesDto
import com.risaina.app.core.network.QuietHoursDto
import com.risaina.app.core.network.UserDto
import com.risaina.app.core.network.WidgetPrivacyDto
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun String.toInstant(): Instant = Instant.parse(this)
fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }
fun String?.toLocalDateOrNull(): LocalDate? = this?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
/** The wire's time of day ("HH:mm"; seconds, if ever sent, are dropped), or null. */
fun String?.toLocalTimeOrNull(): LocalTime? = this?.let { runCatching { LocalTime.parse(it).withSecond(0).withNano(0) }.getOrNull() }
fun Long?.toInstantOrNull(): Instant? = this?.let(Instant::ofEpochMilli)

/** Times of day on the wire: always two-digit hours and minutes, never seconds. */
val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

// ---- users / couple ----
fun UserDto.toDomain() = User(id, displayName, avatarMediaId, createdAt.toInstant())
fun PartnerDto.toDomain() = Partner(id, displayName, avatarMediaId, birthday, timeZone, city?.let { com.risaina.app.core.model.City(it.name, it.latitude, it.longitude) })
fun PartnerDto.toEntity() = PartnerEntity(id, displayName, avatarMediaId, birthday, timeZone, city?.name, city?.latitude, city?.longitude)
fun PartnerEntity.toDomain() = Partner(id, displayName, avatarMediaId, birthday, timeZone, if (cityName != null && cityLat != null && cityLon != null) com.risaina.app.core.model.City(cityName, cityLat, cityLon) else null)

fun CoupleDto.toDomain() = Couple(
    id = id,
    status = runCatching { CoupleStatus.valueOf(status.uppercase()) }.getOrDefault(CoupleStatus.PENDING),
    memberIds = memberIds,
    relationshipStartDate = relationshipStartDate.toLocalDateOrNull(),
    nextMeetingDate = nextMeetingDate.toLocalDateOrNull(),
    nextMeetingLabel = nextMeetingLabel,
    createdAt = createdAt.toInstant(),
)
fun CoupleDto.toEntity(): CoupleEntity {
    val wp = (sharedPreferences?.get("chatWallpaper") as? kotlinx.serialization.json.JsonObject)
    fun str(k: String) = (wp?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    return CoupleEntity(id, status, memberIds.joinToString(","), relationshipStartDate, nextMeetingDate, nextMeetingLabel, createdAt.toInstant().toEpochMilli(), str("style"), str("photoMediaId"))
}
fun CoupleEntity.toDomain() = Couple(
    id = id,
    status = runCatching { CoupleStatus.valueOf(status.uppercase()) }.getOrDefault(CoupleStatus.PENDING),
    memberIds = memberIds.split(",").filter { it.isNotBlank() },
    relationshipStartDate = relationshipStartDate.toLocalDateOrNull(),
    nextMeetingDate = nextMeetingDate.toLocalDateOrNull(),
    nextMeetingLabel = nextMeetingLabel,
    createdAt = Instant.ofEpochMilli(createdAt),
    chatWallpaperStyle = chatWallpaperStyle,
    chatWallpaperMediaId = chatWallpaperMediaId,
)

// ---- preferences ----
fun PreferencesDto.toDomain(local: Preferences): Preferences = local.copy(
    notificationsEnabled = notificationsEnabled,
    previewInNotifications = previewInNotifications,
    quietHours = quietHours?.let {
        QuietHours(it.enabled, LocalTime.parse(it.start), LocalTime.parse(it.end), runCatching { ZoneId.of(it.timeZone) }.getOrDefault(ZoneId.systemDefault()))
    } ?: local.quietHours,
    allowPushInQuietHours = allowPushInQuietHours,
    pingCooldownSeconds = pingCooldownSeconds,
    widgetPrivacy = widgetPrivacy?.let { WidgetPrivacy(it.showPhoto, it.showNoteText, it.showEmoji) } ?: local.widgetPrivacy,
    reducedMotion = reducedMotion,
)
fun Preferences.toDto() = PreferencesDto(
    notificationsEnabled = notificationsEnabled,
    previewInNotifications = previewInNotifications,
    quietHours = QuietHoursDto(quietHours.enabled, quietHours.start.toString(), quietHours.end.toString(), quietHours.zone.id),
    allowPushInQuietHours = allowPushInQuietHours,
    pingCooldownSeconds = pingCooldownSeconds,
    widgetPrivacy = WidgetPrivacyDto(widgetPrivacy.showPhoto, widgetPrivacy.showNoteText, widgetPrivacy.showEmoji),
    reducedMotion = reducedMotion,
)

// ---- pings ----
fun PingDto.toEntity() = PingEntity(
    id = id, localKey = idempotencyKey, coupleId = coupleId, senderId = senderId, recipientId = recipientId,
    type = type, emoji = emoji, message = message, status = status.uppercase(),
    createdAt = createdAt.toInstant().toEpochMilli(),
    deliveredAt = deliveredAt.toInstantOrNull()?.toEpochMilli(), seenAt = seenAt.toInstantOrNull()?.toEpochMilli(),
    failureReason = null,
)
fun PingEntity.toDomain() = Ping(
    id = id, localKey = localKey, coupleId = coupleId, senderId = senderId, recipientId = recipientId,
    type = PingType.fromWire(type), emoji = emoji, message = message,
    status = runCatching { PingStatus.valueOf(status) }.getOrDefault(PingStatus.ACCEPTED),
    createdAt = Instant.ofEpochMilli(createdAt), deliveredAt = deliveredAt.toInstantOrNull(), seenAt = seenAt.toInstantOrNull(),
    failureReason = failureReason,
)

// ---- messages ----
fun MessageDto.toEntity(localMediaPath: String? = null) = MessageEntity(
    id = id, localKey = idempotencyKey, coupleId = coupleId, senderId = senderId, recipientId = recipientId,
    type = type, text = text, mediaId = mediaId, createdAt = createdAt.toInstant().toEpochMilli(),
    editedAt = editedAt.toInstantOrNull()?.toEpochMilli(), deletedAt = deletedAt.toInstantOrNull()?.toEpochMilli(),
    readAt = readAt.toInstantOrNull()?.toEpochMilli(), sendState = MessageSendState.SENT.name, localMediaPath = localMediaPath,
    viewOnce = viewOnce, openedAt = openedAt.toInstantOrNull()?.toEpochMilli(), viewOnceExpired = viewOnceExpired,
)
fun MessageEntity.toDomain() = Message(
    id = id, localKey = localKey, coupleId = coupleId, senderId = senderId, recipientId = recipientId,
    type = MessageType.fromWire(type), text = text, mediaId = mediaId, createdAt = Instant.ofEpochMilli(createdAt),
    editedAt = editedAt.toInstantOrNull(), deletedAt = deletedAt.toInstantOrNull(), readAt = readAt.toInstantOrNull(),
    sendState = runCatching { MessageSendState.valueOf(sendState) }.getOrDefault(MessageSendState.SENT),
    localMediaPath = localMediaPath,
    viewOnce = viewOnce, openedAt = openedAt.toInstantOrNull(), viewOnceExpired = viewOnceExpired,
)

// ---- media ----
fun MediaDto.toEntity(localPath: String? = null) = MediaEntity(
    id, coupleId, ownerId, category, mimeType, sizeBytes, durationMs, width, height, status.uppercase(), createdAt.toInstant().toEpochMilli(), localPath,
)
fun MediaEntity.toDomain() = Media(
    id, coupleId, ownerId, MediaCategory.fromWire(category), mimeType, sizeBytes, durationMs, width, height,
    runCatching { MediaStatus.valueOf(status) }.getOrDefault(MediaStatus.PENDING), Instant.ofEpochMilli(createdAt),
)

// ---- memories / milestones ----
fun MemoryDto.toEntity() = MemoryEntity(
    id, coupleId, createdBy, mediaId, caption, takenAt.toInstantOrNull()?.toEpochMilli(), pinned,
    createdAt.toInstant().toEpochMilli(), deletedAt.toInstantOrNull()?.toEpochMilli(),
)
fun MemoryEntity.toDomain() = Memory(id, coupleId, createdBy, mediaId, caption, takenAt.toInstantOrNull(), pinned, Instant.ofEpochMilli(createdAt), deletedAt.toInstantOrNull())

fun MilestoneDto.toEntity() = MilestoneEntity(id, coupleId, createdBy, title, date.take(10), kind, createdAt.toInstant().toEpochMilli())
fun MilestoneEntity.toDomain() = Milestone(id, coupleId, createdBy, title, LocalDate.parse(date), MilestoneKind.fromWire(kind), Instant.ofEpochMilli(createdAt))

// ---- calendar ----
// Kind, repeat and source kind stay wire strings until the domain mapping, where an unknown value
// becomes UNKNOWN: an entry from a newer app is listed, never dropped and never a crash.
fun CalendarSourceDto.toDomain() = CalendarSource(CalendarSourceKind.fromWire(kind), messageId, listId, itemId)
fun CalendarSource.toDto() = CalendarSourceDto(kind.wire, messageId, listId, itemId)

fun CalendarEntryDto.toEntity() = CalendarEntryEntity(
    id = id, coupleId = coupleId, createdBy = createdBy, updatedBy = updatedBy, kind = kind, title = title,
    notes = notes, emoji = emoji, date = date, endDate = endDate, time = time, durationMinutes = durationMinutes,
    timeZone = timeZone, repeat = repeat, repeatUntil = repeatUntil,
    reminders = reminders.joinToString(","), remindUserIds = remindUserIds.joinToString(","), pinned = pinned,
    done = done, doneAt = doneAt.toInstantOrNull()?.toEpochMilli(), doneBy = doneBy,
    sourceKind = source?.kind, sourceMessageId = source?.messageId, sourceListId = source?.listId, sourceItemId = source?.itemId,
    clientKey = clientKey, version = version,
    createdAt = createdAt.toInstantOrNull()?.toEpochMilli() ?: 0L, updatedAt = updatedAt.toInstantOrNull()?.toEpochMilli() ?: 0L,
)

/** Null only for an entry with no readable date, which cannot be placed anywhere: it is skipped. */
fun CalendarEntryEntity.toDomain(): CalendarEntry? {
    val day = date.toLocalDateOrNull() ?: return null
    return CalendarEntry(
        id = id, coupleId = coupleId, createdBy = createdBy, updatedBy = updatedBy,
        kind = CalendarKind.fromWire(kind), title = title, notes = notes, emoji = emoji,
        date = day, endDate = endDate.toLocalDateOrNull(), time = time.toLocalTimeOrNull(), durationMinutes = durationMinutes,
        timeZone = timeZone, repeat = CalendarRepeat.fromWire(repeat), repeatUntil = repeatUntil.toLocalDateOrNull(),
        reminders = reminders.split(',').mapNotNull { it.trim().toIntOrNull() }.distinct().sorted(),
        remindUserIds = remindUserIds.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        pinned = pinned, done = done, doneAt = doneAt.toInstantOrNull(), doneBy = doneBy,
        source = sourceKind?.let { CalendarSource(CalendarSourceKind.fromWire(it), sourceMessageId, sourceListId, sourceItemId) },
        clientKey = clientKey, version = version, createdAt = Instant.ofEpochMilli(createdAt), updatedAt = Instant.ofEpochMilli(updatedAt),
    )
}

fun CalendarEntryDto.toDomain(): CalendarEntry? = toEntity().toDomain()

/**
 * Back to a row, e.g. for an optimistic tick before the server confirms it. An UNKNOWN kind or
 * repeat is written as "" and reads back as UNKNOWN again; such an entry is never sent to the server.
 */
fun CalendarEntry.toEntity() = CalendarEntryEntity(
    id = id, coupleId = coupleId, createdBy = createdBy, updatedBy = updatedBy, kind = kind.wire, title = title,
    notes = notes, emoji = emoji, date = date.toString(), endDate = endDate?.toString(), time = time?.format(HH_MM),
    durationMinutes = durationMinutes, timeZone = timeZone, repeat = repeat.wire, repeatUntil = repeatUntil?.toString(),
    reminders = reminders.joinToString(","), remindUserIds = remindUserIds.joinToString(","), pinned = pinned,
    done = done, doneAt = doneAt?.toEpochMilli(), doneBy = doneBy,
    sourceKind = source?.kind?.wire, sourceMessageId = source?.messageId, sourceListId = source?.listId, sourceItemId = source?.itemId,
    clientKey = clientKey, version = version, createdAt = createdAt.toEpochMilli(), updatedAt = updatedAt.toEpochMilli(),
)

// ---- wake together ----
fun AlarmWakeDto.toDomain(): AlarmWake? {
    val occ = occurrence.toInstantOrNull() ?: return null
    val woke = at.toInstantOrNull() ?: return null
    return AlarmWake(occ, WakeOutcome.fromWire(outcome), woke, snoozes)
}

fun AlarmMemberDto.toDomain() = AlarmMember(
    userId = userId, ring = ring, skips = skips.mapNotNull { it.toInstantOrNull() }, ready = ready, reason = reason,
    armedFor = armedFor.toInstantOrNull(), reportedAt = reportedAt.toInstantOrNull(), lastWake = lastWake?.toDomain(),
)

/** Null only when the ring time is unreadable: such an alarm could never ring right, so it is left out. */
fun AlarmDto.toDomain(): WakeAlarm? {
    val at = time.toLocalTimeOrNull() ?: return null
    return WakeAlarm(
        id = id, coupleId = coupleId, createdBy = createdBy, updatedBy = updatedBy, mode = WakeMode.fromWire(mode),
        label = label, time = at, timeZone = timeZone, days = days.filter { it in 1..7 }.distinct().sorted(),
        onceAt = onceAt.toInstantOrNull(), enabled = enabled, snoozeMinutes = snoozeMinutes, version = version,
        createdAt = createdAt.toInstantOrNull() ?: Instant.EPOCH, updatedAt = updatedAt.toInstantOrNull() ?: Instant.EPOCH,
        members = members.map { it.toDomain() },
    )
}

/** For keeping alarms as JSON on the phone (WakeAlarmStore). An UNKNOWN mode is written as "" and reads back as UNKNOWN. */
fun WakeAlarm.toDto() = AlarmDto(
    id = id, coupleId = coupleId, createdBy = createdBy, updatedBy = updatedBy, mode = mode.wire, label = label,
    time = time.format(HH_MM), timeZone = timeZone, days = days, onceAt = onceAt?.toString(), enabled = enabled,
    snoozeMinutes = snoozeMinutes, version = version, createdAt = createdAt.toString(), updatedAt = updatedAt.toString(),
    members = members.map { m ->
        AlarmMemberDto(
            userId = m.userId, ring = m.ring, skips = m.skips.map { it.toString() }, ready = m.ready, reason = m.reason,
            armedFor = m.armedFor?.toString(), reportedAt = m.reportedAt?.toString(),
            lastWake = m.lastWake?.let { AlarmWakeDto(it.occurrence.toString(), it.outcome.wire, it.at.toString(), it.snoozes) },
        )
    },
)
