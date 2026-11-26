package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.NetworkEventModel
import com.example.data.model.NetworkSessionModel
import com.example.data.model.PairedDevice
import com.example.service.LocationCaptureHelper
import com.example.service.NetworkActivityMonitor
import com.example.ui.i18n.stringsFor
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.StatusLiveGreen
import com.example.ui.theme.StatusOfflineSlate
import com.example.ui.theme.StatusStaleAmber
import com.example.ui.viewmodel.formatDurationShort
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class NetworkDateFilter { ALL_7D, TODAY, YESTERDAY }
private enum class NetworkKindFilter { ALL, INTERNET, WIFI, CELLULAR, SYNC }

@Composable
fun NetworkActivityScreen(
    language: String,
    devices: List<PairedDevice>,
    events: List<NetworkEventModel>,
    sessions: List<NetworkSessionModel>,
    onExportCsv: (String?) -> Unit
) {
    val strings = stringsFor(language)
    var selectedDeviceId by remember(devices) { mutableStateOf<String?>(devices.firstOrNull()?.id) }
    var dateFilter by remember { mutableStateOf(NetworkDateFilter.ALL_7D) }
    var kindFilter by remember { mutableStateOf(NetworkKindFilter.ALL) }
    var showSessionsTab by remember { mutableStateOf(false) }

    val selectedDevice = devices.firstOrNull { it.id == selectedDeviceId }
    val nowMs = System.currentTimeMillis()

    val startOfTodayMs = remember(nowMs) {
        Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val startOfYesterdayMs = startOfTodayMs - 24L * 60L * 60L * 1000L

    val deviceEvents = remember(events, selectedDeviceId, dateFilter, kindFilter) {
        events.filter { evt ->
            val devOk = selectedDeviceId == null || evt.deviceId == selectedDeviceId
            val dateOk = when (dateFilter) {
                NetworkDateFilter.TODAY -> evt.detectedAtMs >= startOfTodayMs
                NetworkDateFilter.YESTERDAY -> evt.detectedAtMs in startOfYesterdayMs until startOfTodayMs
                NetworkDateFilter.ALL_7D -> true
            }
            val kindOk = when (kindFilter) {
                NetworkKindFilter.ALL -> true
                NetworkKindFilter.INTERNET -> evt.eventType in listOf(
                    "INTERNET_AVAILABLE", "INTERNET_UNAVAILABLE", "NETWORK_VALIDATED",
                    "SWITCHED_WIFI_TO_CELLULAR", "SWITCHED_CELLULAR_TO_WIFI", "NETWORK_CHANGED"
                )
                NetworkKindFilter.WIFI -> evt.networkType == "WIFI" || evt.eventType.startsWith("WIFI_")
                NetworkKindFilter.CELLULAR -> evt.networkType == "CELLULAR" || evt.eventType.startsWith("CELLULAR_")
                NetworkKindFilter.SYNC -> evt.eventType.startsWith("SYNC_") || evt.eventType == "DEVICE_BOOTED"
            }
            devOk && dateOk && kindOk
        }
    }

    val deviceSessions = remember(sessions, selectedDeviceId, dateFilter) {
        sessions.filter { s ->
            val devOk = selectedDeviceId == null || s.deviceId == selectedDeviceId
            val dateOk = when (dateFilter) {
                NetworkDateFilter.TODAY -> s.startedAtMs >= startOfTodayMs || (s.endedAtMs ?: s.lastVerifiedAtMs) >= startOfTodayMs
                NetworkDateFilter.YESTERDAY -> s.startedAtMs in startOfYesterdayMs until startOfTodayMs
                NetworkDateFilter.ALL_7D -> true
            }
            devOk && dateOk
        }
    }

    val durations = remember(sessions, selectedDeviceId, nowMs) {
        val scoped = if (selectedDeviceId == null) sessions else sessions.filter { it.deviceId == selectedDeviceId }
        NetworkActivityMonitor.computeDurations(scoped, nowMs)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("network_activity_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(strings.networkHistoryTitle, style = MaterialTheme.typography.titleLarge)
                OutlinedButton(
                    onClick = { onExportCsv(selectedDeviceId) },
                    modifier = Modifier.testTag("export_network_csv_button")
                ) {
                    Icon(Icons.Default.Download, contentDescription = strings.exportCsvBtn, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.exportCsvBtn)
                }
            }
        }

        // Honest Transparency Disclaimer
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        strings.networkDisclaimer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        // Device Filter Chips
        if (devices.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedDeviceId == null,
                            onClick = { selectedDeviceId = null },
                            label = { Text("All (${devices.size})") }
                        )
                    }
                    items(devices, key = { it.id }) { dev ->
                        FilterChip(
                            selected = selectedDeviceId == dev.id,
                            onClick = { selectedDeviceId = dev.id },
                            label = { Text(dev.deviceName) }
                        )
                    }
                }
            }
        }

        // Current Live Connectivity Status Card
        selectedDevice?.let { dev ->
            val staleMs = nowMs - dev.lastSeenAtMs
            val isStale = dev.lastSeenAtMs <= 0L || staleMs > 60 * 60 * 1000L
            val statusLabel = when {
                dev.revoked -> "Revoked"
                !dev.networkMonitoringEnabled -> "Monitoring Paused by Member"
                isStale -> "Not Recently Verified (${LocationCaptureHelper.formatRelativeTime(dev.lastSeenAtMs, nowMs)})"
                dev.internetAvailable || (dev.online && dev.networkType != "OFFLINE") -> "Online via ${dev.networkType}"
                else -> "Offline"
            }
            val badgeTint = when {
                dev.revoked || !dev.networkMonitoringEnabled -> StatusOfflineSlate
                isStale -> StatusStaleAmber
                dev.internetAvailable || dev.online -> StatusLiveGreen
                else -> StatusOfflineSlate
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(badgeTint)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("${dev.deviceName} • $statusLabel", style = MaterialTheme.typography.titleSmall)
                        }
                        Text(
                            "Wi-Fi: ${if (dev.currentWifiAvailable) "Available" else "Off"} • Mobile Data: ${if (dev.currentCellularAvailable) "Available" else "Off"} • Consent: ${if (dev.networkConsentGranted) "Granted" else "Not Granted"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!dev.lastNetworkEventType.isNullOrBlank()) {
                            Text(
                                "Last Event: ${dev.lastNetworkEventType} (${LocationCaptureHelper.formatRelativeTime(dev.lastNetworkEventAtMs, nowMs)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // Today & 7-Day Honest Connected Duration Summary Cards
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DurationCard(
                    label = "Online Today",
                    value = formatDurationShort(durations.todayOnlineMs),
                    sub = "Wi-Fi ${formatDurationShort(durations.todayWifiMs)} • Cell ${formatDurationShort(durations.todayCellularMs)}",
                    modifier = Modifier.weight(1f)
                )
                DurationCard(
                    label = "Last 7 Days",
                    value = formatDurationShort(durations.sevenDayOnlineMs),
                    sub = "${deviceSessions.size} verified sessions",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Date & Event-Type Filter Chips
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = !showSessionsTab,
                            onClick = { showSessionsTab = false },
                            label = { Text("Timeline Events (${deviceEvents.size})") },
                            modifier = Modifier.testTag("network_tab_events")
                        )
                    }
                    item {
                        FilterChip(
                            selected = showSessionsTab,
                            onClick = { showSessionsTab = true },
                            label = { Text("Sessions (${deviceSessions.size})") },
                            modifier = Modifier.testTag("network_tab_sessions")
                        )
                    }
                    item {
                        FilterChip(
                            selected = dateFilter == NetworkDateFilter.ALL_7D,
                            onClick = { dateFilter = NetworkDateFilter.ALL_7D },
                            label = { Text("7 Days") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = dateFilter == NetworkDateFilter.TODAY,
                            onClick = { dateFilter = NetworkDateFilter.TODAY },
                            label = { Text("Today") }
                        )
                    }
                    item {
                        FilterChip(
                            selected = dateFilter == NetworkDateFilter.YESTERDAY,
                            onClick = { dateFilter = NetworkDateFilter.YESTERDAY },
                            label = { Text("Yesterday") }
                        )
                    }
                }
                if (!showSessionsTab) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NetworkKindFilter.entries.forEach { k ->
                            item {
                                FilterChip(
                                    selected = kindFilter == k,
                                    onClick = { kindFilter = k },
                                    label = { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showSessionsTab) {
            if (deviceSessions.isEmpty()) {
                item {
                    EmptyHubCard("No verified connectivity sessions recorded in this range.")
                }
            } else {
                items(deviceSessions, key = { it.id }) { sess ->
                    NetworkSessionCard(sess)
                }
            }
        } else {
            if (deviceEvents.isEmpty()) {
                item {
                    EmptyHubCard("No network events recorded yet for this filter.")
                }
            } else {
                items(deviceEvents, key = { it.id }) { evt ->
                    NetworkEventCard(evt)
                }
            }
        }
    }
}

@Composable
private fun DurationCard(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NetworkEventCard(evt: NetworkEventModel) {
    val icon = when {
        evt.eventType.startsWith("SYNC_") -> Icons.Default.Sync
        evt.eventType == "WIFI_AVAILABLE" || evt.eventType == "SWITCHED_CELLULAR_TO_WIFI" -> Icons.Default.Wifi
        evt.eventType == "WIFI_DISCONNECTED" -> Icons.Default.WifiOff
        evt.networkType == "CELLULAR" -> Icons.Default.SignalCellularAlt
        evt.internetAvailable -> Icons.Default.CloudDone
        else -> Icons.Default.CloudOff
    }
    val tint = when {
        evt.eventType == "SYNC_FAILED" || evt.eventType == "INTERNET_UNAVAILABLE" -> MaterialTheme.colorScheme.error
        evt.internetAvailable -> StatusLiveGreen
        else -> StatusOfflineSlate
    }
    val sdf = remember { SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault()) }
    val timeText = remember(evt.detectedAtMs) { sdf.format(Date(evt.detectedAtMs)) }
    val uploadedMs = evt.uploadedAt?.toDate()?.time
    val delayNote = if (uploadedMs != null && uploadedMs - evt.detectedAtMs > 120_000L) {
        " • Synced ${LocationCaptureHelper.formatRelativeTime(uploadedMs)}"
    } else ""

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = evt.eventType, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    evt.eventType.replace("_", " "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (!evt.details.isNullOrBlank()) {
                    Text(evt.details, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${evt.deviceName} • $timeText (${evt.timezoneId})$delayNote",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NetworkSessionCard(sess: NetworkSessionModel) {
    val sdf = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }
    val startStr = remember(sess.startedAtMs) { sdf.format(Date(sess.startedAtMs)) }
    val endStr = remember(sess.endedAtMs) { sess.endedAtMs?.let { sdf.format(Date(it)) } ?: "Ongoing" }
    val dur = sess.durationMs ?: (sess.lastVerifiedAtMs - sess.startedAtMs).coerceAtLeast(0L)
    val badgeColor = when (sess.status) {
        "ACTIVE" -> StatusLiveGreen
        "INTERRUPTED" -> StatusStaleAmber
        else -> MaterialTheme.colorScheme.primary
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${sess.deviceName} • ${sess.networkType}", style = MaterialTheme.typography.titleSmall)
                Surface(color = badgeColor.copy(alpha = 0.16f), shape = MaterialTheme.shapes.small) {
                    Text(
                        sess.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Text("Duration: ${formatDurationShort(dur)} • $startStr → $endStr", style = MaterialTheme.typography.bodySmall)
            if (!sess.interruptionReason.isNullOrBlank()) {
                Text(
                    sess.interruptionReason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun EmptyHubCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp)
        )
    }
}
