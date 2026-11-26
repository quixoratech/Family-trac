package com.example.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.LocationRecord
import com.example.data.model.PairedDevice
import com.example.service.LocationCaptureHelper
import com.example.ui.i18n.stringsFor
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.StatusLiveGreen
import com.example.ui.theme.StatusStaleAmber
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AdminMapScreen(
    language: String,
    devices: List<PairedDevice>,
    adminCoordinates: Pair<Double, Double>?,
    onRefreshAdminCoordinates: () -> Unit
) {
    val strings = stringsFor(language)
    val context = LocalContext.current
    val mappedDevices = devices.filter { !it.revoked && it.lastLatitude != null && it.lastLongitude != null }
    var selectedDeviceId by remember(mappedDevices) { mutableStateOf(mappedDevices.firstOrNull()?.id) }
    val selectedDevice = mappedDevices.firstOrNull { it.id == selectedDeviceId } ?: mappedDevices.firstOrNull()

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.5)
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(selectedDevice, adminCoordinates, mappedDevices) {
        mapView.overlays.clear()
        adminCoordinates?.let { (lat, lon) ->
            val adminMarker = Marker(mapView).apply {
                position = GeoPoint(lat, lon)
                title = "Admin Phone (You)"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(adminMarker)
        }

        mappedDevices.forEach { dev ->
            val lat = dev.lastLatitude ?: return@forEach
            val lon = dev.lastLongitude ?: return@forEach
            val pt = GeoPoint(lat, lon)
            val isLive = LocationCaptureHelper.isLocationLive(dev.lastLocationAtMs)

            // Accuracy circle overlay
            val accMeters = dev.lastAccuracyMeters ?: 30.0
            val circle = Polygon().apply {
                points = Polygon.pointsAsCircle(pt, accMeters.coerceIn(10.0, 2000.0))
                fillPaint.color = android.graphics.Color.argb(36, 15, 118, 110)
                outlinePaint.color = android.graphics.Color.argb(140, 15, 118, 110)
                outlinePaint.strokeWidth = 2f
            }
            mapView.overlays.add(circle)

            val marker = Marker(mapView).apply {
                position = pt
                title = "${dev.deviceName} (${if (isLive) "LIVE" else "LAST KNOWN"})"
                snippet = "${dev.ownerName} • ±${accMeters.toInt()}m"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(marker)
        }

        val focusLat = selectedDevice?.lastLatitude ?: adminCoordinates?.first ?: 23.8103
        val focusLon = selectedDevice?.lastLongitude ?: adminCoordinates?.second ?: 90.4125
        mapView.controller.animateTo(GeoPoint(focusLat, focusLon))
        mapView.invalidate()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        if (mappedDevices.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mappedDevices, key = { it.id }) { dev ->
                    FilterChip(
                        selected = dev.id == selectedDevice?.id,
                        onClick = { selectedDeviceId = dev.id },
                        label = { Text(dev.deviceName) }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = onRefreshAdminCoordinates,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 148.dp)
                .testTag("refresh_map_location_fab")
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Center on my location")
        }

        selectedDevice?.let { dev ->
            val isLive = LocationCaptureHelper.isLocationLive(dev.lastLocationAtMs)
            val dist = if (adminCoordinates != null && dev.lastLatitude != null && dev.lastLongitude != null) {
                LocationCaptureHelper.formatDistance(
                    LocationCaptureHelper.distanceMeters(
                        adminCoordinates.first, adminCoordinates.second,
                        dev.lastLatitude, dev.lastLongitude
                    )
                )
            } else "—"

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(dev.deviceName, style = MaterialTheme.typography.titleMedium)
                        Surface(
                            color = (if (isLive) StatusLiveGreen else StatusStaleAmber).copy(alpha = 0.16f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = if (isLive) strings.liveLocationBadge else strings.lastKnownBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isLive) StatusLiveGreen else StatusStaleAmber,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Text(
                        "%.5f, %.5f • ±${(dev.lastAccuracyMeters ?: 0.0).toInt()}m".format(
                            dev.lastLatitude ?: 0.0, dev.lastLongitude ?: 0.0
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFontFamily
                    )
                    Text(
                        "${strings.distanceFromYou}: $dist • ${LocationCaptureHelper.formatRelativeTime(dev.lastLocationAtMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun LocationHistoryScreen(
    language: String,
    devices: List<PairedDevice>,
    records: List<LocationRecord>,
    onExportHistory: (String?) -> Unit = {}
) {
    val strings = stringsFor(language)
    val context = LocalContext.current
    var selectedDeviceId by remember(devices) { mutableStateOf(devices.firstOrNull()?.id.orEmpty()) }
    val deviceRecords = remember(records, selectedDeviceId) {
        val filtered = if (selectedDeviceId.isBlank()) records else records.filter { it.deviceId == selectedDeviceId }
        filtered.sortedBy { it.recordedAtMs }
    }

    var playbackIndex by remember(deviceRecords) { mutableIntStateOf((deviceRecords.size - 1).coerceAtLeast(0)) }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(isPlaying, deviceRecords) {
        if (!isPlaying || deviceRecords.isEmpty()) return@LaunchedEffect
        var idx = 0
        while (isPlaying && idx < deviceRecords.size) {
            playbackIndex = idx
            delay(900L)
            idx++
        }
        isPlaying = false
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.5)
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(deviceRecords, playbackIndex) {
        mapView.overlays.clear()
        if (deviceRecords.isNotEmpty()) {
            val line = Polyline().apply {
                setPoints(deviceRecords.map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.color = android.graphics.Color.rgb(15, 118, 110)
                outlinePaint.strokeWidth = 7f
                outlinePaint.strokeCap = Paint.Cap.ROUND
            }
            mapView.overlays.add(line)

            val current = deviceRecords.getOrNull(playbackIndex.coerceIn(0, deviceRecords.lastIndex))
            if (current != null) {
                val pt = GeoPoint(current.latitude, current.longitude)
                val marker = Marker(mapView).apply {
                    position = pt
                    title = formatTimestamp(current.recordedAtMs)
                    snippet = "±${current.accuracyMeters.toInt()}m • ${current.source}"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mapView.overlays.add(marker)
                mapView.controller.animateTo(pt)
            }
        }
        mapView.invalidate()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (devices.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices, key = { it.id }) { dev ->
                    FilterChip(
                        selected = dev.id == selectedDeviceId,
                        onClick = { selectedDeviceId = dev.id; isPlaying = false },
                        label = { Text(dev.deviceName) }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        }

        if (deviceRecords.isNotEmpty()) {
            val active = deviceRecords.getOrNull(playbackIndex.coerceIn(0, deviceRecords.lastIndex))
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.FilledTonalButton(
                            onClick = { isPlaying = !isPlaying },
                            modifier = Modifier.testTag("history_play_pause_button")
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) strings.pauseHistoryBtn else strings.playHistoryBtn
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (isPlaying) strings.pauseHistoryBtn else strings.playHistoryBtn)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (active != null) {
                            Text(
                                text = "${formatTimestamp(active.recordedAtMs)} (±${active.accuracyMeters.toInt()}m)",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedButton(onClick = { onExportHistory(selectedDeviceId.ifBlank { null }) }) {
                            Icon(Icons.Default.Download, contentDescription = "Export CSV")
                        }
                    }
                    if (deviceRecords.size > 1) {
                        Slider(
                            value = playbackIndex.toFloat(),
                            onValueChange = {
                                isPlaying = false
                                playbackIndex = it.toInt().coerceIn(0, deviceRecords.lastIndex)
                            },
                            valueRange = 0f..deviceRecords.lastIndex.toFloat()
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (deviceRecords.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            strings.noLocationYet,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(deviceRecords.reversed(), key = { it.id }) { rec ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(formatTimestamp(rec.recordedAtMs), style = MaterialTheme.typography.titleSmall)
                                Text(rec.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(2.dp))
                            val movementLabel = when {
                                (rec.speedMps ?: 0.0) >= 1.2 -> "Moving (${"%.1f".format(rec.speedMps)} m/s)"
                                else -> "Stationary"
                            }
                            Text(
                                "%.5f, %.5f • ±${rec.accuracyMeters.toInt()}m • $movementLabel • Bat ${rec.batteryPercent}% (${rec.networkType})".format(
                                    rec.latitude, rec.longitude
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = MonoFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
    return sdf.format(Date(ms))
}
