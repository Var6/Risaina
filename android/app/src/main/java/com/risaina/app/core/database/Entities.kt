package com.risaina.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "pings", indices = [Index("createdAt"), Index(value = ["localKey"], unique = true)])
data class PingEntity(
    @PrimaryKey val id: String,
    val localKey: String,
    val coupleId: String?,
    val senderId: String,
    val recipientId: String,
    val type: String,
    val emoji: String,
    val message: String?,
    val status: String,
    val createdAt: Long,
    val deliveredAt: Long?,
    val seenAt: Long?,
    val failureReason: String?,
)

@Entity(tableName = "messages", indices = [Index("createdAt"), Index(value = ["localKey"], unique = true)])
data class MessageEntity(
    @PrimaryKey val id: String,
    val localKey: String,
    val coupleId: String?,
    val senderId: String,
    val recipientId: String,
    val type: String,
    val text: String?,
    val mediaId: String?,
    val createdAt: Long,
    val editedAt: Long?,
    val deletedAt: Long?,
    val readAt: Long?,
    val sendState: String,
    val localMediaPath: String?,
    val viewOnce: Boolean = false,
    val openedAt: Long? = null,
    val viewOnceExpired: Boolean = false,
)

@Entity(tableName = "media")
data class MediaEntity(
    @PrimaryKey val id: String,
    val coupleId: String,
    val ownerId: String,
    val category: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val status: String,
    val createdAt: Long,
    /** Local cached file, if downloaded. */
    val localPath: String?,
)

@Entity(tableName = "memories", indices = [Index("createdAt"), Index("pinned")])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val coupleId: String,
    val createdBy: String,
    val mediaId: String,
    val caption: String?,
    val takenAt: Long?,
    val pinned: Boolean,
    val createdAt: Long,
    val deletedAt: Long?,
)

@Entity(tableName = "milestones", indices = [Index("date")])
data class MilestoneEntity(
    @PrimaryKey val id: String,
    val coupleId: String,
    val createdBy: String,
    val title: String,
    /** ISO local date. */
    val date: String,
    val kind: String,
    val createdAt: Long,
)

@Entity(tableName = "partner")
data class PartnerEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val avatarMediaId: String?,
    val birthday: String? = null,
    val timeZone: String? = null,
    val cityName: String? = null,
    val cityLat: Double? = null,
    val cityLon: Double? = null,
)

/** Shared note or checklist; items are stored as JSON (they are always edited as a whole). */
@Entity(tableName = "lists", indices = [Index("updatedAt")])
data class ListEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: String,
    val body: String,
    val itemsJson: String,
    val pinned: Boolean,
    val version: Int,
    val createdBy: String,
    val updatedBy: String?,
    val createdAt: Long,
    val updatedAt: Long,
    // Added in version 6. The SQL defaults let the 5 → 6 migration add them to existing rows.
    @ColumnInfo(defaultValue = "none") val marker: String = "none",
    @ColumnInfo(defaultValue = "1") val checkboxes: Boolean = true,
    /**
     * This phone's clock when the row was last written from the server. ListDao.syncFromServer only
     * deletes rows written before its GET started, so a list created meanwhile survives.
     */
    @ColumnInfo(defaultValue = "0") val syncedAt: Long = 0,
)

/**
 * An edit to a shared list that the server has not confirmed yet, kept so nothing typed is lost to a
 * failed save, a conflict or the app closing. [baseJson] is the server copy the edit started from (the
 * base of a three-way merge), [mineJson] what the editor shows now.
 */
@Entity(tableName = "list_drafts")
data class ListDraftEntity(
    @PrimaryKey val listId: String,
    val baseVersion: Int,
    val baseJson: String,
    val mineJson: String,
    val editedAt: Long,
    /** pending | sending | failed | orphaned (the list was deleted while this was unsent) | dropped (a new list deleted before it was saved, until the overview says so). */
    val state: String,
    val lastError: String?,
    val attempts: Int,
)

/**
 * A shared calendar entry, flat. [reminders] (minutes) and [remindUserIds] are comma separated;
 * dates are YYYY-MM-DD and times HH:mm as on the wire; kind and repeat are the wire strings.
 */
@Entity(tableName = "calendar_entries", indices = [Index("date"), Index("sourceMessageId"), Index("sourceListId")])
data class CalendarEntryEntity(
    @PrimaryKey val id: String,
    val coupleId: String,
    val createdBy: String,
    val updatedBy: String?,
    val kind: String,
    val title: String,
    val notes: String?,
    val emoji: String?,
    val date: String,
    val endDate: String?,
    val time: String?,
    val durationMinutes: Int?,
    val timeZone: String?,
    val repeat: String,
    val repeatUntil: String?,
    val reminders: String,
    val remindUserIds: String,
    val pinned: Boolean,
    val done: Boolean,
    val doneAt: Long?,
    val doneBy: String?,
    val sourceKind: String?,
    val sourceMessageId: String?,
    val sourceListId: String?,
    val sourceItemId: String?,
    val clientKey: String?,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Reminders this phone has posted, keyed "{entry or occasion}|{occurrence date}|{offset}". Inserting
 * a key claims it, so the alarm, the worker and app start never post the same reminder twice.
 */
@Entity(tableName = "reminder_log")
data class ReminderLogEntity(
    @PrimaryKey val key: String,
    val firedAt: Long,
)

@Entity(tableName = "couple")
data class CoupleEntity(
    @PrimaryKey val id: String,
    val status: String,
    val memberIds: String, // comma separated
    val relationshipStartDate: String?,
    val nextMeetingDate: String?,
    val nextMeetingLabel: String?,
    val createdAt: Long,
    /** Shared by both partners: chat wallpaper style and optional photo. */
    val chatWallpaperStyle: String? = null,
    val chatWallpaperMediaId: String? = null,
)

/** Operations queued while offline. Drained in order by SyncWorker. */
@Entity(tableName = "outbox", indices = [Index("createdAt")])
data class OutboxEntity(
    @PrimaryKey val localKey: String,
    val kind: String,         // "message" | "memory" | ...
    val payloadJson: String,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Long,
)
