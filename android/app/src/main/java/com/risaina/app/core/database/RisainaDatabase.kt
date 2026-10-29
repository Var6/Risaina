package com.risaina.app.core.database

import android.content.Context
import androidx.room.AutoMigration
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

/**
 * Version 6 is the first real migration (5 → 6, automatic: three new tables and three list columns
 * with SQL defaults). Before it every schema change wiped the database, and with it the outbox of
 * unsent messages and now the unsent list drafts. Only versions before 5 still fall back to that.
 */
@Database(
    entities = [
        PingEntity::class, MessageEntity::class, MediaEntity::class, MemoryEntity::class,
        MilestoneEntity::class, PartnerEntity::class, CoupleEntity::class, OutboxEntity::class, ListEntity::class,
        ListDraftEntity::class, CalendarEntryEntity::class, ReminderLogEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 5, to = 6)],
)
abstract class RisainaDatabase : RoomDatabase() {
    abstract fun pingDao(): PingDao
    abstract fun messageDao(): MessageDao
    abstract fun mediaDao(): MediaDao
    abstract fun memoryDao(): MemoryDao
    abstract fun milestoneDao(): MilestoneDao
    abstract fun coupleDao(): CoupleDao
    abstract fun outboxDao(): OutboxDao
    abstract fun listDao(): ListDao
    abstract fun listDraftDao(): ListDraftDao
    abstract fun calendarDao(): CalendarDao
    abstract fun reminderLogDao(): ReminderLogDao

    /** Wipe all relationship data: used on sign-out, unlink, and account deletion. */
    suspend fun clearAllContent() = withTransaction {
        pingDao().clear(); messageDao().clear(); mediaDao().clear(); memoryDao().clear()
        milestoneDao().clear(); coupleDao().clearCouple(); coupleDao().clearPartner(); outboxDao().clear(); listDao().clear()
        // Unsent list edits must never reach a later session or another couple; posted-reminder keys go with the entries.
        listDraftDao().clear(); calendarDao().clear(); reminderLogDao().clear()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): RisainaDatabase =
        Room.databaseBuilder(context, RisainaDatabase::class.java, "risaina.db")
            // Only for a schema with no migration path to it: versions before 5 (and a downgrade).
            // 5 → 6 runs the AutoMigration above, so the outbox and drafts survive the update.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun pingDao(db: RisainaDatabase) = db.pingDao()
    @Provides fun messageDao(db: RisainaDatabase) = db.messageDao()
    @Provides fun mediaDao(db: RisainaDatabase) = db.mediaDao()
    @Provides fun memoryDao(db: RisainaDatabase) = db.memoryDao()
    @Provides fun milestoneDao(db: RisainaDatabase) = db.milestoneDao()
    @Provides fun coupleDao(db: RisainaDatabase) = db.coupleDao()
    @Provides fun outboxDao(db: RisainaDatabase) = db.outboxDao()
    @Provides fun listDao(db: RisainaDatabase) = db.listDao()
    @Provides fun listDraftDao(db: RisainaDatabase) = db.listDraftDao()
    @Provides fun calendarDao(db: RisainaDatabase) = db.calendarDao()
    @Provides fun reminderLogDao(db: RisainaDatabase) = db.reminderLogDao()
}
