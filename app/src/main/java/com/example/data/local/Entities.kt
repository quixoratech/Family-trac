package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "location_records",
    indices = [
        Index(value = ["synced", "recordedAtMs"]),
        Index(value = ["deviceId", "recordedAtMs"])
    ]
)
data class LocationRecordEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val adminUid: String,
    val ownerUid: String,
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val speedMps: Float?,
    val batteryPercent: Int,
    val networkType: String,
    val source: String,
    val recordedAtMs: Long,
    val synced: Boolean = false,
    val syncAttempts: Int = 0,
    val lastError: String? = null
)

@Entity(tableName = "cached_devices")
data class CachedDeviceEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val adminUid: String,
    val ownerUid: String,
    val ownerName: String,
    val deviceName: String,
    val deviceModel: String,
    val role: String,
    val consentGranted: Boolean,
    val locationSharingEnabled: Boolean,
    val locationPermissionGranted: Boolean,
    val backgroundPermissionGranted: Boolean,
    val batteryPercent: Int,
    val networkType: String,
    val online: Boolean,
    val revoked: Boolean,
    val lastLatitude: Double?,
    val lastLongitude: Double?,
    val lastAccuracyMeters: Double?,
    val lastLocationAtMs: Long?,
    val lastSeenAtMs: Long,
    val lastSyncAtMs: Long
)

@Entity(
    tableName = "pending_messages",
    indices = [Index(value = ["synced", "clientCreatedAtMs"])]
)
data class PendingMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val familyId: String,
    val senderUid: String,
    val senderName: String,
    val participantUidsCsv: String,
    val type: String, // "TEXT", "VOICE", "IMAGE", "DOCUMENT"
    val text: String,
    val voiceFilePath: String? = null,
    val voiceDurationMs: Long = 0L,
    val mediaFilePath: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long = 0L,
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val clientCreatedAtMs: Long,
    val synced: Boolean = false,
    val syncAttempts: Int = 0,
    val lastError: String? = null
)

@Entity(
    tableName = "pending_game_moves",
    indices = [Index(value = ["synced", "createdAtMs"])]
)
data class PendingGameMoveEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val familyId: String,
    val playerUid: String,
    val symbol: String,
    val cellIndex: Int,
    val moveNumber: Int,
    val createdAtMs: Long,
    val synced: Boolean = false
)

@Entity(tableName = "consent_logs")
data class ConsentLogEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val familyId: String,
    val deviceId: String,
    val action: String, // "GRANTED", "PAUSED", "RESUMED", "REVOKED", "NETWORK_GRANTED", "NETWORK_PAUSED"
    val details: String,
    val timestampMs: Long
)

@Entity(tableName = "sync_status")
data class SyncStatusEntity(
    @PrimaryKey val key: String = "global",
    val lastSuccessfulSyncMs: Long = 0L,
    val lastSyncAttemptMs: Long = 0L,
    val lastError: String? = null
)

@Entity(tableName = "app_notifications")
data class AppNotificationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val type: String,
    val createdAtMs: Long,
    val seen: Boolean = false,
    val familyId: String = "",
    val category: String = "GENERAL"
)

@Entity(
    tableName = "network_events",
    indices = [
        Index(value = ["synced", "detectedAtMs"]),
        Index(value = ["deviceId", "detectedAtMs"])
    ]
)
data class NetworkEventEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val adminUid: String,
    val ownerUid: String,
    val deviceId: String,
    val deviceName: String,
    val eventType: String,
    val networkType: String,
    val connectionState: String,
    val internetAvailable: Boolean,
    val eventTimestampMs: Long,
    val detectedAtMs: Long,
    val timezoneId: String,
    val timezoneOffsetMinutes: Int,
    val sessionId: String? = null,
    val details: String? = null,
    val synced: Boolean = false,
    val syncAttempts: Int = 0,
    val lastError: String? = null
)

@Entity(
    tableName = "network_sessions",
    indices = [
        Index(value = ["synced", "startedAtMs"]),
        Index(value = ["deviceId", "startedAtMs"])
    ]
)
data class NetworkSessionEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val adminUid: String,
    val ownerUid: String,
    val deviceId: String,
    val deviceName: String,
    val networkType: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val durationMs: Long? = null,
    val status: String, // "ACTIVE", "COMPLETED", "INTERRUPTED", "UNKNOWN"
    val lastVerifiedAtMs: Long,
    val interruptionReason: String? = null,
    val timezoneId: String,
    val synced: Boolean = false
)
