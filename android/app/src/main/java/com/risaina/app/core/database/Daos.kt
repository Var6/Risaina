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

    @Upsert suspend fun upsert(e: ListEntity)

    @Transaction
    suspend fun replaceAll(items: List<ListEntity>) { clear(); items.forEach { upsert(it) } }

    @Query("DELETE FROM lists WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM lists") suspend fun clear()
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
