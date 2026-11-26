package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LocationRecordEntity::class,
        CachedDeviceEntity::class,
        PendingMessageEntity::class,
        PendingGameMoveEntity::class,
        ConsentLogEntity::class,
        SyncStatusEntity::class,
        AppNotificationEntity::class,
        NetworkEventEntity::class,
        NetworkSessionEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class FamilyTrackDatabase : RoomDatabase() {
    abstract fun locationRecordDao(): LocationRecordDao
    abstract fun cachedDeviceDao(): CachedDeviceDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun pendingGameMoveDao(): PendingGameMoveDao
    abstract fun consentLogDao(): ConsentLogDao
    abstract fun syncStatusDao(): SyncStatusDao
    abstract fun appNotificationDao(): AppNotificationDao
    abstract fun networkEventDao(): NetworkEventDao
    abstract fun networkSessionDao(): NetworkSessionDao

    companion object {
        @Volatile
        private var INSTANCE: FamilyTrackDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Non-destructive column additions for existing pending_messages
                db.execSQL("ALTER TABLE pending_messages ADD COLUMN mediaFilePath TEXT")
                db.execSQL("ALTER TABLE pending_messages ADD COLUMN fileName TEXT")
                db.execSQL("ALTER TABLE pending_messages ADD COLUMN mimeType TEXT")
                db.execSQL("ALTER TABLE pending_messages ADD COLUMN fileSizeBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_messages ADD COLUMN replyToMessageId TEXT")
                db.execSQL("ALTER TABLE pending_messages ADD COLUMN replyToPreview TEXT")

                // Non-destructive column additions for existing app_notifications
                db.execSQL("ALTER TABLE app_notifications ADD COLUMN familyId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_notifications ADD COLUMN category TEXT NOT NULL DEFAULT 'GENERAL'")

                // New table: network_events
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `network_events` (
                        `id` TEXT NOT NULL,
                        `familyId` TEXT NOT NULL,
                        `adminUid` TEXT NOT NULL,
                        `ownerUid` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `deviceName` TEXT NOT NULL,
                        `eventType` TEXT NOT NULL,
                        `networkType` TEXT NOT NULL,
                        `connectionState` TEXT NOT NULL,
                        `internetAvailable` INTEGER NOT NULL,
                        `eventTimestampMs` INTEGER NOT NULL,
                        `detectedAtMs` INTEGER NOT NULL,
                        `timezoneId` TEXT NOT NULL,
                        `timezoneOffsetMinutes` INTEGER NOT NULL,
                        `sessionId` TEXT,
                        `details` TEXT,
                        `synced` INTEGER NOT NULL,
                        `syncAttempts` INTEGER NOT NULL,
                        `lastError` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_events_synced_detectedAtMs` ON `network_events` (`synced`, `detectedAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_events_deviceId_detectedAtMs` ON `network_events` (`deviceId`, `detectedAtMs`)")

                // New table: network_sessions
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `network_sessions` (
                        `id` TEXT NOT NULL,
                        `familyId` TEXT NOT NULL,
                        `adminUid` TEXT NOT NULL,
                        `ownerUid` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `deviceName` TEXT NOT NULL,
                        `networkType` TEXT NOT NULL,
                        `startedAtMs` INTEGER NOT NULL,
                        `endedAtMs` INTEGER,
                        `durationMs` INTEGER,
                        `status` TEXT NOT NULL,
                        `lastVerifiedAtMs` INTEGER NOT NULL,
                        `interruptionReason` TEXT,
                        `timezoneId` TEXT NOT NULL,
                        `synced` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_sessions_synced_startedAtMs` ON `network_sessions` (`synced`, `startedAtMs`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_network_sessions_deviceId_startedAtMs` ON `network_sessions` (`deviceId`, `startedAtMs`)")
            }
        }

        fun getInstance(context: Context): FamilyTrackDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FamilyTrackDatabase::class.java,
                    "familytrack_bd.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
