package com.risaina.app.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class User(
    val id: String,
    val displayName: String,
    val avatarMediaId: String?,
    val createdAt: Instant,
)

data class Partner(
    val id: String,
    val displayName: String,
    val avatarMediaId: String?,
    /** "YYYY-MM-DD", or "--MM-DD" when the year is private. */
    val birthday: String? = null,
    /** IANA zone, or null when the partner doesn't share their local time. */
    val timeZone: String? = null,
)

enum class CoupleStatus { PENDING, ACTIVE, ENDED }

data class Couple(
    val id: String,
    val status: CoupleStatus,
    val memberIds: List<String>,
    val relationshipStartDate: LocalDate?,
    val nextMeetingDate: LocalDate?,
    val nextMeetingLabel: String?,
    val createdAt: Instant,
)

enum class PingType(val wire: String, val label: String, val defaultEmoji: String, val defaultMessage: String) {
    THINKING_OF_YOU("thinking_of_you", "Thinking of you", "💭", "You crossed my mind."),
    MISS_YOU("miss_you", "Miss you", "🌙", "Missing you right now."),
    HUG("hug", "Hug", "🫂", "Sending a hug."),
    KISS("kiss", "Kiss", "💋", "A kiss, from here."),
    GOOD_MORNING("good_morning", "Good morning", "☀️", "Good morning, love."),
    GOODNIGHT("goodnight", "Goodnight", "✨", "Goodnight. Sleep well."),
    CUSTOM("custom", "Custom", "❤️", "");

    companion object {
        fun fromWire(value: String): PingType = entries.firstOrNull { it.wire == value } ?: CUSTOM
    }
}

/**
 * Local lifecycle for a ping as seen by the *sender*. Only [PENDING] and [FAILED] exist purely
 * on-device; the rest mirror what the server has recorded with evidence.
 */
enum class PingStatus { PENDING, ACCEPTED, DELIVERED, SEEN, FAILED, EXPIRED }

data class Ping(
    val id: String,                // server id, or local id while pending
    val localKey: String,          // idempotency key (UUID)
    val coupleId: String?,
    val senderId: String,
    val recipientId: String,
    val type: PingType,
    val emoji: String,
    val message: String?,
    val status: PingStatus,
    val createdAt: Instant,
    val deliveredAt: Instant?,
    val seenAt: Instant?,
    val failureReason: String? = null,
)

enum class MessageType(val wire: String) {
    TEXT("text"), EMOJI("emoji"), PHOTO("photo"), VOICE("voice");
    companion object { fun fromWire(v: String) = entries.first { it.wire == v } }
}

enum class MessageSendState { PENDING, SENT, FAILED }

data class Message(
    val id: String,
    val localKey: String,
    val coupleId: String?,
    val senderId: String,
    val recipientId: String,
    val type: MessageType,
    val text: String?,
    val mediaId: String?,
    val createdAt: Instant,
    val editedAt: Instant?,
    val deletedAt: Instant?,
    val readAt: Instant?,
    val sendState: MessageSendState,
    val localMediaPath: String? = null,
    val uploadProgress: Float? = null,
)

enum class MediaCategory(val wire: String) {
    PHOTO("photo"), VOICE("voice"), AVATAR("avatar"), WALLPAPER("wallpaper");
    companion object { fun fromWire(v: String) = entries.first { it.wire == v } }
}

enum class MediaStatus { PENDING, READY, DELETED }

data class Media(
    val id: String,
    val coupleId: String,
    val ownerId: String,
    val category: MediaCategory,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val status: MediaStatus,
    val createdAt: Instant,
)

data class Memory(
    val id: String,
    val coupleId: String,
    val createdBy: String,
    val mediaId: String,
    val caption: String?,
    val takenAt: Instant?,
    val pinned: Boolean,
    val createdAt: Instant,
    val deletedAt: Instant?,
)

enum class MilestoneKind(val wire: String) {
    ANNIVERSARY("anniversary"), MEETING("meeting"), CUSTOM("custom");
    companion object { fun fromWire(v: String) = entries.firstOrNull { it.wire == v } ?: CUSTOM }
}

data class Milestone(
    val id: String,
    val coupleId: String,
    val createdBy: String,
    val title: String,
    val date: LocalDate,
    val kind: MilestoneKind,
    val createdAt: Instant,
)

data class QuietHours(
    val enabled: Boolean,
    val start: LocalTime,
    val end: LocalTime,
    val zone: ZoneId,
)

data class WidgetPrivacy(
    val showPhoto: Boolean = true,
    val showNoteText: Boolean = true,
    val showEmoji: Boolean = true,
)

data class Preferences(
    val notificationsEnabled: Boolean = true,
    val previewInNotifications: Boolean = true,
    val quietHours: QuietHours = QuietHours(false, LocalTime.of(22, 30), LocalTime.of(7, 30), ZoneId.systemDefault()),
    val allowPushInQuietHours: Boolean = false,
    val pingCooldownSeconds: Int = 15,
    val widgetPrivacy: WidgetPrivacy = WidgetPrivacy(),
    val reducedMotion: Boolean = false,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val ritualsEnabled: Boolean = false,
    val morningRitualTime: LocalTime = LocalTime.of(8, 0),
    val nightRitualTime: LocalTime = LocalTime.of(22, 0),
    val accent: String = "ROSEWOOD",
    val background: String = "GLOW",
    val shareLocalTime: Boolean = true,
)

enum class DarkMode { SYSTEM, LIGHT, DARK }

data class DomainEvent(
    val id: String,
    val coupleId: String,
    val seq: Long,
    val type: String,
    val actorId: String,
    val payloadJson: String,
    val createdAt: Instant,
)

data class Session(
    val userId: String,
    val accessToken: String,
    val accessExpiresAt: Instant,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
    val deviceId: String,
)

data class Profile(
    val userId: String, val displayName: String, val avatarMediaId: String?, val emailVerified: Boolean = true,
    val birthday: String? = null, val hasPassword: Boolean = true,
)

data class InvitationCreated(val id: String, val code: String, val expiresAt: Instant)
data class InvitationSummary(val id: String, val expiresAt: Instant, val createdAt: Instant)
