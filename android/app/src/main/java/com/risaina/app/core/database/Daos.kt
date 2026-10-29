package com.risaina.app.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PingDao {
    @Query("SELECT * FROM pings ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<PingEntity>>

    @Query("SELECT * FROM pings WHERE id = :id")
    suspend fun byId(id: String): PingEntity?

    @Query("SELECT * FROM pings WHERE localKey = :key")
    suspend fun byLocalKey(key: String): PingEntity?

    @Query("SELECT * FROM pings WHERE senderId != :me ORDER BY createdAt DESC LIMIT 1")
    fun latestReceived(me: String): Flow<PingEntity?>

    @Upsert
    suspend fun upsert(ping: PingEntity)

    @Upsert
    suspend fun upsertAll(pings: List<PingEntity>)

    @Query("DELETE FROM pings WHERE id = :id")
    suspend fun delete(id: String)

    /** Replace a locally-keyed pending row with the server-assigned id. */
    @Transaction
    suspend fun replaceLocal(localKey: String, replacement: PingEntity) {
        deleteByLocalKey(localKey)
        upsert(replacement)
    }

    @Query("DELETE FROM pings WHERE localKey = :key")
    suspend fun deleteByLocalKey(key: String)

    @Query("DELETE FROM pings")
    suspend fun clear()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE deletedAt IS NULL ORDER BY createdAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE deletedAt IS NULL ORDER BY createdAt DESC LIMIT 1")
    fun latest(): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE localKey = :key")
    suspend fun byLocalKey(key: String): MessageEntity?

    @Query("SELECT MIN(createdAt) FROM messages")
    suspend fun oldestCreatedAt(): Long?

    @Query("SELECT COUNT(*) FROM messages WHERE senderId != :me AND readAt IS NULL AND deletedAt IS NULL")
    fun unreadCount(me: String): Flow<Int>

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Upsert
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Transaction
    suspend fun replaceLocal(localKey: String, replacement: MessageEntity) {
        deleteByLocalKey(localKey)
        upsert(replacement)
    }

    @Query("DELETE FROM messages WHERE localKey = :key")
    suspend fun deleteByLocalKey(key: String)

    @Query("UPDATE messages SET readAt = :readAt WHERE id = :id AND readAt IS NULL")
    suspend fun markRead(id: String, readAt: Long)

    @Query("UPDATE messages SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Long)

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun byId(id: String): MediaEntity?

    @Query("SELECT * FROM media WHERE id = :id")
    fun observe(id: String): Flow<MediaEntity?>

    @Upsert
    suspend fun upsert(media: MediaEntity)

    @Query("UPDATE media SET localPath = :path WHERE id = :id")
    suspend fun setLocalPath(id: String, path: String?)

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM media")
    suspend fun clear()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE deletedAt IS NULL ORDER BY pinned DESC, COALESCE(takenAt, createdAt) DESC")
    fun all(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE deletedAt IS NULL AND pinned = 1 ORDER BY createdAt DESC LIMIT 1")
    fun favourite(): Flow<MemoryEntity?>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun byId(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE id = :id")
    fun observe(id: String): Flow<MemoryEntity?>

    @Upsert
    suspend fun upsert(memory: MemoryEntity)

    @Upsert
    suspend fun upsertAll(memories: List<MemoryEntity>)

    @Query("UPDATE memories SET deletedAt = :at WHERE id = :id")
    suspend fun markDeleted(id: String, at: Long)

    @Query("DELETE FROM memories")
    suspend fun clear()
}

@Dao
interface MilestoneDao {
    @Query("SELECT * FROM milestones ORDER BY date ASC")
    fun all(): Flow<List<MilestoneEntity>>

    @Upsert
    suspend fun upsert(m: MilestoneEntity)

    @Upsert
    suspend fun upsertAll(m: List<MilestoneEntity>)

    @Query("DELETE FROM milestones WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM milestones")
    suspend fun clear()
}

@Dao
interface CoupleDao {
    @Query("SELECT * FROM couple LIMIT 1")
    fun observeCouple(): Flow<CoupleEntity?>

    @Query("SELECT * FROM partner LIMIT 1")
    fun observePartner(): Flow<PartnerEntity?>

    @Query("SELECT * FROM partner LIMIT 1")
    suspend fun partner(): PartnerEntity?

    @Query("SELECT * FROM couple LIMIT 1")
    suspend fun couple(): CoupleEntity?

    @Transaction
    suspend fun replace(couple: CoupleEntity?, partner: PartnerEntity?) {
        clearCouple(); clearPartner()
        if (couple != null) insertCouple(couple)
        if (partner != null) insertPartner(partner)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCouple(c: CoupleEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPartner(p: PartnerEntity)
    @Query("DELETE FROM couple") suspend fun clearCouple()
    @Query("DELETE FROM partner") suspend fun clearPartner()
}

@Dao
interface ListDao {
    @Query("SELECT * FROM lists ORDER BY pinned DESC, updatedAt DESC")
    fun all(): Flow<List<ListEntity>>

    @Query("SELECT * FROM lists WHERE id = :id")
    fun observe(id: String): Flow<ListEntity?>

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun byId(id: String): ListEntity?

    /** Writes unconditionally: use [upsertIfNewer] for anything that came from the server. */
    @Upsert suspend fun upsert(e: ListEntity)

    @Query("SELECT version FROM lists WHERE id = :id") suspend fun versionOf(id: String): Int?
    @Query("SELECT id FROM lists WHERE syncedAt < :before") suspend fun idsSyncedBefore(before: Long): List<String>

    /**
     * Writes [e] unless Room already holds a newer version. Replayed events (this phone's own older
     * saves come back after its newer HTTP reply) and a GET answered after a save carry older copies,
     * and writing them made a just-added item vanish. Returns whether it was written.
     */
    @Transaction
    suspend fun upsertIfNewer(e: ListEntity): Boolean {
        val current = versionOf(e.id)
        if (current != null && e.version < current) return false
        upsert(e)
        return true
    }

    suspend fun syncFromServer(items: List<ListEntity>, requestedAtMillis: Long) = syncFromServer(items, requestedAtMillis, emptySet())

    /**
     * Applies a GET /lists answer started at [requestedAtMillis] (this phone's clock). Each list is
     * written only if newer; a list missing from the answer is deleted only when it was written before
     * the GET started, because one created or saved while the GET was on its way is missing too.
     * [deletedMeanwhile]: lists deleted while the GET was on its way (here, or by a list.deleted
     * event). The answer still has them, and with no row left upsertIfNewer would bring them back.
     */
    @Transaction
    suspend fun syncFromServer(items: List<ListEntity>, requestedAtMillis: Long, deletedMeanwhile: Set<String>) {
        items.forEach { if (it.id !in deletedMeanwhile) upsertIfNewer(it) }
        val returned = items.mapTo(HashSet()) { it.id }
        idsSyncedBefore(requestedAtMillis).filterNot { it in returned }.forEach { delete(it) }
    }

    @Query("DELETE FROM lists WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM lists") suspend fun clear()
}

@Dao
interface ListDraftDao {
    @Query("SELECT * FROM list_drafts WHERE listId = :listId") suspend fun get(listId: String): ListDraftEntity?
    @Query("SELECT * FROM list_drafts WHERE listId = :listId") fun observe(listId: String): Flow<ListDraftEntity?>
    @Query("SELECT * FROM list_drafts ORDER BY editedAt ASC") suspend fun all(): List<ListDraftEntity>
    @Query("SELECT * FROM list_drafts ORDER BY editedAt ASC") fun observeAll(): Flow<List<ListDraftEntity>>
    @Upsert suspend fun upsert(e: ListDraftEntity)
    @Query("DELETE FROM list_drafts WHERE listId = :listId") suspend fun delete(listId: String)
    @Query("DELETE FROM list_drafts") suspend fun clear()
}

@Dao
interface CalendarDao {
    /** All-day entries (time NULL) sort before timed ones on the same date. */
    @Query("SELECT * FROM calendar_entries ORDER BY date ASC, time ASC")
    fun all(): Flow<List<CalendarEntryEntity>>

    @Query("SELECT * FROM calendar_entries WHERE id = :id") suspend fun byId(id: String): CalendarEntryEntity?
    @Query("SELECT * FROM calendar_entries WHERE id = :id") fun observe(id: String): Flow<CalendarEntryEntity?>

    /** Entries made from these messages (the chat's "In your calendar" chips). Keep [messageIds] under 900. */
    @Query("SELECT * FROM calendar_entries WHERE sourceMessageId IN (:messageIds) ORDER BY date ASC, time ASC")
    fun forMessages(messageIds: List<String>): Flow<List<CalendarEntryEntity>>

    /** Entries made from a note or checklist, or from one of its items. */
    @Query("SELECT * FROM calendar_entries WHERE sourceListId = :listId ORDER BY date ASC, time ASC")
    fun forList(listId: String): Flow<List<CalendarEntryEntity>>

    @Query("SELECT version FROM calendar_entries WHERE id = :id") suspend fun versionOf(id: String): Int?
    @Query("SELECT id FROM calendar_entries WHERE syncedAt < :before") suspend fun idsSyncedBefore(before: Long): List<String>

    /** Writes unconditionally: use [upsertIfNewer] for anything that came from the server. */
    @Upsert suspend fun upsert(e: CalendarEntryEntity)

    /** Writes [e] unless Room already holds a newer version (a replayed event, a slow GET). */
    @Transaction
    suspend fun upsertIfNewer(e: CalendarEntryEntity): Boolean {
        val current = versionOf(e.id)
        if (current != null && e.version < current) return false
        upsert(e)
        return true
    }

    /**
     * Applies a GET /calendar answer (every entry) started at [requestedAtMillis] (this phone's clock),
     * as ListDao.syncFromServer does: each entry is written only if newer, and an entry missing from the
     * answer is deleted only when it was written before the GET started, because one saved here or
     * received from the partner while the GET was on its way is missing too. Deleted one by one: up to
     * 1000 ids would not fit the 999 parameters older SQLite allows in NOT IN.
     */
    @Transaction
    suspend fun syncFromServer(items: List<CalendarEntryEntity>, requestedAtMillis: Long) {
        items.forEach { upsertIfNewer(it) }
        val returned = items.mapTo(HashSet()) { it.id }
        idsSyncedBefore(requestedAtMillis).filterNot { it in returned }.forEach { delete(it) }
    }

    /** An optimistic tick from a reminder's "Done" button; the server's reply (a newer version) replaces it. */
    @Query("UPDATE calendar_entries SET done = :done, doneAt = :doneAt, doneBy = :doneBy WHERE id = :id")
    suspend fun markDone(id: String, done: Boolean, doneAt: Long?, doneBy: String?)

    @Query("DELETE FROM calendar_entries WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM calendar_entries") suspend fun clear()
}

@Dao
interface ReminderLogDao {
    /** Claims a reminder key: the new row id, or -1 when it was already claimed (so already posted). */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertOrIgnore(e: ReminderLogEntity): Long
    @Query("DELETE FROM reminder_log WHERE firedAt < :olderThan") suspend fun prune(olderThan: Long)
    @Query("DELETE FROM reminder_log") suspend fun clear()
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    fun count(): Flow<Int>

    @Upsert suspend fun upsert(e: OutboxEntity)
    @Delete suspend fun delete(e: OutboxEntity)
    @Query("DELETE FROM outbox WHERE localKey = :key") suspend fun deleteByKey(key: String)
    @Query("DELETE FROM outbox") suspend fun clear()
}
