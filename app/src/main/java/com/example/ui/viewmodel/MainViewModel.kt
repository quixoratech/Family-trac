package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppNotificationEntity
import com.example.data.local.AppPreferences
import com.example.data.local.CachedDeviceEntity
import com.example.data.local.ConsentLogEntity
import com.example.data.local.FamilyTrackDatabase
import com.example.data.local.LocalAppSettings
import com.example.data.local.LocationRecordEntity
import com.example.data.local.PendingMessageEntity
import com.example.data.local.SyncStatusEntity
import com.example.data.model.CallSession
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.FamilyCalendarEvent
import com.example.data.model.FamilyExpense
import com.example.data.model.FamilyGroup
import com.example.data.model.FamilyMember
import com.example.data.model.FamilyNotice
import com.example.data.model.FamilyNotificationModel
import com.example.data.model.FamilyTask
import com.example.data.model.FamilyVaultFile
import com.example.data.model.GameSession
import com.example.data.model.LocationRecord
import com.example.data.model.NetworkEventModel
import com.example.data.model.NetworkSessionModel
import com.example.data.model.PairedDevice
import com.example.data.model.PairingCode
import com.example.data.model.UserProfile
import com.example.data.remote.CommunicationRepository
import com.example.data.remote.FamilyHubRepository
import com.example.data.remote.FamilyRepository
import com.example.data.remote.unwrapFirestoreError
import com.example.service.LocationCaptureHelper
import com.example.service.LocationForegroundService
import com.example.service.MediaAndExportHelper
import com.example.service.NearbyCommunicationManager
import com.example.service.NearbyPacket
import com.example.service.NearbyPeer
import com.example.service.NetworkActivityMonitor
import com.example.service.NotificationHelper
import com.example.service.QrDiagnosticLogger
import com.example.service.QrDiagnosticStage
import com.example.service.SyncScheduler
import com.example.service.VoiceRecorderPlayer
import com.example.service.WebRtcCallManager
import com.example.service.WebRtcCallState
import com.example.ui.theme.ThemePreference
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

