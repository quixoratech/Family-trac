package com.example.service

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.AppPreferences
import com.example.data.local.FamilyTrackDatabase
import com.example.data.local.SyncStatusEntity
import com.example.data.remote.CommunicationRepository
import com.example.data.remote.FamilyHubRepository
import com.example.data.remote.FamilyRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

class LocationSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val user = Firebase.auth.currentUser ?: return Result.success()
        val db = FamilyTrackDatabase.getInstance(applicationContext)
        val locDao = db.locationRecordDao()
        val prefs = AppPreferences(applicationContext)
        val settings = prefs.settingsFlow.first()

        if (settings.activeFamilyId.isBlank() || settings.activeAdminUid.isBlank()) {
            return Result.success()
        }

        if (settings.networkMonitoringConsent && settings.networkMonitoringEnabled) {
            NetworkActivityMonitor.recordSyncLifecycleEvent(
                applicationContext,
                "SYNC_STARTED",
                "Background location & network sync started"
            )
            NetworkActivityMonitor.heartbeatActiveSession(applicationContext)
        }

        val repo = FamilyRepository(applicationContext)
        val pending = locDao.getPendingBatch()
        var hadFailure = false

        for (record in pending) {
            val uploadResult = repo.uploadLocationRecordIdempotent(
                recordId = record.id,
                familyId = record.familyId,
                adminUid = record.adminUid,
                deviceId = record.deviceId,
                latitude = record.latitude,
                longitude = record.longitude,
                accuracyMeters = record.accuracyMeters.toDouble(),
                altitudeMeters = record.altitudeMeters,
                speedMps = record.speedMps?.toDouble(),
                batteryPercent = record.batteryPercent,
                networkType = record.networkType,
                source = record.source,
                recordedAtMs = record.recordedAtMs
            )
            if (uploadResult.isSuccess) {
                locDao.markSynced(record.id)
            } else {
                hadFailure = true
                locDao.markSyncFailed(record.id, uploadResult.exceptionOrNull()?.message)
            }
        }

        val netOk = SyncScheduler.flushNetworkEventsAndSessions(applicationContext)
        if (!netOk) hadFailure = true

        // Update device status heartbeat & latest synced coordinates + live network telemetry
        val deviceId = prefs.getOrCreateDeviceId()
        val latest = locDao.getLatestForDevice(deviceId)
        val latestNetEvt = db.networkEventDao().getLatestForDevice(deviceId)
        val activeSessions = db.networkSessionDao().getOpenActiveSessions(deviceId)
        val activeSess = activeSessions.firstOrNull()
        val snap = NetworkActivityMonitor.currentSnapshot()
        val netType = LocationCaptureHelper.getNetworkType(applicationContext)
        val nowMs = System.currentTimeMillis()

        val deviceUpdateResult = repo.updateOwnDeviceStatus(
            familyId = settings.activeFamilyId,
            deviceId = deviceId,
            consentGranted = settings.consentGranted,
            locationSharingEnabled = settings.locationSharingEnabled,
            locationPermissionGranted = LocationCaptureHelper.hasForegroundLocationPermission(applicationContext),
            backgroundPermissionGranted = LocationCaptureHelper.hasBackgroundLocationPermission(applicationContext),
            batteryPercent = LocationCaptureHelper.getBatteryPercent(applicationContext),
            networkType = netType,
            online = netType != "OFFLINE",
            lastLatitude = latest?.latitude,
            lastLongitude = latest?.longitude,
            lastAccuracyMeters = latest?.accuracyMeters?.toDouble(),
            lastLocationAtMs = latest?.recordedAtMs,
            lastSyncAtMs = if (!hadFailure) nowMs else null,
            networkConsentGranted = settings.networkMonitoringConsent,
            networkMonitoringEnabled = settings.networkMonitoringEnabled,
            currentWifiAvailable = snap.wifiAvailable || netType == "WIFI",
            currentCellularAvailable = snap.cellularAvailable || netType == "CELLULAR",
            internetAvailable = snap.internetAvailable || netType != "OFFLINE",
            currentNetworkSessionId = activeSess?.id.orEmpty(),
            currentSessionStartedAtMs = activeSess?.startedAtMs,
            lastNetworkEventType = latestNetEvt?.eventType,
            lastNetworkEventAtMs = latestNetEvt?.detectedAtMs,
            lastActiveAtMs = nowMs
        )

        if (!hadFailure && deviceUpdateResult.isSuccess) {
            prefs.setLastSyncMs(nowMs)
            db.syncStatusDao().upsert(
                SyncStatusEntity(
                    lastSuccessfulSyncMs = nowMs,
                    lastSyncAttemptMs = nowMs,
                    lastError = null
                )
            )
            if (settings.networkMonitoringConsent && settings.networkMonitoringEnabled) {
                NetworkActivityMonitor.recordSyncLifecycleEvent(
                    applicationContext,
                    "SYNC_COMPLETED",
                    "Location & network telemetry sync completed"
                )
                SyncScheduler.flushNetworkEventsAndSessions(applicationContext)
            }
        } else {
            val ownDevices = runCatching {
                repo.observeOwnDevice(settings.activeFamilyId, user.uid).first()
            }.getOrDefault(emptyList())
            val isRevoked = ownDevices.firstOrNull { it.id == deviceId }?.revoked == true
            if (isRevoked) {
                prefs.setConsentAndSharing(consentGranted = false, sharingEnabled = false)
                prefs.setNetworkMonitoringEnabled(false)
                LocationForegroundService.stop(applicationContext)
                NetworkActivityMonitor.stopMonitoring(applicationContext, logPauseEvent = false)
                return Result.success()
            }
            val errMsg = deviceUpdateResult.exceptionOrNull()?.message ?: "Pending retry"
            db.syncStatusDao().upsert(
                SyncStatusEntity(
                    lastSuccessfulSyncMs = settings.lastSyncMs,
                    lastSyncAttemptMs = nowMs,
                    lastError = errMsg
                )
            )
            if (settings.networkMonitoringConsent && settings.networkMonitoringEnabled) {
                NetworkActivityMonitor.recordSyncLifecycleEvent(
                    applicationContext,
                    "SYNC_FAILED",
                    "Sync error: ${errMsg.take(120)}"
                )
            }
        }

        val sevenDaysAgoMs = nowMs - 7L * 24L * 60L * 60L * 1000L
        locDao.pruneSyncedOlderThan(sevenDaysAgoMs)
        val thirtyDaysAgoMs = nowMs - 30L * 24L * 60L * 60L * 1000L
        db.networkEventDao().pruneSyncedOlderThan(thirtyDaysAgoMs)
        db.networkSessionDao().pruneOldSynced(thirtyDaysAgoMs)

        SyncScheduler.flushOfflineComms(applicationContext, user.uid)

        return if (hadFailure || deviceUpdateResult.isFailure) Result.retry() else Result.success()
    }
}

class NetworkSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val user = Firebase.auth.currentUser ?: return Result.success()
        val settings = AppPreferences(applicationContext).settingsFlow.first()
        if (settings.activeFamilyId.isBlank()) return Result.success()
        if (settings.networkMonitoringConsent && settings.networkMonitoringEnabled) {
            NetworkActivityMonitor.heartbeatActiveSession(applicationContext)
        }
        val ok = SyncScheduler.flushNetworkEventsAndSessions(applicationContext)
        val commsOk = SyncScheduler.flushOfflineComms(applicationContext, user.uid)
        return if (ok && commsOk) Result.success() else Result.retry()
    }
}

class CommunicationSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val uid = Firebase.auth.currentUser?.uid ?: return Result.success()
        val ok = SyncScheduler.flushOfflineComms(applicationContext, uid)
        return if (ok) Result.success() else Result.retry()
    }
}

object SyncScheduler {
    private const val PERIODIC_LOCATION_WORK = "familytrack_periodic_location_sync"
    private const val IMMEDIATE_LOCATION_WORK = "familytrack_immediate_location_sync"
    private const val IMMEDIATE_NETWORK_WORK = "familytrack_immediate_network_sync"
    private const val IMMEDIATE_COMMS_WORK = "familytrack_immediate_comms_sync"

