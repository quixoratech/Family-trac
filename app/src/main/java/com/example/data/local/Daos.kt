package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecord(record: LocationRecordEntity)

    @Query("SELECT * FROM location_records WHERE synced = 0 ORDER BY recordedAtMs ASC LIMIT 100")
    suspend fun getPendingBatch(): List<LocationRecordEntity>

    @Query("SELECT COUNT(*) FROM location_records WHERE synced = 0")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM location_records ORDER BY recordedAtMs DESC LIMIT 200")
    fun observeLocalHistory(): Flow<List<LocationRecordEntity>>

    @Query("SELECT * FROM location_records WHERE deviceId = :deviceId ORDER BY recordedAtMs DESC LIMIT 1")
    suspend fun getLatestForDevice(deviceId: String): LocationRecordEntity?

    @Query("UPDATE location_records SET synced = 1, lastError = NULL WHERE id = :recordId")
    suspend fun markSynced(recordId: String)

    @Query("UPDATE location_records SET syncAttempts = syncAttempts + 1, lastError = :error WHERE id = :recordId")
    suspend fun markSyncFailed(recordId: String, error: String?)

    // Only delete ALREADY SYNCED local records older than 7 days; never delete unsynchronized records!
    @Query("DELETE FROM location_records WHERE synced = 1 AND recordedAtMs < :cutoffMs")
    suspend fun pruneSyncedOlderThan(cutoffMs: Long)
}

@Dao
interface CachedDeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(devices: List<CachedDeviceEntity>)

    @Query("SELECT * FROM cached_devices WHERE familyId = :familyId ORDER BY lastSeenAtMs DESC")
    fun observeDevicesForFamily(familyId: String): Flow<List<CachedDeviceEntity>>

    @Query("DELETE FROM cached_devices")
    suspend fun clearAll()
}

@Dao
interface PendingMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPending(message: PendingMessageEntity)

    @Query("SELECT * FROM pending_messages WHERE synced = 0 ORDER BY clientCreatedAtMs ASC")
    suspend fun getUnsyncedMessages(): List<PendingMessageEntity>

    @Query("SELECT * FROM pending_messages WHERE conversationId = :conversationId AND synced = 0 ORDER BY clientCreatedAtMs ASC")
    fun observePendingForConversation(conversationId: String): Flow<List<PendingMessageEntity>>

    @Query("SELECT COUNT(*) FROM pending_messages WHERE synced = 0")
    fun observePendingMessageCount(): Flow<Int>

    @Query("UPDATE pending_messages SET synced = 1, lastError = NULL WHERE id = :messageId")
    suspend fun markSynced(messageId: String)

    @Query("UPDATE pending_messages SET syncAttempts = syncAttempts + 1, lastError = :error WHERE id = :messageId")
    suspend fun markFailed(messageId: String, error: String?)

    @Query("UPDATE pending_messages SET lastError = NULL WHERE id = :messageId")
    suspend fun clearErrorForRetry(messageId: String)

    @Query("DELETE FROM pending_messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("DELETE FROM pending_messages WHERE synced = 1 AND clientCreatedAtMs < :cutoffMs")
    suspend fun pruneOldSynced(cutoffMs: Long)
}

@Dao
interface PendingGameMoveDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMove(move: PendingGameMoveEntity)

    @Query("SELECT * FROM pending_game_moves WHERE synced = 0 ORDER BY createdAtMs ASC")
    suspend fun getUnsyncedMoves(): List<PendingGameMoveEntity>

    @Query("SELECT COUNT(*) FROM pending_game_moves WHERE synced = 0")
    fun observePendingMoveCount(): Flow<Int>

    @Query("UPDATE pending_game_moves SET synced = 1 WHERE id = :moveId")
    suspend fun markSynced(moveId: String)
}

@Dao
interface ConsentLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ConsentLogEntity)

    @Query("SELECT * FROM consent_logs ORDER BY timestampMs DESC LIMIT 100")
    fun observeLogs(): Flow<List<ConsentLogEntity>>
}

@Dao
interface SyncStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(status: SyncStatusEntity)

    @Query("SELECT * FROM sync_status WHERE key = 'global' LIMIT 1")
    fun observeStatus(): Flow<SyncStatusEntity?>
}

@Dao
interface AppNotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: AppNotificationEntity)

    @Query("SELECT * FROM app_notifications ORDER BY createdAtMs DESC LIMIT 100")
    fun observeAll(): Flow<List<AppNotificationEntity>>

    @Query("UPDATE app_notifications SET seen = 1 WHERE id = :id")
    suspend fun markSeen(id: String)

    @Query("UPDATE app_notifications SET seen = 1")
    suspend fun markAllSeen()
}

@Dao
interface NetworkEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: NetworkEventEntity)

    @Query("SELECT * FROM network_events WHERE synced = 0 ORDER BY detectedAtMs ASC LIMIT 100")
    suspend fun getPendingBatch(): List<NetworkEventEntity>

    @Query("SELECT * FROM network_events WHERE deviceId = :deviceId ORDER BY detectedAtMs DESC LIMIT 1")
    suspend fun getLatestForDevice(deviceId: String): NetworkEventEntity?

    @Query("SELECT * FROM network_events ORDER BY detectedAtMs DESC LIMIT 300")
    fun observeLocalEvents(): Flow<List<NetworkEventEntity>>

    @Query("SELECT COUNT(*) FROM network_events WHERE synced = 0")
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE network_events SET synced = 1, lastError = NULL WHERE id = :eventId")
    suspend fun markSynced(eventId: String)

    @Query("UPDATE network_events SET syncAttempts = syncAttempts + 1, lastError = :error WHERE id = :eventId")
    suspend fun markSyncFailed(eventId: String, error: String?)

    @Query("DELETE FROM network_events WHERE synced = 1 AND detectedAtMs < :cutoffMs")
    suspend fun pruneSyncedOlderThan(cutoffMs: Long)
}

@Dao
interface NetworkSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: NetworkSessionEntity)

    @Query("SELECT * FROM network_sessions WHERE deviceId = :deviceId AND status = 'ACTIVE' ORDER BY startedAtMs DESC")
    suspend fun getOpenActiveSessions(deviceId: String): List<NetworkSessionEntity>

    @Query("SELECT * FROM network_sessions WHERE synced = 0 ORDER BY startedAtMs ASC LIMIT 50")
    suspend fun getPendingSyncSessions(): List<NetworkSessionEntity>

    @Query("SELECT * FROM network_sessions ORDER BY startedAtMs DESC LIMIT 200")
    fun observeLocalSessions(): Flow<List<NetworkSessionEntity>>

    @Query("UPDATE network_sessions SET synced = 1 WHERE id = :sessionId")
    suspend fun markSynced(sessionId: String)

    @Query("DELETE FROM network_sessions WHERE synced = 1 AND status != 'ACTIVE' AND startedAtMs < :cutoffMs")
    suspend fun pruneOldSynced(cutoffMs: Long)
}
