package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class UserRole { ADMIN, FAMILY }
enum class NetworkKind { WIFI, CELLULAR, OFFLINE, OTHER }
enum class LocationSource { FUSED, GPS_PROVIDER, NETWORK_PROVIDER }
enum class MessageType { TEXT, VOICE, IMAGE, DOCUMENT }
enum class MessageStatus { SENDING, SENT, DELIVERED, READ, FAILED }
enum class CallStatus { RINGING, ACTIVE, ENDED, REJECTED, MISSED }
enum class GameStatus { WAITING, ACTIVE, X_WON, O_WON, DRAW }
enum class LastSeenPrivacy { EVERYONE, ADMIN_ONLY, NOBODY }
enum class UserAvailability { AVAILABLE, BUSY, AWAY, OFFLINE }
enum class TypingActivity { NONE, TYPING, RECORDING, SENDING }
enum class CalendarRecurrence { NONE, DAILY, WEEKLY, MONTHLY }
enum class TaskPriority { LOW, MEDIUM, HIGH }

enum class NetworkEventType {
    INTERNET_AVAILABLE,
    INTERNET_UNAVAILABLE,
    WIFI_AVAILABLE,
    WIFI_DISCONNECTED,
    CELLULAR_AVAILABLE,
    CELLULAR_DISCONNECTED,
    NETWORK_CHANGED,
    NETWORK_VALIDATED,
    SWITCHED_WIFI_TO_CELLULAR,
    SWITCHED_CELLULAR_TO_WIFI,
    SYNC_STARTED,
    SYNC_COMPLETED,
    SYNC_FAILED,
    MONITORING_STARTED,
    MONITORING_PAUSED,
    DEVICE_BOOTED
}

enum class NetworkSessionStatus { ACTIVE, COMPLETED, INTERRUPTED, UNKNOWN }

