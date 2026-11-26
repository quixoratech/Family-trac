package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class CapturedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val speedMps: Float?,
    val source: String,
    val timestampMs: Long
)

object LocationCaptureHelper {
    fun hasForegroundLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            hasForegroundLocationPermission(context)
        }
    }

    fun isGpsEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    fun getBatteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val prop = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (prop != null && prop in 0..100) return prop
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 100
    }

    fun getNetworkType(context: Context): String {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "OFFLINE"
            val network = cm.activeNetwork ?: return "OFFLINE"
            val caps = cm.getNetworkCapabilities(network) ?: return "OFFLINE"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "WIFI"
                else -> "OFFLINE"
            }
        }.getOrDefault("OFFLINE")
    }

    @SuppressLint("MissingPermission")
    suspend fun captureBestLocation(context: Context): CapturedLocation? {
        if (!hasForegroundLocationPermission(context)) return null

        // 1. Primary: Google FusedLocationProviderClient with 8s timeout (won't hang on older phones)
        val fusedResult = withTimeoutOrNull(8_000L) {
            val cts = CancellationTokenSource()
            try {
                runCatching {
                    val client = LocationServices.getFusedLocationProviderClient(context)
                    val loc = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).await()
                        ?: client.lastLocation.await()
                    loc?.toCaptured("FUSED")
                }.getOrNull()
            } finally {
                cts.cancel()
            }
        }

        if (fusedResult != null && fusedResult.accuracyMeters <= 250f) {
            return fusedResult
        }

        // 2. Fallback: Android native LocationManager (active single fix + cached GPS/Network for Android 7-15)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val activeNative = if (lm != null) {
            val provider = when {
                runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> LocationManager.GPS_PROVIDER
                runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) {
                withTimeoutOrNull(6_000L) {
                    suspendCancellableCoroutine<CapturedLocation?> { cont ->
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        runCatching {
                            LocationManagerCompat.getCurrentLocation(
                                lm,
                                provider,
                                signal,
                                ContextCompat.getMainExecutor(context)
                            ) { loc ->
                                if (cont.isActive) {
                                    cont.resume(loc?.toCaptured("NATIVE_$provider"))
                                }
                            }
                        }.onFailure {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
            } else null
        } else null

        val gpsLoc = runCatching { lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.toCaptured("GPS_PROVIDER") }.getOrNull()
        val netLoc = runCatching { lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.toCaptured("NETWORK_PROVIDER") }.getOrNull()

        return listOfNotNull(fusedResult, activeNative, gpsLoc, netLoc)
            .filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
            .minByOrNull { it.accuracyMeters }
    }

    private fun Location.toCaptured(source: String): CapturedLocation {
        return CapturedLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else 50f,
            altitudeMeters = if (hasAltitude()) altitude else null,
            speedMps = if (hasSpeed()) speed else null,
            source = source,
            timestampMs = System.currentTimeMillis()
        )
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    fun formatDistance(meters: Double): String {
        return if (meters < 1000.0) {
            "${meters.toInt()} m"
        } else {
            String.format("%.2f km", meters / 1000.0)
        }
    }

    /**
     * Distinguishes LIVE LOCATION (captured <= 5 minutes ago) from LAST KNOWN LOCATION.
     */
    fun isLocationLive(locationTimestampMs: Long?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (locationTimestampMs == null || locationTimestampMs <= 0L) return false
        return (nowMs - locationTimestampMs) <= 5L * 60L * 1000L
    }

    fun formatRelativeTime(timestampMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (timestampMs == null || timestampMs <= 0L) return "Never"
        val diffSec = ((nowMs - timestampMs) / 1000L).coerceAtLeast(0L)
        return when {
            diffSec < 60 -> "Just now"
            diffSec < 3600 -> "${diffSec / 60}m ago"
            diffSec < 86400 -> "${diffSec / 3600}h ago"
            else -> "${diffSec / 86400}d ago"
        }
    }
}