sealed interface PairingCodeUiState {
    data object Idle : PairingCodeUiState
    data object Generating : PairingCodeUiState
    data class Ready(val code: PairingCode, val syncedToCloud: Boolean) : PairingCodeUiState
    data class Error(val message: String) : PairingCodeUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val appContext: Context,
    val currentUserId: String,
    val currentUserEmail: String,
    private val familyRepo: FamilyRepository,
    private val commRepo: CommunicationRepository,
    private val prefs: AppPreferences,
    private val localDb: FamilyTrackDatabase,
    val voiceRecorderPlayer: VoiceRecorderPlayer,
    val webRtcManager: WebRtcCallManager,
    val nearbyManager: NearbyCommunicationManager,
    private val hubRepo: FamilyHubRepository = FamilyHubRepository(appContext)
) : ViewModel() {

    val isAllowedAdminEmail: Boolean = currentUserEmail.trim().lowercase() == AUTHORIZED_ADMIN_EMAIL

    val localSettings: StateFlow<LocalAppSettings> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalAppSettings())

    private val _connectionStatusMessage = MutableStateFlow<String?>(null)
    val connectionStatusMessage: StateFlow<String?> = _connectionStatusMessage.asStateFlow()

    private val _manualRetryTrigger = MutableStateFlow(0)

    val userProfileState: StateFlow<UiState<UserProfile?>> = familyRepo.observeUserProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { err ->
            val root = unwrapFirestoreError(err)
            Log.w(TAG, "Profile observe error", root)
            emit(UiState.Error(root.message ?: "Failed to load profile"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val familiesSnapshotState: StateFlow<UiState<com.example.data.remote.FamilyQuerySnapshot>> =
        _manualRetryTrigger
            .flatMapLatest {
                familyRepo.observeMyFamiliesWithMetadata(
                    userId = currentUserId,
                    onTransientError = {
                        _connectionStatusMessage.value = "Connection unstable — using cached data & reconnecting..."
                    },
                    onRecovered = {
                        _connectionStatusMessage.value = null
                    }
                )
                    .map<com.example.data.remote.FamilyQuerySnapshot, UiState<com.example.data.remote.FamilyQuerySnapshot>> {
                        UiState.Success(it)
                    }
                    .catch { err ->
                        val root = unwrapFirestoreError(err)
                        Log.w(TAG, "Families observe error", root)
                        emit(UiState.Error(root.message ?: "Failed to connect to family database"))
                    }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    val familiesState: StateFlow<UiState<List<FamilyGroup>>> = familiesSnapshotState
        .map { state ->
            when (state) {
                is UiState.Loading -> UiState.Loading
                is UiState.Error -> UiState.Error(state.message)
                is UiState.Success -> UiState.Success(state.data.families)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    val activeFamily: StateFlow<FamilyGroup?> = combine(familiesSnapshotState, localSettings) { state, s ->
        val cachedFallback = if (
            s.activeFamilyId.isNotBlank() &&
            s.activeAdminUid.isNotBlank() &&
            (s.selectedRole != "ADMIN" || isAllowedAdminEmail)
        ) {
            FamilyGroup(
                id = s.activeFamilyId,
                adminUid = s.activeAdminUid,
                adminEmail = if (s.selectedRole == "ADMIN") currentUserEmail else null,
                name = s.activeFamilyName.ifBlank { "My Family" },
                memberUids = listOf(currentUserId)
            )
        } else {
            null
        }

        when (state) {
            is UiState.Success -> {
                val live = state.data.families.firstOrNull()
                if (live != null) {
                    live
                } else if (state.data.isFromCache && cachedFallback != null) {
                    // Empty offline cache must NEVER evict an already-paired Admin or Family device
                    cachedFallback
                } else {
                    null
                }
            }
            is UiState.Loading, is UiState.Error -> cachedFallback
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isBackendAdmin: StateFlow<Boolean> = activeFamily
        .map { it != null && it.adminUid == currentUserId && isAllowedAdminEmail }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val familyMembers: StateFlow<List<FamilyMember>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isBlank()) flowOf(emptyList())
            else familyRepo.observeFamilyMembers(familyId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val adminDevices: StateFlow<List<PairedDevice>> = activeFamily
        .flatMapLatest { fam ->
            if (fam == null || fam.adminUid != currentUserId || !isAllowedAdminEmail) flowOf(emptyList())
            else familyRepo.observeAdminDevices(fam.id, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val cachedDevices: StateFlow<List<CachedDeviceEntity>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else localDb.cachedDeviceDao().observeDevicesForFamily(famId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val effectiveAdminDevices: StateFlow<List<PairedDevice>> = combine(adminDevices, cachedDevices) { live, cached ->
        live.ifEmpty {
            cached.map { c ->
                PairedDevice(
                    id = c.id,
                    familyId = c.familyId,
                    adminUid = c.adminUid,
                    ownerUid = c.ownerUid,
                    ownerName = c.ownerName,
                    deviceName = c.deviceName,
                    deviceModel = c.deviceModel,
                    role = c.role,
                    consentGranted = c.consentGranted,
                    locationSharingEnabled = c.locationSharingEnabled,
                    locationPermissionGranted = c.locationPermissionGranted,
                    backgroundPermissionGranted = c.backgroundPermissionGranted,
                    batteryPercent = c.batteryPercent.toLong(),
                    networkType = c.networkType,
                    online = c.online,
                    revoked = c.revoked,
                    lastLatitude = c.lastLatitude,
                    lastLongitude = c.lastLongitude,
                    lastAccuracyMeters = c.lastAccuracyMeters,
                    lastLocationAtMs = c.lastLocationAtMs,
                    lastSeenAtMs = c.lastSeenAtMs,
                    lastSyncAtMs = c.lastSyncAtMs
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val ownDevice: StateFlow<PairedDevice?> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else familyRepo.observeOwnDevice(famId, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val activePairingCodes: StateFlow<List<PairingCode>> = isBackendAdmin
        .flatMapLatest { admin ->
            if (!admin) flowOf(emptyList())
            else familyRepo.observeActivePairingCodes(currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val sevenDayLocations: StateFlow<List<LocationRecord>> = activeFamily
        .flatMapLatest { fam ->
            if (fam == null || fam.adminUid != currentUserId || !isAllowedAdminEmail) flowOf(emptyList())
            else familyRepo.observeSevenDayLocationHistory(fam.id, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val cloudNetworkEvents: StateFlow<List<NetworkEventModel>> = activeFamily
        .flatMapLatest { fam ->
            if (fam == null || fam.adminUid != currentUserId || !isAllowedAdminEmail) flowOf(emptyList())
            else hubRepo.observeAdminNetworkEvents(fam.id, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val networkEvents: StateFlow<List<NetworkEventModel>> = combine(
        isBackendAdmin,
        cloudNetworkEvents,
        localDb.networkEventDao().observeLocalEvents()
    ) { admin, cloud, local ->
        if (!admin) emptyList()
        else {
            val localMapped = local.map { e ->
                NetworkEventModel(
                    id = e.id,
                    familyId = e.familyId,
                    adminUid = e.adminUid,
                    ownerUid = e.ownerUid,
                    deviceId = e.deviceId,
                    deviceName = e.deviceName,
                    eventType = e.eventType,
                    networkType = e.networkType,
                    connectionState = e.connectionState,
                    internetAvailable = e.internetAvailable,
                    eventTimestampMs = e.eventTimestampMs,
                    detectedAtMs = e.detectedAtMs,
                    timezoneId = e.timezoneId,
                    timezoneOffsetMinutes = e.timezoneOffsetMinutes.toLong(),
                    sessionId = e.sessionId,
                    details = e.details
                )
            }
            (cloud + localMapped).distinctBy { it.id }.sortedByDescending { it.detectedAtMs }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val cloudNetworkSessions: StateFlow<List<NetworkSessionModel>> = activeFamily
        .flatMapLatest { fam ->
            if (fam == null || fam.adminUid != currentUserId || !isAllowedAdminEmail) flowOf(emptyList())
            else hubRepo.observeAdminNetworkSessions(fam.id, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val networkSessions: StateFlow<List<NetworkSessionModel>> = combine(
        isBackendAdmin,
        cloudNetworkSessions,
        localDb.networkSessionDao().observeLocalSessions()
    ) { admin, cloud, local ->
        if (!admin) emptyList()
        else {
            val localMapped = local.map { s ->
                NetworkSessionModel(
                    id = s.id,
                    familyId = s.familyId,
                    adminUid = s.adminUid,
                    ownerUid = s.ownerUid,
                    deviceId = s.deviceId,
                    deviceName = s.deviceName,
                    networkType = s.networkType,
                    startedAtMs = s.startedAtMs,
                    endedAtMs = s.endedAtMs,
                    durationMs = s.durationMs,
                    status = s.status,
                    lastVerifiedAtMs = s.lastVerifiedAtMs,
                    interruptionReason = s.interruptionReason,
                    timezoneId = s.timezoneId
                )
            }
            (cloud + localMapped).distinctBy { it.id }.sortedByDescending { it.startedAtMs }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val calendarEvents: StateFlow<List<FamilyCalendarEvent>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else hubRepo.observeCalendarEvents(famId).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val tasks: StateFlow<List<FamilyTask>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else hubRepo.observeTasks(famId).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val expenses: StateFlow<List<FamilyExpense>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else hubRepo.observeExpenses(famId).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val notices: StateFlow<List<FamilyNotice>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else hubRepo.observeNotices(famId).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val vaultFiles: StateFlow<List<FamilyVaultFile>> = combine(activeFamily, isBackendAdmin) { fam, admin ->
        fam?.id.orEmpty() to admin
    }.flatMapLatest { (famId, admin) ->
        if (famId.isBlank()) flowOf(emptyList())
        else hubRepo.observeVaultFiles(famId, currentUserId, admin).catch { emit(emptyList()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val cloudNotifications: StateFlow<List<FamilyNotificationModel>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else hubRepo.observeNotifications(famId, currentUserId).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val localNotifications: StateFlow<List<AppNotificationEntity>> = localDb.appNotificationDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val unreadNotificationCount: StateFlow<Int> = combine(cloudNotifications, localNotifications) { cloud, local ->
        cloud.count { !it.seen } + local.count { !it.seen }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0)

    val pendingLocationCount: StateFlow<Int> = localDb.locationRecordDao().observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0)

    val pendingMessageCount: StateFlow<Int> = localDb.pendingMessageDao().observePendingMessageCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0)

    val syncStatus: StateFlow<SyncStatusEntity?> = localDb.syncStatusDao().observeStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val consentLogs: StateFlow<List<ConsentLogEntity>> = localDb.consentLogDao().observeLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val conversations: StateFlow<List<Conversation>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else commRepo.observeConversations(famId, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _selectedConversationId = MutableStateFlow("conv_group")
    val selectedConversationId: StateFlow<String> = _selectedConversationId.asStateFlow()

    private val _replyingToMessage = MutableStateFlow<ChatMessage?>(null)
    val replyingToMessage: StateFlow<ChatMessage?> = _replyingToMessage.asStateFlow()

    val activeMessages: StateFlow<List<ChatMessage>> = combine(activeFamily, _selectedConversationId) { fam, convId ->
        fam?.id.orEmpty() to convId
    }.flatMapLatest { (famId, convId) ->
        if (famId.isBlank() || convId.isBlank()) flowOf(emptyList())
        else commRepo.observeMessages(famId, convId, currentUserId)
            .catch { err ->
                unwrapFirestoreError(err)
                emit(emptyList())
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val pendingConversationMessages: StateFlow<List<PendingMessageEntity>> = _selectedConversationId
        .flatMapLatest { convId ->
            if (convId.isBlank()) flowOf(emptyList())
            else localDb.pendingMessageDao().observePendingForConversation(convId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val calls: StateFlow<List<CallSession>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else commRepo.observeCalls(famId, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val games: StateFlow<List<GameSession>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else commRepo.observeGames(famId, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _selectedGameId = MutableStateFlow<String?>(null)
    val selectedGameId: StateFlow<String?> = _selectedGameId.asStateFlow()

    // Admin's own phone current coordinates for live distance computation
    private val _adminCurrentLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val adminCurrentLocation: StateFlow<Pair<Double, Double>?> = _adminCurrentLocation.asStateFlow()

    private val _actionBanner = MutableStateFlow<String?>(null)
    val actionBanner: StateFlow<String?> = _actionBanner.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _latestGeneratedCode = MutableStateFlow<PairingCode?>(null)
    val latestGeneratedCode: StateFlow<PairingCode?> = _latestGeneratedCode.asStateFlow()

    private val _pairingCodeUiState = MutableStateFlow<PairingCodeUiState>(PairingCodeUiState.Idle)
    val pairingCodeUiState: StateFlow<PairingCodeUiState> = _pairingCodeUiState.asStateFlow()

    private val _pendingConsentAfterJoin = MutableStateFlow(false)
    val pendingConsentAfterJoin: StateFlow<Boolean> = _pendingConsentAfterJoin.asStateFlow()

    // Active Call tracking
    private val _activeCallId = MutableStateFlow<String?>(null)
    val activeCallId: StateFlow<String?> = _activeCallId.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    val nearbyPeers: StateFlow<List<NearbyPeer>> = nearbyManager.peers
    val nearbyPackets: StateFlow<List<NearbyPacket>> = nearbyManager.packets
    val nearbyStatus: StateFlow<String> = nearbyManager.status

    private var iceObserverJob: Job? = null
    private var callTimerJob: Job? = null
    private var appliedRemoteAnswerForCallId: String? = null
    private val seenIceIds = mutableSetOf<String>()
    private var lastNotifiedCallId: String? = null
    private var lastNotifiedMessageId: String? = null
    private var lastTypingSentAtMs: Long = 0L

    init {
        viewModelScope.launch {
            prefs.getOrCreateDeviceId()
        }
        // Keep DataStore pairing metadata, presence, network monitor & Group chat membership synced when family loads
        viewModelScope.launch {
            activeFamily.collect { fam ->
                if (fam != null) {
                    val role = if (fam.adminUid == currentUserId && isAllowedAdminEmail) "ADMIN" else "FAMILY"
                    prefs.setSessionPairing(role = role, familyId = fam.id, adminUid = fam.adminUid, familyName = fam.name)
                    familyRepo.ensureGroupConversationMember(fam.id, fam.memberUids)
                    val cur = localSettings.value
                    SyncScheduler.schedulePeriodicSync(appContext, intervalMinutes = cur.locationIntervalMinutes)
                    NetworkActivityMonitor.startMonitoringIfConsented(appContext)
                    familyRepo.updateMemberPresence(
                        familyId = fam.id,
                        online = true,
                        lastSeenPrivacy = cur.lastSeenPrivacy,
                        availability = cur.availability
                    )
                }
            }
        }
        // Cache Admin device statuses into Room for offline dashboard viewing
        viewModelScope.launch {
            adminDevices.collect { devices ->
                if (devices.isNotEmpty()) {
                    localDb.cachedDeviceDao().upsertAll(
                        devices.map { d ->
                            CachedDeviceEntity(
                                id = d.id,
                                familyId = d.familyId,
                                adminUid = d.adminUid,
                                ownerUid = d.ownerUid,
                                ownerName = d.ownerName,
                                deviceName = d.deviceName,
                                deviceModel = d.deviceModel,
                                role = d.role,
                                consentGranted = d.consentGranted,
                                locationSharingEnabled = d.locationSharingEnabled,
                                locationPermissionGranted = d.locationPermissionGranted,
                                backgroundPermissionGranted = d.backgroundPermissionGranted,
                                batteryPercent = d.batteryPercent.toInt(),
                                networkType = d.networkType,
                                online = d.online,
                                revoked = d.revoked,
                                lastLatitude = d.lastLatitude,
                                lastLongitude = d.lastLongitude,
                                lastAccuracyMeters = d.lastAccuracyMeters,
                                lastLocationAtMs = d.lastLocationAtMs,
                                lastSeenAtMs = d.lastSeenAtMs,
                                lastSyncAtMs = d.lastSyncAtMs
                            )
                        }
                    )
                }
            }
        }
        // Automatically stop foreground sharing if Admin revokes this device
        viewModelScope.launch {
            ownDevice.collect { device ->
                if (device != null && device.revoked) {
                    LocationForegroundService.stop(appContext)
                    prefs.setConsentAndSharing(consentGranted = false, sharingEnabled = false)
                }
            }
        }
        // Automatically fetch Admin phone coordinates for live distance badges
        viewModelScope.launch {
            isBackendAdmin.collect { admin ->
                if (admin && LocationCaptureHelper.hasForegroundLocationPermission(appContext)) {
                    refreshAdminOwnCoordinates()
                }
            }
        }
        // Observe incoming calls & trigger alert notification + WebRTC remote answer handling
        viewModelScope.launch {
            calls.collect { list ->
                val incomingRinging = list.firstOrNull { it.calleeUid == currentUserId && it.status == "RINGING" }
                if (incomingRinging != null && incomingRinging.id != lastNotifiedCallId) {
                    lastNotifiedCallId = incomingRinging.id
                    NotificationHelper.showAlertNotification(
                        appContext,
                        NotificationHelper.CHANNEL_CALLS,
                        2001,
                        "Incoming Voice Call",
                        "${incomingRinging.callerName} is calling you"
                    )
                }
                val activeId = _activeCallId.value
                if (activeId != null) {
                    val session = list.firstOrNull { it.id == activeId }
                    if (session != null) {
                        if (session.callerUid == currentUserId &&
                            session.status == "ACTIVE" &&
                            !session.answerSdp.isNullOrBlank() &&
                            appliedRemoteAnswerForCallId != session.id
                        ) {
                            appliedRemoteAnswerForCallId = session.id
                            webRtcManager.setRemoteAnswer(session.answerSdp)
                            startCallTimer()
                        } else if (session.status in listOf("ENDED", "REJECTED", "MISSED")) {
                            cleanupCallResources()
                        }
                    }
                }
            }
        }
        // Observe incoming messages for notifications, inbox log & read/delivery receipts
        viewModelScope.launch {
            activeMessages.collect { msgs ->
                val famId = activeFamily.value?.id ?: return@collect
                for (m in msgs.takeLast(12)) {
                    if (m.senderUid != currentUserId && !m.deliveredToUids.contains(currentUserId)) {
                        commRepo.markMessageDelivered(famId, m.conversationId, m.id)
                    }
                }
                val latest = msgs.lastOrNull()
                if (latest != null && latest.senderUid != currentUserId && latest.id != lastNotifiedMessageId) {
                    lastNotifiedMessageId = latest.id
                    if (!latest.readByUids.contains(currentUserId)) {
                        val preview = when (latest.type) {
                            "VOICE" -> "Sent a voice message"
                            "IMAGE" -> "Sent a photo"
                            "DOCUMENT" -> "Shared ${latest.fileName ?: "a document"}"
                            else -> latest.text.take(120)
                        }
                        NotificationHelper.showAlertNotification(
                            appContext,
                            NotificationHelper.CHANNEL_MESSAGES,
                            2002,
                            latest.senderName,
                            preview
                        )
                        localDb.appNotificationDao().insert(
                            AppNotificationEntity(
                                id = "msg_${latest.id}",
                                title = "Message from ${latest.senderName}",
                                body = preview,
                                type = "MESSAGE",
                                createdAtMs = latest.clientCreatedAtMs,
                                seen = false,
                                familyId = famId,
                                category = "MESSAGE"
                            )
                        )
                        commRepo.markMessageRead(famId, latest.conversationId, latest.id)
                    }
                }
            }
        }
    }

    fun clearBanner() {
        _actionBanner.value = null
    }

    fun retryFamilyConnection() {
        _connectionStatusMessage.value = "Reconnecting..."
        _manualRetryTrigger.value += 1
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch {
            prefs.setLanguage(lang)
            val role = if (isBackendAdmin.value) "ADMIN" else "FAMILY"
            familyRepo.upsertUserProfile(role = role, language = lang)
        }
    }

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch {
            prefs.setThemePreference(theme)
        }
    }

    fun saveTurnConfig(url: String, username: String, credential: String) {
        viewModelScope.launch {
            prefs.setTurnConfig(url, username, credential)
            _actionBanner.value = "TURN server settings saved."
        }
    }

    // --- ONBOARDING & PAIRING ---
    fun createFamilyAsAdmin(familyName: String) {
        if (!isAllowedAdminEmail) {
            _actionBanner.value = "Admin Mode is restricted to $AUTHORIZED_ADMIN_EMAIL. Please join using Family Mode."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val deviceId = prefs.getOrCreateDeviceId()
            val lang = localSettings.value.language
            val res = familyRepo.createFamilyAsAdmin(familyName, deviceId, lang)
            _isBusy.value = false
            res.onSuccess { fam ->
                prefs.setSessionPairing("ADMIN", fam.id, fam.adminUid, fam.name)
                _pendingConsentAfterJoin.value = true
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Failed to create family group"
            }
        }
    }

    fun joinFamilyWithCode(rawCode: String, deviceName: String) {
        viewModelScope.launch {
            _isBusy.value = true
            val deviceId = prefs.getOrCreateDeviceId()
            val lang = localSettings.value.language
            val lookupRes = familyRepo.lookupPairingCode(rawCode)
            if (lookupRes.isFailure) {
                _isBusy.value = false
                _actionBanner.value = lookupRes.exceptionOrNull()?.localizedMessage ?: "Invalid pairing code"
                return@launch
            }
            val codeObj = lookupRes.getOrThrow()
            val joinRes = familyRepo.joinFamilyWithPairingCode(codeObj, deviceId, deviceName, lang)
            _isBusy.value = false
            joinRes.onSuccess {
                prefs.setSessionPairing("FAMILY", codeObj.familyId, codeObj.adminUid)
                _pendingConsentAfterJoin.value = true
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Could not pair device"
            }
        }
    }

    fun setLastSeenPrivacy(privacy: String) {
        viewModelScope.launch {
            prefs.setLastSeenPrivacy(privacy)
            val role = if (isBackendAdmin.value) "ADMIN" else "FAMILY"
            familyRepo.upsertUserProfile(role = role, language = localSettings.value.language, lastSeenPrivacy = privacy)
            activeFamily.value?.let { fam ->
                familyRepo.updateMemberPresence(fam.id, online = true, lastSeenPrivacy = privacy)
            }
        }
    }

    fun setAvailability(availability: String) {
        viewModelScope.launch {
            prefs.setAvailability(availability)
            activeFamily.value?.let { fam ->
                familyRepo.updateMemberPresence(fam.id, online = true, availability = availability)
            }
        }
    }

    fun setLocationIntervalMinutes(minutes: Int) {
        viewModelScope.launch {
            prefs.setLocationIntervalMinutes(minutes)
            SyncScheduler.schedulePeriodicSync(appContext, intervalMinutes = minutes, replace = true)
            _actionBanner.value = "Sync interval updated to every $minutes min."
        }
    }

    fun toggleNetworkMonitoring(enable: Boolean) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val deviceId = prefs.getOrCreateDeviceId()
            prefs.setNetworkMonitoringConsent(consentGranted = enable, enabled = enable)
            localDb.consentLogDao().insert(
                ConsentLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    familyId = fam.id,
                    deviceId = deviceId,
                    action = if (enable) "NETWORK_GRANTED" else "NETWORK_PAUSED",
                    details = if (enable) "User enabled consent-based network activity monitoring" else "User paused network activity monitoring",
                    timestampMs = System.currentTimeMillis()
                )
            )
            if (enable) {
                NetworkActivityMonitor.startMonitoringIfConsented(appContext)
                SyncScheduler.enqueueImmediateNetworkSync(appContext)
            } else {
                NetworkActivityMonitor.stopMonitoring(appContext, logPauseEvent = true)
                SyncScheduler.enqueueImmediateNetworkSync(appContext)
            }
            val snap = NetworkActivityMonitor.currentSnapshot()
            val netType = LocationCaptureHelper.getNetworkType(appContext)
            familyRepo.updateOwnDeviceStatus(
                familyId = fam.id,
                deviceId = deviceId,
                consentGranted = localSettings.value.consentGranted,
                locationSharingEnabled = localSettings.value.locationSharingEnabled,
                locationPermissionGranted = LocationCaptureHelper.hasForegroundLocationPermission(appContext),
                backgroundPermissionGranted = LocationCaptureHelper.hasBackgroundLocationPermission(appContext),
                batteryPercent = LocationCaptureHelper.getBatteryPercent(appContext),
                networkType = netType,
                online = netType != "OFFLINE",
                networkConsentGranted = enable,
                networkMonitoringEnabled = enable,
                currentWifiAvailable = snap.wifiAvailable,
                currentCellularAvailable = snap.cellularAvailable,
                internetAvailable = snap.internetAvailable
            )
        }
    }

    fun grantConsentAndEnableSharing() {
        grantConsentAndEnableSharing(enableLocation = true, enableNetwork = true)
    }

    fun grantConsentAndEnableSharing(enableLocation: Boolean = true, enableNetwork: Boolean = true) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val deviceId = prefs.getOrCreateDeviceId()
            prefs.setConsentAndSharing(consentGranted = enableLocation, sharingEnabled = enableLocation)
            prefs.setNetworkMonitoringConsent(consentGranted = enableNetwork, enabled = enableNetwork)
            _pendingConsentAfterJoin.value = false

            if (enableLocation) {
                localDb.consentLogDao().insert(
                    ConsentLogEntity(
                        id = UUID.randomUUID().toString(),
                        userId = currentUserId,
                        familyId = fam.id,
                        deviceId = deviceId,
                        action = "GRANTED",
                        details = "Explicit user consent granted for location & telemetry sharing",
                        timestampMs = System.currentTimeMillis()
                    )
                )
                LocationForegroundService.startIfPermitted(appContext)
            }
            if (enableNetwork) {
                localDb.consentLogDao().insert(
                    ConsentLogEntity(
                        id = UUID.randomUUID().toString(),
                        userId = currentUserId,
                        familyId = fam.id,
                        deviceId = deviceId,
                        action = "NETWORK_GRANTED",
                        details = "Explicit user consent granted for network connectivity history",
                        timestampMs = System.currentTimeMillis()
                    )
                )
                NetworkActivityMonitor.startMonitoringIfConsented(appContext)
            }
            captureLocationAndQueueSync()
        }
    }

    fun skipLocationSharingForNow() {
        viewModelScope.launch {
            val fam = activeFamily.value
            val deviceId = prefs.getOrCreateDeviceId()
            prefs.setConsentAndSharing(consentGranted = false, sharingEnabled = false)
            _pendingConsentAfterJoin.value = false
            LocationForegroundService.stop(appContext)
            if (fam != null) {
                familyRepo.updateOwnDeviceStatus(
                    familyId = fam.id,
                    deviceId = deviceId,
                    consentGranted = false,
                    locationSharingEnabled = false,
                    locationPermissionGranted = LocationCaptureHelper.hasForegroundLocationPermission(appContext),
                    backgroundPermissionGranted = LocationCaptureHelper.hasBackgroundLocationPermission(appContext),
                    batteryPercent = LocationCaptureHelper.getBatteryPercent(appContext),
                    networkType = LocationCaptureHelper.getNetworkType(appContext),
                    online = LocationCaptureHelper.getNetworkType(appContext) != "OFFLINE",
                    networkConsentGranted = localSettings.value.networkMonitoringConsent,
                    networkMonitoringEnabled = localSettings.value.networkMonitoringEnabled
                )
            }
        }
    }

    fun toggleLocationSharing(enable: Boolean) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val deviceId = prefs.getOrCreateDeviceId()
            if (enable && !LocationCaptureHelper.hasForegroundLocationPermission(appContext)) {
                _actionBanner.value = "Grant location permission first to enable sharing."
                return@launch
            }
            prefs.setConsentAndSharing(consentGranted = true, sharingEnabled = enable)
            localDb.consentLogDao().insert(
                ConsentLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    familyId = fam.id,
                    deviceId = deviceId,
                    action = if (enable) "RESUMED" else "PAUSED",
                    details = if (enable) "User resumed location sharing" else "User paused location sharing",
                    timestampMs = System.currentTimeMillis()
                )
            )
            if (enable) {
                LocationForegroundService.startIfPermitted(appContext)
                captureLocationAndQueueSync()
            } else {
                LocationForegroundService.stop(appContext)
                familyRepo.updateOwnDeviceStatus(
                    familyId = fam.id,
                    deviceId = deviceId,
                    consentGranted = true,
                    locationSharingEnabled = false,
                    locationPermissionGranted = LocationCaptureHelper.hasForegroundLocationPermission(appContext),
                    backgroundPermissionGranted = LocationCaptureHelper.hasBackgroundLocationPermission(appContext),
                    batteryPercent = LocationCaptureHelper.getBatteryPercent(appContext),
                    networkType = LocationCaptureHelper.getNetworkType(appContext),
                    online = LocationCaptureHelper.getNetworkType(appContext) != "OFFLINE",
                    networkConsentGranted = localSettings.value.networkMonitoringConsent,
                    networkMonitoringEnabled = localSettings.value.networkMonitoringEnabled
                )
            }
        }
    }

    // --- LOCATION CAPTURE & SYNC ---
    fun refreshAdminOwnCoordinates() {
        viewModelScope.launch {
            val captured = LocationCaptureHelper.captureBestLocation(appContext)
            if (captured != null) {
                _adminCurrentLocation.value = captured.latitude to captured.longitude
            }
        }
    }

    fun captureLocationAndQueueSync() {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val settings = localSettings.value
            val deviceId = prefs.getOrCreateDeviceId()

            if (settings.consentGranted && settings.locationSharingEnabled &&
                LocationCaptureHelper.hasForegroundLocationPermission(appContext)
            ) {
                val loc = LocationCaptureHelper.captureBestLocation(appContext)
                if (loc != null) {
                    if (fam.adminUid == currentUserId) {
                        _adminCurrentLocation.value = loc.latitude to loc.longitude
                    }
                    localDb.locationRecordDao().insertRecord(
                        LocationRecordEntity(
                            id = UUID.randomUUID().toString(),
                            familyId = fam.id,
                            adminUid = fam.adminUid,
                            ownerUid = currentUserId,
                            deviceId = deviceId,
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracyMeters = loc.accuracyMeters,
                            altitudeMeters = loc.altitudeMeters,
                            speedMps = loc.speedMps,
                            batteryPercent = LocationCaptureHelper.getBatteryPercent(appContext),
                            networkType = LocationCaptureHelper.getNetworkType(appContext),
                            source = loc.source,
                            recordedAtMs = loc.timestampMs,
                            synced = false
                        )
                    )
                }
            }
            SyncScheduler.enqueueImmediateLocationSync(appContext)
        }
    }

    fun generatePairingCode() {
        viewModelScope.launch {
            val attemptId = QrDiagnosticLogger.newAttemptId()
            val s = localSettings.value
            val famId = activeFamily.value?.id ?: s.activeFamilyId
            val famName = activeFamily.value?.name?.ifBlank { null }
                ?: s.activeFamilyName.ifBlank { "My Family" }
            QrDiagnosticLogger.logStage(
                attemptId = attemptId,
                stage = QrDiagnosticStage.CODE_REQUEST_START,
                status = "OK",
                message = "Requested pairing code for familyId='${famId.ifBlank { "<empty>" }}'"
            )
            if (famId.isBlank()) {
                val msg = "Family group is still loading. Check internet connection and tap Retry."
                QrDiagnosticLogger.logStage(
                    attemptId = attemptId,
                    stage = QrDiagnosticStage.ERROR,
                    status = "FAIL",
                    message = msg
                )
                _pairingCodeUiState.value = PairingCodeUiState.Error(msg)
                _actionBanner.value = msg
                return@launch
            }
            _isBusy.value = true
            _pairingCodeUiState.value = PairingCodeUiState.Generating
            val res = familyRepo.generatePairingCodeWithStatus(
                familyId = famId,
                familyName = famName,
                attemptId = attemptId,
                fallbackAdminUid = if (isAllowedAdminEmail) currentUserId else ""
            )
            _isBusy.value = false
            res.onSuccess { outcome ->
                _latestGeneratedCode.value = outcome.pairingCode
                _pairingCodeUiState.value = PairingCodeUiState.Ready(
                    code = outcome.pairingCode,
                    syncedToCloud = outcome.syncedToCloud
                )
                outcome.pendingWriteTask?.addOnCompleteListener { task ->
                    val cur = _pairingCodeUiState.value
                    if (task.isSuccessful) {
                        QrDiagnosticLogger.logStage(
                            attemptId = attemptId,
                            stage = QrDiagnosticStage.FIRESTORE_WRITE_RESULT,
                            status = "OK",
                            message = "Delayed cloud ACK completed for ${outcome.pairingCode.code}"
                        )
                        if (cur is PairingCodeUiState.Ready && cur.code.code == outcome.pairingCode.code) {
                            _pairingCodeUiState.value = cur.copy(syncedToCloud = true)
                        }
                    } else {
                        val errMsg = task.exception?.localizedMessage ?: "Cloud sync failed for pairing code"
                        QrDiagnosticLogger.logStage(
                            attemptId = attemptId,
                            stage = QrDiagnosticStage.ERROR,
                            status = "FAIL",
                            message = "Delayed pairing code upload failed: $errMsg",
                            error = task.exception
                        )
                        Log.w(TAG, "Delayed pairing code upload error", task.exception)
                        if (cur is PairingCodeUiState.Ready && cur.code.code == outcome.pairingCode.code) {
                            _pairingCodeUiState.value = PairingCodeUiState.Error(errMsg)
                        }
                    }
                }
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "Failed to generate pairing code. Please try again."
                QrDiagnosticLogger.logStage(
                    attemptId = attemptId,
                    stage = QrDiagnosticStage.ERROR,
                    status = "FAIL",
                    message = msg,
                    error = err
                )
                _pairingCodeUiState.value = PairingCodeUiState.Error(msg)
                _actionBanner.value = msg
            }
        }
    }

    fun revokeDevice(deviceId: String) {
        setDevicePausedOrRevoked(deviceId, revoked = true)
    }

    fun setDevicePausedOrRevoked(deviceId: String, revoked: Boolean) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val res = familyRepo.setDeviceRevoked(fam.id, deviceId, revoked)
            res.onSuccess {
                _actionBanner.value = if (revoked) "Device access paused/revoked." else "Device access restored."
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Failed to update device"
            }
        }
    }

    fun renamePairedDevice(deviceId: String, newName: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val res = familyRepo.renameDevice(fam.id, deviceId, newName)
            res.onSuccess { _actionBanner.value = "Device renamed." }
                .onFailure { _actionBanner.value = it.localizedMessage ?: "Rename failed" }
        }
    }

    fun removePairedDevice(deviceId: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val res = familyRepo.removeDevice(fam.id, deviceId)
            res.onSuccess { _actionBanner.value = "Device removed." }
                .onFailure { _actionBanner.value = it.localizedMessage ?: "Remove failed" }
        }
    }

    // --- WHATSAPP-STYLE MESSENGER ---
    fun selectConversation(conversationId: String) {
        _selectedConversationId.value = conversationId
        _replyingToMessage.value = null
    }

    fun openDirectChat(member: FamilyMember) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val myName = Firebase.auth.currentUser?.displayName ?: "Family"
            val res = commRepo.getOrCreateDirectConversation(fam.id, member.uid, member.displayName, myName)
            res.onSuccess { convId ->
                _selectedConversationId.value = convId
                _replyingToMessage.value = null
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Could not open chat"
            }
        }
    }

    fun setReplyTarget(msg: ChatMessage?) {
        _replyingToMessage.value = msg
    }

    fun onChatTypingStateChanged(isTyping: Boolean, activity: String = "TYPING") {
        val fam = activeFamily.value ?: return
        val convId = _selectedConversationId.value
        val now = System.currentTimeMillis()
        if (isTyping && now - lastTypingSentAtMs < 3_000L) return
        lastTypingSentAtMs = if (isTyping) now else 0L
        viewModelScope.launch {
            familyRepo.updateMemberPresence(
                familyId = fam.id,
                online = true,
                typingConversationId = convId,
                typingActivity = if (isTyping) activity else "NONE"
            )
        }
    }

    fun sendTextMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val replyTarget = _replyingToMessage.value
        _replyingToMessage.value = null
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val convId = _selectedConversationId.value
            val conv = conversations.value.firstOrNull { it.id == convId }
            val participants = conv?.participantUids?.ifEmpty { fam.memberUids } ?: fam.memberUids
            val senderName = Firebase.auth.currentUser?.displayName ?: "Family Member"
            val messageId = UUID.randomUUID().toString()
            val nowMs = System.currentTimeMillis()
            val replyPreview = replyTarget?.let { "${it.senderName}: ${it.text.take(70)}" }

            localDb.pendingMessageDao().insertPending(
                PendingMessageEntity(
                    id = messageId,
                    conversationId = convId,
                    familyId = fam.id,
                    senderUid = currentUserId,
                    senderName = senderName,
                    participantUidsCsv = participants.joinToString(","),
                    type = "TEXT",
                    text = clean,
                    replyToMessageId = replyTarget?.id,
                    replyToPreview = replyPreview,
                    clientCreatedAtMs = nowMs,
                    synced = false
                )
            )

            val res = commRepo.sendMessageIdempotent(
                messageId = messageId,
                familyId = fam.id,
                conversationId = convId,
                senderName = senderName,
                participantUids = participants,
                type = "TEXT",
                text = clean,
                replyToMessageId = replyTarget?.id,
                replyToPreview = replyPreview,
                clientCreatedAtMs = nowMs
            )
            if (res.isSuccess) {
                localDb.pendingMessageDao().markSynced(messageId)
                familyRepo.updateMemberPresence(fam.id, online = true, typingConversationId = convId, typingActivity = "NONE")
            } else {
                localDb.pendingMessageDao().markFailed(messageId, res.exceptionOrNull()?.message)
                SyncScheduler.enqueueImmediateCommsSync(appContext)
            }
        }
    }

    fun sendVoiceNote(file: File, durationMs: Long) {
        val replyTarget = _replyingToMessage.value
        _replyingToMessage.value = null
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val convId = _selectedConversationId.value
            val conv = conversations.value.firstOrNull { it.id == convId }
            val participants = conv?.participantUids?.ifEmpty { fam.memberUids } ?: fam.memberUids
            val senderName = Firebase.auth.currentUser?.displayName ?: "Family Member"
            val messageId = UUID.randomUUID().toString()
            val nowMs = System.currentTimeMillis()
            val replyPreview = replyTarget?.let { "${it.senderName}: ${it.text.take(70)}" }

            localDb.pendingMessageDao().insertPending(
                PendingMessageEntity(
                    id = messageId,
                    conversationId = convId,
                    familyId = fam.id,
                    senderUid = currentUserId,
                    senderName = senderName,
                    participantUidsCsv = participants.joinToString(","),
                    type = "VOICE",
                    text = "Voice note (${durationMs / 1000L}s)",
                    voiceFilePath = file.absolutePath,
                    voiceDurationMs = durationMs,
                    replyToMessageId = replyTarget?.id,
                    replyToPreview = replyPreview,
                    clientCreatedAtMs = nowMs,
                    synced = false
                )
            )

            val base64 = runCatching {
                if (file.length() <= 400_000L) Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) else null
            }.getOrNull()

            if (base64 == null) {
                localDb.pendingMessageDao().markFailed(messageId, "Voice note exceeded 400 KB limit")
                _actionBanner.value = "Voice note exceeded size limit."
                return@launch
            }

            val res = commRepo.sendMessageIdempotent(
                messageId = messageId,
                familyId = fam.id,
                conversationId = convId,
                senderName = senderName,
                participantUids = participants,
                type = "VOICE",
                text = "Voice note (${durationMs / 1000L}s)",
                voiceAudioBase64 = base64,
                voiceDurationMs = durationMs,
                replyToMessageId = replyTarget?.id,
                replyToPreview = replyPreview,
                clientCreatedAtMs = nowMs
            )
            if (res.isSuccess) {
                localDb.pendingMessageDao().markSynced(messageId)
            } else {
                localDb.pendingMessageDao().markFailed(messageId, res.exceptionOrNull()?.message)
                SyncScheduler.enqueueImmediateCommsSync(appContext)
            }
        }
    }

    fun sendImageAttachment(uri: Uri, caption: String = "") {
        val replyTarget = _replyingToMessage.value
        _replyingToMessage.value = null
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val prepared = MediaAndExportHelper.compressImageToPayload(appContext, uri)
            if (prepared.isFailure) {
                _actionBanner.value = prepared.exceptionOrNull()?.message ?: "Could not prepare photo"
                return@launch
            }
            val payload = prepared.getOrThrow()
            sendPreparedMediaMessage(
                familyId = fam.id,
                memberUids = fam.memberUids,
                type = "IMAGE",
                text = caption.ifBlank { payload.fileName },
                mediaFile = payload.file,
                fileName = payload.fileName,
                mimeType = payload.mimeType,
                sizeBytes = payload.sizeBytes,
                base64 = payload.base64,
                replyTarget = replyTarget
            )
        }
    }

    fun sendDocumentAttachment(uri: Uri, caption: String = "") {
        val replyTarget = _replyingToMessage.value
        _replyingToMessage.value = null
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val prepared = MediaAndExportHelper.readDocumentWithinLimit(appContext, uri)
            if (prepared.isFailure) {
                _actionBanner.value = prepared.exceptionOrNull()?.message ?: "Document exceeded limit"
                return@launch
            }
            val payload = prepared.getOrThrow()
            sendPreparedMediaMessage(
                familyId = fam.id,
                memberUids = fam.memberUids,
                type = "DOCUMENT",
                text = caption.ifBlank { payload.fileName },
                mediaFile = payload.file,
                fileName = payload.fileName,
                mimeType = payload.mimeType,
                sizeBytes = payload.sizeBytes,
                base64 = payload.base64,
                replyTarget = replyTarget
            )
        }
    }

    private suspend fun sendPreparedMediaMessage(
        familyId: String,
        memberUids: List<String>,
        type: String,
        text: String,
        mediaFile: File,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        base64: String,
        replyTarget: ChatMessage?
    ) {
        val convId = _selectedConversationId.value
        val conv = conversations.value.firstOrNull { it.id == convId }
        val participants = conv?.participantUids?.ifEmpty { memberUids } ?: memberUids
        val senderName = Firebase.auth.currentUser?.displayName ?: "Family Member"
        val messageId = UUID.randomUUID().toString()
        val nowMs = System.currentTimeMillis()
        val replyPreview = replyTarget?.let { "${it.senderName}: ${it.text.take(70)}" }

        localDb.pendingMessageDao().insertPending(
            PendingMessageEntity(
                id = messageId,
                conversationId = convId,
                familyId = familyId,
                senderUid = currentUserId,
                senderName = senderName,
                participantUidsCsv = participants.joinToString(","),
                type = type,
                text = text,
                mediaFilePath = mediaFile.absolutePath,
                fileName = fileName,
                mimeType = mimeType,
                fileSizeBytes = sizeBytes,
                replyToMessageId = replyTarget?.id,
                replyToPreview = replyPreview,
                clientCreatedAtMs = nowMs,
                synced = false
            )
        )

        val res = commRepo.sendMessageIdempotent(
            messageId = messageId,
            familyId = familyId,
            conversationId = convId,
            senderName = senderName,
            participantUids = participants,
            type = type,
            text = text,
            mediaBase64 = base64,
            fileName = fileName,
            mimeType = mimeType,
            fileSizeBytes = sizeBytes,
            replyToMessageId = replyTarget?.id,
            replyToPreview = replyPreview,
            clientCreatedAtMs = nowMs
        )
        if (res.isSuccess) {
            localDb.pendingMessageDao().markSynced(messageId)
        } else {
            localDb.pendingMessageDao().markFailed(messageId, res.exceptionOrNull()?.message)
            SyncScheduler.enqueueImmediateCommsSync(appContext)
        }
    }

    fun retryPendingMessage(messageId: String) {
        viewModelScope.launch {
            localDb.pendingMessageDao().clearErrorForRetry(messageId)
            val ok = SyncScheduler.flushOfflineComms(appContext, currentUserId)
            if (!ok) SyncScheduler.enqueueImmediateCommsSync(appContext)
        }
    }

    fun deletePendingMessage(messageId: String) {
        viewModelScope.launch {
            localDb.pendingMessageDao().deleteById(messageId)
        }
    }

    fun toggleMessageReaction(msg: ChatMessage, emoji: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            commRepo.toggleReaction(fam.id, msg.conversationId, msg, emoji)
        }
    }

    fun editMessage(msg: ChatMessage, newText: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val res = commRepo.editMessageText(fam.id, msg.conversationId, msg.id, newText)
            res.onFailure { _actionBanner.value = it.localizedMessage ?: "Edit failed" }
        }
    }

    fun deleteMessageForAll(msg: ChatMessage) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val res = commRepo.deleteMessageForAll(fam.id, msg.conversationId, msg.id)
            res.onFailure { _actionBanner.value = it.localizedMessage ?: "Delete failed" }
        }
    }

    fun toggleConversationFlag(conversationId: String, fieldName: String, enable: Boolean) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            commRepo.toggleConversationFlag(fam.id, conversationId, fieldName, enable)
        }
    }

    fun openChatDocument(fileName: String, mimeType: String, base64: String) {
        val res = MediaAndExportHelper.openBase64Document(appContext, fileName, mimeType, base64)
        res.onFailure { _actionBanner.value = it.localizedMessage ?: "No app available to open file" }
    }

    // --- WEBRTC VOICE CALLS ---
    fun startVoiceCall(member: FamilyMember) {
        val fam = activeFamily.value ?: return
        if (LocationCaptureHelper.getNetworkType(appContext) == "OFFLINE") {
            _actionBanner.value = "Internet connection is required for online voice calls."
            return
        }
        val settings = localSettings.value
        val myName = Firebase.auth.currentUser?.displayName ?: "Family"
        val participants = listOf(currentUserId, member.uid)

        val initialized = webRtcManager.startPeerConnection(
            turnUrl = settings.turnServerUrl,
            turnUsername = settings.turnUsername,
            turnCredential = settings.turnCredential,
            onLocalIceCandidate = { cand ->
                val callId = _activeCallId.value ?: return@startPeerConnection
                viewModelScope.launch {
                    commRepo.sendIceCandidate(
                        fam.id, callId, participants,
                        cand.sdpMid ?: "0", cand.sdpMLineIndex, cand.sdp
                    )
                }
            }
        )
        if (!initialized) return

        webRtcManager.createOffer(
            onOfferCreated = { sdp ->
                viewModelScope.launch {
                    val res = commRepo.createCallSession(fam.id, myName, member.uid, member.displayName, sdp)
                    res.onSuccess { callId ->
                        _activeCallId.value = callId
                        appliedRemoteAnswerForCallId = null
                        observeCallIceCandidates(fam.id, callId)
                    }.onFailure { err ->
                        webRtcManager.closeSession()
                        _actionBanner.value = err.localizedMessage ?: "Failed to start call"
                    }
                }
            },
            onError = { err ->
                webRtcManager.closeSession()
                _actionBanner.value = err
            }
        )
    }

    fun acceptIncomingCall(call: CallSession) {
        val fam = activeFamily.value ?: return
        val offerSdp = call.offerSdp
        if (offerSdp.isNullOrBlank()) {
            _actionBanner.value = "Invalid call offer received."
            return
        }
        val settings = localSettings.value
        _activeCallId.value = call.id
        val initialized = webRtcManager.startPeerConnection(
            turnUrl = settings.turnServerUrl,
            turnUsername = settings.turnUsername,
            turnCredential = settings.turnCredential,
            onLocalIceCandidate = { cand ->
                viewModelScope.launch {
                    commRepo.sendIceCandidate(
                        fam.id, call.id, call.participantUids,
                        cand.sdpMid ?: "0", cand.sdpMLineIndex, cand.sdp
                    )
                }
            }
        )
        if (!initialized) return

        observeCallIceCandidates(fam.id, call.id)
        webRtcManager.acceptOfferAndCreateAnswer(
            offerSdp = offerSdp,
            onAnswerCreated = { answerSdp ->
                viewModelScope.launch {
                    commRepo.updateCallStatus(fam.id, call.id, "ACTIVE", answerSdp = answerSdp)
                    startCallTimer()
                }
            },
            onError = { err ->
                webRtcManager.closeSession()
                _activeCallId.value = null
                _actionBanner.value = err
            }
        )
    }

    fun rejectIncomingCall(call: CallSession) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            commRepo.updateCallStatus(fam.id, call.id, "REJECTED", endedAtMs = System.currentTimeMillis())
        }
    }

    fun endActiveCall() {
        viewModelScope.launch {
            val fam = activeFamily.value
            val callId = _activeCallId.value
            if (fam != null && callId != null) {
                commRepo.updateCallStatus(fam.id, callId, "ENDED", endedAtMs = System.currentTimeMillis())
            }
            cleanupCallResources()
        }
    }

    private fun observeCallIceCandidates(familyId: String, callId: String) {
        iceObserverJob?.cancel()
        seenIceIds.clear()
        iceObserverJob = viewModelScope.launch {
            commRepo.observeIceCandidates(familyId, callId, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                }
                .collect { cands ->
                    for (c in cands) {
                        if (c.senderUid != currentUserId && seenIceIds.add(c.id)) {
                            webRtcManager.addRemoteIceCandidate(c.sdpMid, c.sdpMLineIndex.toInt(), c.candidate)
                        }
                    }
                }
        }
    }

    private fun startCallTimer() {
        if (callTimerJob?.isActive == true) return
        _callDurationSeconds.value = 0L
        callTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                _callDurationSeconds.value += 1L
            }
        }
    }

    private fun cleanupCallResources() {
        iceObserverJob?.cancel()
        callTimerJob?.cancel()
        _callDurationSeconds.value = 0L
        _activeCallId.value = null
        appliedRemoteAnswerForCallId = null
        seenIceIds.clear()
        webRtcManager.closeSession()
    }

    // --- MULTIPLAYER TIC-TAC-TOE ---
    fun inviteToTicTacToe(opponent: FamilyMember) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val myName = Firebase.auth.currentUser?.displayName ?: "Player"
            val res = commRepo.createGameSession(fam.id, myName, opponent.uid, opponent.displayName)
            res.onSuccess { id ->
                _selectedGameId.value = id
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Could not start game"
            }
        }
    }

    fun selectGame(gameId: String) {
        _selectedGameId.value = gameId
    }

    fun makeTicTacToeMove(game: GameSession, cellIndex: Int) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val res = commRepo.submitTicTacToeMove(fam.id, game, cellIndex)
            res.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Invalid move"
            }
        }
    }

    fun restartTicTacToe(gameId: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            commRepo.restartGame(fam.id, gameId)
        }
    }

    // --- NEARBY OPTIONAL COMMUNICATION ---
    fun startNearbyDiscovery() {
        nearbyManager.startDiscoveryAndServer()
    }

    fun sendNearbyPacket(peerAddress: String, kind: String, payload: String) {
        val myName = Firebase.auth.currentUser?.displayName ?: "Family"
        nearbyManager.sendBluetoothPacket(peerAddress, myName, kind, payload) { _, msg ->
            _actionBanner.value = msg
        }
    }

    // --- FAMILY HUB: CALENDAR, TASKS, EXPENSES, NOTICES, VAULT, NOTIFICATIONS ---
    fun createCalendarEvent(
        title: String,
        description: String,
        startAtMs: Long,
        recurrence: String,
        reminderMinutesBefore: Int
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val name = Firebase.auth.currentUser?.displayName ?: "Family"
            val res = hubRepo.createCalendarEvent(
                familyId = fam.id,
                title = title,
                description = description,
                startAtMs = startAtMs,
                endAtMs = startAtMs + 60 * 60 * 1000L,
                recurrence = recurrence,
                reminderMinutesBefore = reminderMinutesBefore,
                participantUids = fam.memberUids,
                creatorName = name
            )
            res.onSuccess { _actionBanner.value = "Event added to Family Calendar." }
                .onFailure { _actionBanner.value = it.localizedMessage ?: "Failed to add event" }
        }
    }

    fun deleteCalendarEvent(eventId: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            hubRepo.deleteCalendarEvent(fam.id, eventId)
        }
    }

    fun createTask(title: String, description: String, assignedTo: FamilyMember?, priority: String, dueAtMs: Long?) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val creator = Firebase.auth.currentUser?.displayName ?: "Family"
            val targetUid = assignedTo?.uid ?: currentUserId
            val targetName = assignedTo?.displayName ?: creator
            val res = hubRepo.createTask(
                familyId = fam.id,
                title = title,
                description = description,
                assignedToUid = targetUid,
                assignedToName = targetName,
                priority = priority,
                dueAtMs = dueAtMs,
                creatorName = creator
            )
            res.onSuccess {
                _actionBanner.value = "Task created."
                if (targetUid != currentUserId) {
                    hubRepo.sendTargetedNotification(fam.id, targetUid, "New Family Task", title.take(100), "TASK")
                }
            }.onFailure { _actionBanner.value = it.localizedMessage ?: "Failed to create task" }
        }
    }

    fun toggleTask(task: FamilyTask) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            hubRepo.toggleTaskCompletion(fam.id, task)
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            hubRepo.deleteTask(fam.id, taskId)
        }
    }

    fun createExpense(title: String, category: String, amountBdt: Double, paidBy: FamilyMember?, notes: String) {
        if (title.isBlank() || amountBdt <= 0.0) return
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val myName = Firebase.auth.currentUser?.displayName ?: "Family"
            val res = hubRepo.createExpense(
                familyId = fam.id,
                title = title,
                category = category,
                amountBdt = amountBdt,
                paidByUid = paidBy?.uid ?: currentUserId,
                paidByName = paidBy?.displayName ?: myName,
                expenseDateMs = System.currentTimeMillis(),
                notes = notes
            )
            res.onSuccess { _actionBanner.value = "Expense recorded (৳${amountBdt.toInt()})." }
                .onFailure { _actionBanner.value = it.localizedMessage ?: "Failed to save expense" }
        }
    }

    fun deleteExpense(expenseId: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            hubRepo.deleteExpense(fam.id, expenseId)
        }
    }

    fun createNotice(title: String, body: String, pinned: Boolean, expiresAtMs: Long? = null) {
        if (title.isBlank() || body.isBlank()) return
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val myName = Firebase.auth.currentUser?.displayName ?: "Admin"
            val res = hubRepo.createNotice(fam.id, title, body, pinned, expiresAtMs, myName)
            res.onSuccess {
                _actionBanner.value = "Notice published."
                fam.memberUids.filter { it != currentUserId }.forEach { uid ->
                    hubRepo.sendTargetedNotification(fam.id, uid, "Family Notice: ${title.take(50)}", body.take(120), "NOTICE")
                }
            }.onFailure { _actionBanner.value = it.localizedMessage ?: "Failed to publish notice" }
        }
    }

    fun toggleNoticePin(notice: FamilyNotice) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            hubRepo.toggleNoticePinned(fam.id, notice)
        }
    }

    fun markNoticeRead(noticeId: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            hubRepo.markNoticeRead(fam.id, noticeId)
        }
    }

    fun deleteNotice(noticeId: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            hubRepo.deleteNotice(fam.id, noticeId)
        }
    }

    fun uploadVaultDocument(uri: Uri, title: String, folder: String, allowedUids: List<String>) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val prepared = MediaAndExportHelper.readDocumentWithinLimit(appContext, uri)
            if (prepared.isFailure) {
                _actionBanner.value = prepared.exceptionOrNull()?.message ?: "File exceeds 550 KB limit"
                return@launch
            }
            val payload = prepared.getOrThrow()
            val myName = Firebase.auth.currentUser?.displayName ?: "Family"
            val res = hubRepo.uploadVaultFile(
                familyId = fam.id,
                title = title.ifBlank { payload.fileName },
                folder = folder,
                fileName = payload.fileName,
                mimeType = payload.mimeType,
                fileSizeBytes = payload.sizeBytes,
                fileBase64 = payload.base64,
                uploadedByName = myName,
                allowedUids = allowedUids.ifEmpty { fam.memberUids }
            )
            res.onSuccess { _actionBanner.value = "Uploaded to Family Vault." }
                .onFailure { _actionBanner.value = it.localizedMessage ?: "Upload failed" }
        }
    }

    fun openVaultDocument(file: FamilyVaultFile) {
        val res = MediaAndExportHelper.openBase64Document(appContext, file.fileName, file.mimeType, file.fileBase64)
        res.onFailure { _actionBanner.value = it.localizedMessage ?: "Cannot open document" }
    }

    fun deleteVaultFile(fileId: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            hubRepo.deleteVaultFile(fam.id, fileId)
        }
    }

    fun markNotificationSeen(cloudId: String?, localId: String?) {
        viewModelScope.launch {
            val fam = activeFamily.value
            if (cloudId != null && fam != null) {
                hubRepo.markNotificationSeen(fam.id, cloudId)
            }
            if (localId != null) {
                localDb.appNotificationDao().markSeen(localId)
            }
        }
    }

    fun markAllNotificationsSeen() {
        viewModelScope.launch {
            val fam = activeFamily.value
            if (fam != null) {
                cloudNotifications.value.filter { !it.seen }.forEach { n ->
                    hubRepo.markNotificationSeen(fam.id, n.id)
                }
            }
            localDb.appNotificationDao().markAllSeen()
        }
    }

    // --- CSV EXPORTS ---
    fun exportNetworkHistory(deviceId: String?) {
        val devs = effectiveAdminDevices.value
        val devName = devs.firstOrNull { it.id == deviceId }?.deviceName ?: "All_Devices"
        val evts = if (deviceId.isNullOrBlank()) networkEvents.value else networkEvents.value.filter { it.deviceId == deviceId }
        val sess = if (deviceId.isNullOrBlank()) networkSessions.value else networkSessions.value.filter { it.deviceId == deviceId }
        val csv = buildNetworkActivityCsv(devName, evts, sess)
        MediaAndExportHelper.shareCsvReport(appContext, "network_activity_${devName}.csv", "Network Activity ($devName)", csv)
            .onFailure { _actionBanner.value = it.localizedMessage ?: "Export failed" }
    }

    fun exportLocationHistory(deviceId: String?) {
        val devs = effectiveAdminDevices.value
        val devName = devs.firstOrNull { it.id == deviceId }?.deviceName ?: "All_Devices"
        val recs = if (deviceId.isNullOrBlank()) sevenDayLocations.value else sevenDayLocations.value.filter { it.deviceId == deviceId }
        val csv = buildLocationHistoryCsv(devName, recs)
        MediaAndExportHelper.shareCsvReport(appContext, "route_history_${devName}.csv", "7-Day Route ($devName)", csv)
            .onFailure { _actionBanner.value = it.localizedMessage ?: "Export failed" }
    }

    fun exportExpensesCsv() {
        val csv = buildExpensesCsv(expenses.value)
        MediaAndExportHelper.shareCsvReport(appContext, "family_expenses_bdt.csv", "Family Expenses (BDT)", csv)
            .onFailure { _actionBanner.value = it.localizedMessage ?: "Export failed" }
    }

    suspend fun prepareSignOut() {
        activeFamily.value?.let { fam ->
            familyRepo.updateMemberPresence(fam.id, online = false, typingActivity = "NONE")
        }
        LocationForegroundService.stop(appContext)
        NetworkActivityMonitor.stopMonitoring(appContext, logPauseEvent = false)
        cleanupCallResources()
        voiceRecorderPlayer.release()
        nearbyManager.stop()
        prefs.clearPairingOnSignOut()
    }

    override fun onCleared() {
        super.onCleared()
        cleanupCallResources()
        voiceRecorderPlayer.release()
        nearbyManager.stop()
    }

    companion object {
        private const val TAG = "MainViewModel"
        const val AUTHORIZED_ADMIN_EMAIL = "quixoratech@gmail.com"
    }
}
