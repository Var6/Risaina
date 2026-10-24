package com.risaina.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        PingEntity::class, MessageEntity::class, MediaEntity::class, MemoryEntity::class,
        MilestoneEntity::class, PartnerEntity::class, CoupleEntity::class, OutboxEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class RisainaDatabase : RoomDatabase() {
    abstract fun pingDao(): PingDao
    abstract fun messageDao(): MessageDao
    abstract fun mediaDao(): MediaDao
    abstract fun memoryDao(): MemoryDao
    abstract fun milestoneDao(): MilestoneDao
    abstract fun coupleDao(): CoupleDao
    abstract fun outboxDao(): OutboxDao

    /** Wipe all relationship data: used on sign-out, unlink, and account deletion. */
    suspend fun clearAllContent() = withTransaction {
        pingDao().clear(); messageDao().clear(); mediaDao().clear(); memoryDao().clear()
        milestoneDao().clear(); coupleDao().clearCouple(); coupleDao().clearPartner(); outboxDao().clear()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): RisainaDatabase =
        Room.databaseBuilder(context, RisainaDatabase::class.java, "risaina.db")
            .fallbackToDestructiveMigration(dropAllTables = true) // pre-1.0: cache only, server is the source of truth
            .build()

    @Provides fun pingDao(db: RisainaDatabase) = db.pingDao()
    @Provides fun messageDao(db: RisainaDatabase) = db.messageDao()
    @Provides fun mediaDao(db: RisainaDatabase) = db.mediaDao()
    @Provides fun memoryDao(db: RisainaDatabase) = db.memoryDao()
    @Provides fun milestoneDao(db: RisainaDatabase) = db.milestoneDao()
    @Provides fun coupleDao(db: RisainaDatabase) = db.coupleDao()
    @Provides fun outboxDao(db: RisainaDatabase) = db.outboxDao()
}
