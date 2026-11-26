package com.example.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.example.data.local.AppPreferences
import com.example.data.local.FamilyTrackDatabase
import com.example.data.local.NetworkEventEntity
import com.example.data.local.NetworkSessionEntity
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TimeZone
import java.util.UUID

data class NetworkSnapshot(
    val wifiAvailable: Boolean = false,
    val cellularAvailable: Boolean = false,
    val internetAvailable: Boolean = false,
    val validated: Boolean = false,
    val transportType: String = "OFFLINE"
)

data class NetworkDurationsSummary(
    val todayOnlineMs: Long,
    val sevenDayOnlineMs: Long,
    val todayWifiMs: Long,
    val todayCellularMs: Long
)

object NetworkActivityMonitor {
    private const val TAG = "NetworkActivityMonitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    @Volatile
    private var isRegistered = false
    @Volatile
    private var defaultCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var currentSnapshot = NetworkSnapshot()
    @Volatile
    private var activeSessionId: String? = null

    fun currentSnapshot(): NetworkSnapshot = currentSnapshot

    fun startMonitoringIfConsented(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            val prefs = AppPreferences(appCtx)
            val settings = prefs.settingsFlow.first()
            if (!settings.networkMonitoringConsent || !settings.networkMonitoringEnabled ||
                settings.activeFamilyId.isBlank() || settings.activeAdminUid.isBlank()
            ) {
                stopMonitoring(appCtx, logPauseEvent = false)
                return@launch
            }
            reconcileInterruptedSessions(appCtx, reason = "App restarted before previous network session closed")
            registerCallbacks(appCtx)
        }
    }

    fun stopMonitoring(context: Context, logPauseEvent: Boolean) {
        val appCtx = context.applicationContext
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            defaultCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            wifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            cellularCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
        defaultCallback = null
        wifiCallback = null
        cellularCallback = null
        isRegistered = false

        scope.launch {
            stateMutex.withLock {
                val nowMs = System.currentTimeMillis()
                closeActiveSessionLocked(
                    appCtx = appCtx,
                    nowMs = nowMs,
                    status = "COMPLETED",
                    reason = null
                )
                if (logPauseEvent) {
                    recordEventLocked(
                        appCtx = appCtx,
                        eventType = "MONITORING_PAUSED",
                        networkType = currentSnapshot.transportType,
                        connectionState = "PAUSED",
                        internetAvailable = currentSnapshot.internetAvailable,
                        sessionId = null,
                        details = "User paused consent-based network activity monitoring"
                    )
                }
            }
        }
    }

    private suspend fun registerCallbacks(appCtx: Context) {
        stateMutex.withLock {
            if (isRegistered) return
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val initial = readCurrentSnapshot(cm)
            currentSnapshot = initial

            val defCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleDefaultNetworkChange(appCtx, cm, lost = false)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    handleDefaultNetworkChange(appCtx, cm, lost = false, capsOverride = networkCapabilities)
                }

                override fun onLost(network: Network) {
                    handleDefaultNetworkChange(appCtx, cm, lost = true)
                }
            }

            val wifiReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val wifiCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleTransportChange(appCtx, isWifi = true, available = true)
                }

                override fun onLost(network: Network) {
                    handleTransportChange(appCtx, isWifi = true, available = false)
                }
            }

            val cellReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            val cellCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleTransportChange(appCtx, isWifi = false, available = true)
                }

                override fun onLost(network: Network) {
                    handleTransportChange(appCtx, isWifi = false, available = false)
                }
            }

            runCatching {
                cm.registerDefaultNetworkCallback(defCb)
                cm.registerNetworkCallback(wifiReq, wifiCb)
                cm.registerNetworkCallback(cellReq, cellCb)
                defaultCallback = defCb
                wifiCallback = wifiCb
                cellularCallback = cellCb
                isRegistered = true

                // Open initial active session if currently connected
                if (initial.internetAvailable && initial.transportType != "OFFLINE") {
                    val sessId = openActiveSessionLocked(appCtx, initial.transportType, System.currentTimeMillis())
                    recordEventLocked(
                        appCtx = appCtx,
                        eventType = "MONITORING_STARTED",
                        networkType = initial.transportType,
                        connectionState = if (initial.validated) "VALIDATED" else "CONNECTED",
                        internetAvailable = true,
                        sessionId = sessId,
                        details = "Background network monitoring started while ${initial.transportType} connected"
                    )
                } else {
                    recordEventLocked(
                        appCtx = appCtx,
                        eventType = "MONITORING_STARTED",
                        networkType = "OFFLINE",
                        connectionState = "DISCONNECTED",
                        internetAvailable = false,
                        sessionId = null,
                        details = "Background network monitoring started (currently offline)"
                    )
                }
            }.onFailure {
                Log.w(TAG, "Failed to register NetworkCallbacks", it)
            }
        }
    }

    private fun handleTransportChange(appCtx: Context, isWifi: Boolean, available: Boolean) {
        scope.launch {
            stateMutex.withLock {
                val prev = currentSnapshot
                val next = if (isWifi) prev.copy(wifiAvailable = available) else prev.copy(cellularAvailable = available)
                if (isWifi && prev.wifiAvailable == available) return@withLock
                if (!isWifi && prev.cellularAvailable == available) return@withLock
                currentSnapshot = next

                val evtType = when {
                    isWifi && available -> "WIFI_AVAILABLE"
                    isWifi && !available -> "WIFI_DISCONNECTED"
                    !isWifi && available -> "CELLULAR_AVAILABLE"
                    else -> "CELLULAR_DISCONNECTED"
                }
                val transport = if (isWifi) "WIFI" else "CELLULAR"
                recordEventLocked(
                    appCtx = appCtx,
                    eventType = evtType,
                    networkType = transport,
                    connectionState = if (available) "CONNECTED" else "DISCONNECTED",
                    internetAvailable = next.internetAvailable,
                    sessionId = activeSessionId,
                    details = if (available) "$transport transport became available" else "$transport transport disconnected"
                )
            }
        }
    }

    private fun handleDefaultNetworkChange(
        appCtx: Context,
        cm: ConnectivityManager,
        lost: Boolean,
        capsOverride: NetworkCapabilities? = null
    ) {
        scope.launch {
            stateMutex.withLock {
                val prev = currentSnapshot
                val next = if (lost) {
                    readCurrentSnapshot(cm).copy(internetAvailable = false, validated = false, transportType = "OFFLINE")
                } else {
                    readCurrentSnapshot(cm, capsOverride)
                }
                if (next == prev) return@withLock
                currentSnapshot = next
                val nowMs = System.currentTimeMillis()

                // 1. Internet availability transition
                if (!prev.internetAvailable && next.internetAvailable) {
                    val sessId = openActiveSessionLocked(appCtx, next.transportType, nowMs)
                    recordEventLocked(
                        appCtx = appCtx,
                        eventType = "INTERNET_AVAILABLE",
                        networkType = next.transportType,
                        connectionState = if (next.validated) "VALIDATED" else "CONNECTED",
                        internetAvailable = true,
                        sessionId = sessId,
                        details = "Internet capability became available on ${next.transportType}"
                    )
                    SyncScheduler.enqueueImmediateNetworkSync(appCtx)
                } else if (prev.internetAvailable && !next.internetAvailable) {
                    val closedId = activeSessionId
                    closeActiveSessionLocked(appCtx, nowMs, status = "COMPLETED", reason = null)
                    recordEventLocked(
                        appCtx = appCtx,
                        eventType = "INTERNET_UNAVAILABLE",
                        networkType = "OFFLINE",
                        connectionState = "DISCONNECTED",
                        internetAvailable = false,
                        sessionId = closedId,
                        details = "Internet connection became unavailable"
                    )
                }

                // 2. Transport switch (e.g., Wi-Fi <-> Cellular) while staying online
                if (prev.internetAvailable && next.internetAvailable && prev.transportType != next.transportType) {
                    closeActiveSessionLocked(appCtx, nowMs, status = "COMPLETED", reason = "Switched to ${next.transportType}")
                    val newSessId = openActiveSessionLocked(appCtx, next.transportType, nowMs)
                    val switchType = when {
                        prev.transportType == "WIFI" && next.transportType == "CELLULAR" -> "SWITCHED_WIFI_TO_CELLULAR"
                        prev.transportType == "CELLULAR" && next.transportType == "WIFI" -> "SWITCHED_CELLULAR_TO_WIFI"
                        else -> "NETWORK_CHANGED"
                    }
                    recordEventLocked(
                        appCtx = appCtx,
                        eventType = switchType,
                        networkType = next.transportType,
                        connectionState = if (next.validated) "VALIDATED" else "CONNECTED",
                        internetAvailable = true,
                        sessionId = newSessId,
                        details = "Switched from ${prev.transportType} to ${next.transportType}"
                    )
                    SyncScheduler.enqueueImmediateNetworkSync(appCtx)
                }

                // 3. Validated internet signal transition
                if (!prev.validated && next.validated && next.internetAvailable) {
                    recordEventLocked(
                        appCtx = appCtx,
                        eventType = "NETWORK_VALIDATED",
                        networkType = next.transportType,
                        connectionState = "VALIDATED",
                        internetAvailable = true,
                        sessionId = activeSessionId,
                        details = "OS validated actual internet reachability on ${next.transportType}"
                    )
                    SyncScheduler.enqueueImmediateNetworkSync(appCtx)
                }
            }
        }
    }

    suspend fun recordSyncLifecycleEvent(
        context: Context,
        eventType: String,
        details: String
    ) {
        val appCtx = context.applicationContext
        stateMutex.withLock {
            val snap = currentSnapshot
            val state = when (eventType) {
                "SYNC_STARTED" -> "SYNCING"
                "SYNC_COMPLETED" -> "CONNECTED"
                "SYNC_FAILED" -> "FAILED"
                else -> "UNKNOWN"
            }
            recordEventLocked(
                appCtx = appCtx,
                eventType = eventType,
                networkType = snap.transportType,
                connectionState = state,
                internetAvailable = snap.internetAvailable,
                sessionId = activeSessionId,
                details = details
            )
        }
    }

    suspend fun recordDeviceBootEvent(context: Context) {
        val appCtx = context.applicationContext
        reconcileInterruptedSessions(appCtx, reason = "Device rebooted")
        stateMutex.withLock {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val snap = if (cm != null) readCurrentSnapshot(cm) else NetworkSnapshot()
            currentSnapshot = snap
            recordEventLocked(
                appCtx = appCtx,
                eventType = "DEVICE_BOOTED",
                networkType = snap.transportType,
                connectionState = "BOOT",
                internetAvailable = snap.internetAvailable,
                sessionId = null,
                details = "Device boot completed; network listener re-initialized"
            )
        }
    }

    suspend fun reconcileInterruptedSessions(context: Context, reason: String) {
        val appCtx = context.applicationContext
        val prefs = AppPreferences(appCtx)
        val deviceId = prefs.getOrCreateDeviceId()
        val dao = FamilyTrackDatabase.getInstance(appCtx).networkSessionDao()
        stateMutex.withLock {
            val open = dao.getOpenActiveSessions(deviceId)
            for (sess in open) {
                if (sess.id != activeSessionId) {
                    val endMs = maxOf(sess.startedAtMs, sess.lastVerifiedAtMs)
                    val duration = (endMs - sess.startedAtMs).coerceAtLeast(0L)
                    dao.upsert(
                        sess.copy(
                            endedAtMs = endMs,
                            durationMs = duration,
                            status = "INTERRUPTED",
                            interruptionReason = reason.take(200),
                            synced = false
                        )
                    )
                }
            }
        }
    }

    suspend fun heartbeatActiveSession(context: Context) {
        val appCtx = context.applicationContext
        val prefs = AppPreferences(appCtx)
        val deviceId = prefs.getOrCreateDeviceId()
        val dao = FamilyTrackDatabase.getInstance(appCtx).networkSessionDao()
        stateMutex.withLock {
            val liveId = activeSessionId ?: return@withLock
            val open = dao.getOpenActiveSessions(deviceId).firstOrNull { it.id == liveId } ?: return@withLock
            val nowMs = System.currentTimeMillis()
            dao.upsert(
                open.copy(
                    lastVerifiedAtMs = nowMs,
                    durationMs = (nowMs - open.startedAtMs).coerceAtLeast(0L),
                    synced = false
                )
            )
        }
    }

    private suspend fun openActiveSessionLocked(
        appCtx: Context,
        networkType: String,
        nowMs: Long
    ): String? {
        val settings = AppPreferences(appCtx).settingsFlow.first()
        val uid = Firebase.auth.currentUser?.uid ?: return null
        if (!settings.networkMonitoringConsent || !settings.networkMonitoringEnabled ||
            settings.activeFamilyId.isBlank() || settings.activeAdminUid.isBlank()
        ) return null
        val deviceId = AppPreferences(appCtx).getOrCreateDeviceId()
        val sessionId = "ns_${deviceId.take(10)}_${nowMs}"
        val tzId = TimeZone.getDefault().id
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80).ifBlank { "Android Device" }

        val session = NetworkSessionEntity(
            id = sessionId,
            familyId = settings.activeFamilyId,
            adminUid = settings.activeAdminUid,
            ownerUid = uid,
            deviceId = deviceId,
            deviceName = deviceName,
            networkType = if (networkType in listOf("WIFI", "CELLULAR")) networkType else "OTHER",
            startedAtMs = nowMs,
            endedAtMs = null,
            durationMs = 0L,
            status = "ACTIVE",
            lastVerifiedAtMs = nowMs,
            interruptionReason = null,
            timezoneId = tzId,
            synced = false
        )
        FamilyTrackDatabase.getInstance(appCtx).networkSessionDao().upsert(session)
        activeSessionId = sessionId
        return sessionId
    }

    private suspend fun closeActiveSessionLocked(
        appCtx: Context,
        nowMs: Long,
        status: String,
        reason: String?
    ) {
        val liveId = activeSessionId ?: return
        val deviceId = AppPreferences(appCtx).getOrCreateDeviceId()
        val dao = FamilyTrackDatabase.getInstance(appCtx).networkSessionDao()
        val existing = dao.getOpenActiveSessions(deviceId).firstOrNull { it.id == liveId }
        if (existing != null) {
            val ended = maxOf(existing.startedAtMs, nowMs)
            dao.upsert(
                existing.copy(
                    endedAtMs = ended,
                    durationMs = (ended - existing.startedAtMs).coerceAtLeast(0L),
                    status = status,
                    lastVerifiedAtMs = ended,
                    interruptionReason = reason?.take(200),
                    synced = false
                )
            )
        }
        activeSessionId = null
    }

    private suspend fun recordEventLocked(
        appCtx: Context,
        eventType: String,
        networkType: String,
        connectionState: String,
        internetAvailable: Boolean,
        sessionId: String?,
        details: String?
    ) {
        val prefs = AppPreferences(appCtx)
        val settings = prefs.settingsFlow.first()
        val uid = Firebase.auth.currentUser?.uid ?: return
        if (!settings.networkMonitoringConsent || !settings.networkMonitoringEnabled ||
            settings.activeFamilyId.isBlank() || settings.activeAdminUid.isBlank()
        ) return

        val deviceId = prefs.getOrCreateDeviceId()
        val nowMs = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val offsetMins = tz.getOffset(nowMs) / 60_000
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80).ifBlank { "Android Device" }

        val entity = NetworkEventEntity(
            id = "ne_" + UUID.randomUUID().toString().replace("-", "").take(20),
            familyId = settings.activeFamilyId,
            adminUid = settings.activeAdminUid,
            ownerUid = uid,
            deviceId = deviceId,
            deviceName = deviceName,
            eventType = eventType,
            networkType = if (networkType in listOf("WIFI", "CELLULAR", "OFFLINE")) networkType else "OTHER",
            connectionState = connectionState.take(40),
            internetAvailable = internetAvailable,
            eventTimestampMs = nowMs,
            detectedAtMs = nowMs,
            timezoneId = tz.id.take(64),
            timezoneOffsetMinutes = offsetMins,
            sessionId = sessionId,
            details = details?.take(300),
            synced = false
        )
        FamilyTrackDatabase.getInstance(appCtx).networkEventDao().insertEvent(entity)
    }

    fun readCurrentSnapshot(cm: ConnectivityManager, capsOverride: NetworkCapabilities? = null): NetworkSnapshot {
        val activeNetwork = cm.activeNetwork
        val caps = capsOverride ?: activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val transport = when {
            isWifi -> "WIFI"
            isCell -> "CELLULAR"
            internet -> "OTHER"
            else -> "OFFLINE"
        }
        return NetworkSnapshot(
            wifiAvailable = isWifi || currentSnapshot.wifiAvailable,
            cellularAvailable = isCell || currentSnapshot.cellularAvailable,
            internetAvailable = internet,
            validated = validated,
            transportType = transport
        )
    }

    /**
     * Computes pure, honest online durations for today and last 7 days from observed sessions.
     * Never invents missing intervals when a session was interrupted without verification.
     */
    fun computeDurations(
        sessions: List<com.example.data.model.NetworkSessionModel>,
        nowMs: Long = System.currentTimeMillis()
    ): NetworkDurationsSummary {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfTodayMs = cal.timeInMillis
        val sevenDaysAgoMs = nowMs - 7L * 24L * 60L * 60L * 1000L

        var todayOnline = 0L
        var sevenDayOnline = 0L
        var todayWifi = 0L
        var todayCellular = 0L

        for (s in sessions) {
            if (s.startedAtMs <= 0L) continue
            val effectiveEnd = when (s.status) {
                "COMPLETED" -> s.endedAtMs ?: s.lastVerifiedAtMs
                "INTERRUPTED" -> s.endedAtMs ?: s.lastVerifiedAtMs
                "ACTIVE" -> {
                    // Only count active session up to now if verified within 45 min; otherwise cap at lastVerifiedAtMs
                    if (nowMs - s.lastVerifiedAtMs <= 45 * 60 * 1000L) nowMs else maxOf(s.startedAtMs, s.lastVerifiedAtMs)
                }
                else -> s.endedAtMs ?: s.lastVerifiedAtMs
            }.coerceAtLeast(s.startedAtMs)

            val overlap7d = (minOf(effectiveEnd, nowMs) - maxOf(s.startedAtMs, sevenDaysAgoMs)).coerceAtLeast(0L)
            sevenDayOnline += overlap7d

            val overlapToday = (minOf(effectiveEnd, nowMs) - maxOf(s.startedAtMs, startOfTodayMs)).coerceAtLeast(0L)
            if (overlapToday > 0L) {
                todayOnline += overlapToday
                when (s.networkType) {
                    "WIFI" -> todayWifi += overlapToday
                    "CELLULAR" -> todayCellular += overlapToday
                }
            }
        }
        return NetworkDurationsSummary(
            todayOnlineMs = todayOnline,
            sevenDayOnlineMs = sevenDayOnline,
            todayWifiMs = todayWifi,
            todayCellularMs = todayCellular
        )
    }
}
