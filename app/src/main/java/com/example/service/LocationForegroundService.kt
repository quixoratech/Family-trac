package com.example.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.data.local.AppPreferences
import com.example.data.local.ConsentLogEntity
import com.example.data.local.FamilyTrackDatabase
import com.example.data.local.LocationRecordEntity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class LocationForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fusedClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var hourlyLoopJob: Job? = null
    private var lastSavedLat: Double? = null
    private var lastSavedLon: Double? = null
    private var lastSavedAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_SHARING) {
            serviceScope.launch {
                val prefs = AppPreferences(applicationContext)
                val settings = prefs.settingsFlow.first()
                prefs.setLocationSharingEnabled(false)
                val uid = Firebase.auth.currentUser?.uid.orEmpty()
                if (uid.isNotBlank() && settings.activeFamilyId.isNotBlank()) {
                    FamilyTrackDatabase.getInstance(applicationContext).consentLogDao().insert(
                        ConsentLogEntity(
                            id = UUID.randomUUID().toString(),
                            userId = uid,
                            familyId = settings.activeFamilyId,
                            deviceId = settings.deviceId,
                            action = "PAUSED",
                            details = "User paused sharing from persistent notification",
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                    SyncScheduler.enqueueImmediateLocationSync(applicationContext)
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (!LocationCaptureHelper.hasForegroundLocationPermission(this)) {
            Log.w(TAG, "Location permission missing; stopping LocationForegroundService")
            stopSelf()
            return START_NOT_STICKY
        }

        val notif = NotificationHelper.buildLocationForegroundNotification(
            this,
            "Hourly + movement sync active • Tap Pause anytime"
        )
        val startedFg = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NotificationHelper.NOTIF_ID_LOCATION_FGS,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NotificationHelper.NOTIF_ID_LOCATION_FGS, notif)
            }
        }.isSuccess

        if (!startedFg) {
            Log.w(TAG, "Foreground service start blocked by OS policy; relying on WorkManager sync")
            stopSelf()
            return START_NOT_STICKY
        }

        startLocationUpdates()
        startHourlyHeartbeat()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val client = fusedClient ?: return
        if (locationCallback != null) return

        // Battery-efficient balanced accuracy: 30-min interval, min 75m displacement
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30 * 60 * 1000L)
            .setMinUpdateIntervalMillis(10 * 60 * 1000L)
            .setMinUpdateDistanceMeters(75f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                serviceScope.launch {
                    recordAndQueueLocation(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = if (loc.hasAccuracy()) loc.accuracy else 40f,
                        altitude = if (loc.hasAltitude()) loc.altitude else null,
                        speed = if (loc.hasSpeed()) loc.speed else null,
                        source = "FUSED"
                    )
                }
            }
        }
        locationCallback = callback
        runCatching {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        }.onFailure {
            Log.w(TAG, "Fused location update registration failed: ${it.message}")
        }
    }

    private fun startHourlyHeartbeat() {
        if (hourlyLoopJob?.isActive == true) return
        hourlyLoopJob = serviceScope.launch {
            while (true) {
                val settings = AppPreferences(applicationContext).settingsFlow.first()
                if (!settings.consentGranted || !settings.locationSharingEnabled) {
                    stopSelf()
                    break
                }
                val captured = LocationCaptureHelper.captureBestLocation(applicationContext)
                if (captured != null) {
                    recordAndQueueLocation(
                        latitude = captured.latitude,
                        longitude = captured.longitude,
                        accuracy = captured.accuracyMeters,
                        altitude = captured.altitudeMeters,
                        speed = captured.speedMps,
                        source = captured.source
                    )
                }
                val intervalMs = settings.locationIntervalMinutes.coerceIn(15, 120) * 60L * 1000L
                delay(intervalMs)
            }
        }
    }

    private suspend fun recordAndQueueLocation(
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        altitude: Double?,
        speed: Float?,
        source: String
    ) {
        val prefs = AppPreferences(applicationContext)
        val settings = prefs.settingsFlow.first()
        val uid = Firebase.auth.currentUser?.uid ?: return
        if (settings.activeFamilyId.isBlank() || settings.activeAdminUid.isBlank()) return
        if (!settings.consentGranted || !settings.locationSharingEnabled) return

        val nowMs = System.currentTimeMillis()
        val prevLat = lastSavedLat
        val prevLon = lastSavedLon
        val elapsedMs = nowMs - lastSavedAtMs
        val movedMeters = if (prevLat != null && prevLon != null) {
            LocationCaptureHelper.distanceMeters(prevLat, prevLon, latitude, longitude)
        } else {
            Double.MAX_VALUE
        }
        val minElapsedMs = (settings.locationIntervalMinutes.coerceIn(15, 120) - 2).coerceAtLeast(10) * 60L * 1000L
        if (elapsedMs < minElapsedMs && (movedMeters < 75.0 || elapsedMs < 2L * 60L * 1000L)) {
            return
        }

        val deviceId = prefs.getOrCreateDeviceId()
        val battery = LocationCaptureHelper.getBatteryPercent(applicationContext)
        val network = LocationCaptureHelper.getNetworkType(applicationContext)

        val entity = LocationRecordEntity(
            id = UUID.randomUUID().toString(),
            familyId = settings.activeFamilyId,
            adminUid = settings.activeAdminUid,
            ownerUid = uid,
            deviceId = deviceId,
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy,
            altitudeMeters = altitude,
            speedMps = speed,
            batteryPercent = battery,
            networkType = network,
            source = source,
            recordedAtMs = nowMs,
            synced = false
        )

        FamilyTrackDatabase.getInstance(applicationContext).locationRecordDao().insertRecord(entity)
        lastSavedLat = latitude
        lastSavedLon = longitude
        lastSavedAtMs = nowMs

        SyncScheduler.enqueueImmediateLocationSync(applicationContext)
    }

    override fun onDestroy() {
        locationCallback?.let { cb ->
            runCatching { fusedClient?.removeLocationUpdates(cb) }
        }
        hourlyLoopJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LocationFgs"
        const val ACTION_PAUSE_SHARING = "com.example.action.PAUSE_SHARING"

        suspend fun startIfPermitted(context: Context) {
            // Enforce the user's current opt-in at the service boundary as well as in the UI.
            // This prevents stale boot/work intents from starting location collection after pause/revoke.
            val settings = AppPreferences(context.applicationContext).settingsFlow.first()
            if (!settings.consentGranted || !settings.locationSharingEnabled) return
            if (settings.activeFamilyId.isBlank() || settings.activeAdminUid.isBlank()) return
            if (!LocationCaptureHelper.hasForegroundLocationPermission(context)) return
            val intent = Intent(context, LocationForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // On Android 12-15 background restrictions, safely fall back to WorkManager sync
                Log.w(TAG, "Unable to start foreground service: ${e.message}")
                SyncScheduler.enqueueImmediateLocationSync(context)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LocationForegroundService::class.java)) }
        }
    }
}
