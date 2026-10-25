package com.risaina.app.core.data

import com.risaina.app.core.database.CoupleEntity
import com.risaina.app.core.database.MediaEntity
import com.risaina.app.core.database.MemoryEntity
import com.risaina.app.core.database.MessageEntity
import com.risaina.app.core.database.MilestoneEntity
import com.risaina.app.core.database.PartnerEntity
import com.risaina.app.core.database.PingEntity
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
import com.risaina.app.core.model.WidgetPrivacy
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

fun String.toInstant(): Instant = Instant.parse(this)
fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }
fun String?.toLocalDateOrNull(): LocalDate? = this?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
fun Long?.toInstantOrNull(): Instant? = this?.let(Instant::ofEpochMilli)

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
fun CoupleDto.toEntity() = CoupleEntity(id, status, memberIds.joinToString(","), relationshipStartDate, nextMeetingDate, nextMeetingLabel, createdAt.toInstant().toEpochMilli())
fun CoupleEntity.toDomain() = Couple(
    id = id,
    status = runCatching { CoupleStatus.valueOf(status.uppercase()) }.getOrDefault(CoupleStatus.PENDING),
    memberIds = memberIds.split(",").filter { it.isNotBlank() },
    relationshipStartDate = relationshipStartDate.toLocalDateOrNull(),
    nextMeetingDate = nextMeetingDate.toLocalDateOrNull(),
    nextMeetingLabel = nextMeetingLabel,
    createdAt = Instant.ofEpochMilli(createdAt),
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
