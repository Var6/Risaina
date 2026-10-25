package com.risaina.app.core.database

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