inline fun <reified T : Enum<T>> safeEnum(raw: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: fallback

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String? = null,
    val photoUrl: String? = null,
    val role: String = "FAMILY",
    val familyId: String? = null,
    val deviceId: String? = null,
    val language: String = "en",
    val lastSeenPrivacy: String = "EVERYONE",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FamilyGroup(
    @DocumentId val id: String = "",
    val adminUid: String = "",
    val adminEmail: String? = null,
    val name: String = "",
    val memberUids: List<String> = emptyList(),
    val lastPairingCode: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PairingCode(
    @DocumentId val id: String = "",
    val code: String = "",
    val familyId: String = "",
    val adminUid: String = "",
    val familyName: String = "",
    val status: String = "ACTIVE",
    val usedByUid: String? = null,
    val usedByDeviceId: String? = null,
    val expiresAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val usedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FamilyMember(
    @DocumentId val id: String = "",
    val uid: String = "",
    val familyId: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val role: String = "FAMILY",
    val deviceId: String = "",
    val online: Boolean = false,
    val lastSeenAtMs: Long = 0L,
    val lastActiveAtMs: Long = 0L,
    val lastSyncAtMs: Long = 0L,
    val lastSeenPrivacy: String = "EVERYONE",
    val availability: String = "AVAILABLE",
    val typingConversationId: String? = null,
    val typingActivity: String = "NONE",
    val typingUpdatedAtMs: Long = 0L,
    @ServerTimestamp val joinedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PairedDevice(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val adminUid: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val deviceName: String = "",
    val deviceModel: String = "",
    val androidVersion: String = "",
    val role: String = "FAMILY",
    val consentGranted: Boolean = false,
    @ServerTimestamp val consentTimestamp: Timestamp? = null,
    val networkConsentGranted: Boolean = false,
    val networkMonitoringEnabled: Boolean = false,
    val locationSharingEnabled: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    val backgroundPermissionGranted: Boolean = false,
    val batteryPercent: Long = 100L,
    val networkType: String = "OFFLINE",
    val currentWifiAvailable: Boolean = false,
    val currentCellularAvailable: Boolean = false,
    val internetAvailable: Boolean = false,
    val currentNetworkSessionId: String? = null,
    val currentSessionStartedAtMs: Long? = null,
    val lastNetworkEventType: String? = null,
    val lastNetworkEventAtMs: Long? = null,
    val deviceHealth: String = "GOOD",
    val online: Boolean = false,
    val revoked: Boolean = false,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val lastAccuracyMeters: Double? = null,
    val lastLocationAtMs: Long? = null,
    val lastSeenAtMs: Long = 0L,
    val lastActiveAtMs: Long = 0L,
    val lastSyncAtMs: Long = 0L,
    val pairedAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class LocationRecord(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val adminUid: String = "",
    val ownerUid: String = "",
    val deviceId: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Double = 0.0,
    val altitudeMeters: Double? = null,
    val speedMps: Double? = null,
    val batteryPercent: Long = 100L,
    val networkType: String = "WIFI",
    val source: String = "FUSED",
    val recordedAtMs: Long = 0L,
    @ServerTimestamp val syncedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class NetworkEventModel(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val adminUid: String = "",
    val ownerUid: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val eventType: String = "NETWORK_CHANGED",
    val networkType: String = "OFFLINE",
    val connectionState: String = "UNKNOWN",
    val internetAvailable: Boolean = false,
    val eventTimestampMs: Long = 0L,
    val detectedAtMs: Long = 0L,
    val timezoneId: String = "UTC",
    val timezoneOffsetMinutes: Long = 0L,
    val sessionId: String? = null,
    val details: String? = null,
    @ServerTimestamp val uploadedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class NetworkSessionModel(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val adminUid: String = "",
    val ownerUid: String = "",
    val deviceId: String = "",
    val deviceName: String = "",
    val networkType: String = "WIFI",
    val startedAtMs: Long = 0L,
    val endedAtMs: Long? = null,
    val durationMs: Long? = null,
    val status: String = "ACTIVE",
    val lastVerifiedAtMs: Long = 0L,
    val interruptionReason: String? = null,
    val timezoneId: String = "UTC",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Conversation(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val type: String = "DIRECT",
    val title: String = "",
    val participantUids: List<String> = emptyList(),
    val lastMessageText: String = "",
    val lastMessageType: String = "TEXT",
    val lastMessageSenderUid: String = "",
    val lastMessageAtMs: Long = 0L,
    val pinnedByUids: List<String> = emptyList(),
    val mutedByUids: List<String> = emptyList(),
    val archivedByUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val conversationId: String = "",
    val familyId: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val participantUids: List<String> = emptyList(),
    val type: String = "TEXT",
    val text: String = "",
    val voiceAudioBase64: String? = null,
    val voiceDurationMs: Long? = null,
    val mediaBase64: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long? = null,
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val reactions: Map<String, String> = emptyMap(),
    val editedAtMs: Long? = null,
    val deletedForAll: Boolean = false,
    val clientCreatedAtMs: Long = 0L,
    val status: String = "SENT",
    val deliveredToUids: List<String> = emptyList(),
    val readByUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CallSession(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val callerUid: String = "",
    val callerName: String = "",
    val calleeUid: String = "",
    val calleeName: String = "",
    val participantUids: List<String> = emptyList(),
    val status: String = "RINGING",
    val offerSdp: String? = null,
    val answerSdp: String? = null,
    val startedAtMs: Long = 0L,
    val endedAtMs: Long? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class IceCandidateModel(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val participantUids: List<String> = emptyList(),
    val sdpMid: String = "",
    val sdpMLineIndex: Long = 0L,
    val candidate: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class GameSession(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val playerXUid: String = "",
    val playerXName: String = "",
    val playerOUid: String = "",
    val playerOName: String = "",
    val participantUids: List<String> = emptyList(),
    val board: List<String> = List(9) { "" },
    val currentTurn: String = "X",
    val status: String = "ACTIVE",
    val winnerUid: String? = null,
    val moveCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class GameMove(
    @DocumentId val id: String = "",
    val gameId: String = "",
    val playerUid: String = "",
    val symbol: String = "X",
    val cellIndex: Long = 0L,
    val moveNumber: Long = 1L,
    val participantUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FamilyNotificationModel(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val targetUid: String = "",
    val senderUid: String = "",
    val title: String = "",
    val body: String = "",
    val type: String = "MESSAGE",
    val seen: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FamilyCalendarEvent(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val title: String = "",
    val description: String = "",
    val startAtMs: Long = 0L,
    val endAtMs: Long? = null,
    val recurrence: String = "NONE",
    val reminderMinutesBefore: Long = 30L,
    val participantUids: List<String> = emptyList(),
    val createdByUid: String = "",
    val createdByName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FamilyTask(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val title: String = "",
    val description: String = "",
    val assignedToUid: String = "",
    val assignedToName: String = "",
    val priority: String = "MEDIUM",
    val dueAtMs: Long? = null,
    val completed: Boolean = false,
    val completedAtMs: Long? = null,
    val completedByUid: String? = null,
    val createdByUid: String = "",
    val createdByName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FamilyExpense(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val title: String = "",
    val category: String = "Groceries",
    val amountBdt: Double = 0.0,
    val paidByUid: String = "",
    val paidByName: String = "",
    val expenseDateMs: Long = 0L,
    val notes: String = "",
    val createdByUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FamilyNotice(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val title: String = "",
    val body: String = "",
    val pinned: Boolean = false,
    val expiresAtMs: Long? = null,
    val readByUids: List<String> = emptyList(),
    val authorUid: String = "",
    val authorName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FamilyVaultFile(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val title: String = "",
    val folder: String = "General",
    val fileName: String = "",
    val mimeType: String = "application/octet-stream",
    val fileSizeBytes: Long = 0L,
    val fileBase64: String = "",
    val uploadedByUid: String = "",
    val uploadedByName: String = "",
    val allowedUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