    private val connectedConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodicSync(context: Context, intervalMinutes: Int = 30, replace: Boolean = false) {
        val safeMinutes = intervalMinutes.coerceIn(15, 120).toLong()
        val request = PeriodicWorkRequestBuilder<LocationSyncWorker>(safeMinutes, TimeUnit.MINUTES)
            .setConstraints(connectedConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_LOCATION_WORK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun enqueueImmediateLocationSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setConstraints(connectedConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_LOCATION_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun enqueueImmediateNetworkSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<NetworkSyncWorker>()
            .setConstraints(connectedConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_NETWORK_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun enqueueImmediateCommsSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<CommunicationSyncWorker>()
            .setConstraints(connectedConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_COMMS_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    suspend fun flushNetworkEventsAndSessions(context: Context): Boolean {
        if (Firebase.auth.currentUser == null) return true
        val db = FamilyTrackDatabase.getInstance(context)
        val hubRepo = FamilyHubRepository(context)
        var allOk = true

        val pendingSessions = db.networkSessionDao().getPendingSyncSessions()
        for (sess in pendingSessions) {
            val res = hubRepo.upsertNetworkSession(sess)
            if (res.isSuccess) {
                db.networkSessionDao().markSynced(sess.id)
            } else {
                allOk = false
            }
        }

        val pendingEvents = db.networkEventDao().getPendingBatch()
        for (evt in pendingEvents) {
            val res = hubRepo.uploadNetworkEventIdempotent(evt)
            if (res.isSuccess) {
                db.networkEventDao().markSynced(evt.id)
            } else {
                allOk = false
                db.networkEventDao().markSyncFailed(evt.id, res.exceptionOrNull()?.message)
            }
        }
        return allOk
    }

    suspend fun flushOfflineComms(context: Context, currentUid: String): Boolean {
        val db = FamilyTrackDatabase.getInstance(context)
        val msgDao = db.pendingMessageDao()
        val commRepo = CommunicationRepository(context)
        var allOk = true

        for (pending in msgDao.getUnsyncedMessages()) {
            if (pending.senderUid != currentUid) continue
            val voiceBase64 = pending.voiceFilePath?.let { path ->
                runCatching {
                    val file = File(path)
                    if (file.exists() && file.length() <= 400_000L) {
                        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    } else null
                }.getOrNull()
            }
            val mediaBase64 = pending.mediaFilePath?.let { path ->
                runCatching {
                    val file = File(path)
                    if (file.exists() && file.length() <= 550_000L) {
                        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    } else null
                }.getOrNull()
            }
            val result = commRepo.sendMessageIdempotent(
                messageId = pending.id,
                familyId = pending.familyId,
                conversationId = pending.conversationId,
                senderName = pending.senderName,
                participantUids = pending.participantUidsCsv.split(",").filter { it.isNotBlank() },
                type = pending.type,
                text = pending.text,
                voiceAudioBase64 = voiceBase64,
                voiceDurationMs = if (pending.voiceDurationMs > 0) pending.voiceDurationMs else null,
                mediaBase64 = mediaBase64,
                fileName = pending.fileName,
                mimeType = pending.mimeType,
                fileSizeBytes = if (pending.fileSizeBytes > 0L) pending.fileSizeBytes else null,
                replyToMessageId = pending.replyToMessageId,
                replyToPreview = pending.replyToPreview,
                clientCreatedAtMs = pending.clientCreatedAtMs
            )
            if (result.isSuccess) {
                msgDao.markSynced(pending.id)
            } else {
                allOk = false
                msgDao.markFailed(pending.id, result.exceptionOrNull()?.message)
                Log.w("SyncScheduler", "Pending message sync failed: ${result.exceptionOrNull()?.message}")
            }
        }
        return allOk
    }
}
