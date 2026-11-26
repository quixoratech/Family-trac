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
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.data.model.LocationRecord
import com.example.data.model.NetworkEventModel
import com.example.data.model.NetworkSessionModel
import com.example.data.model.PairedDevice
import com.example.ui.i18n.stringsFor
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.StatusLiveGreen
import com.example.ui.theme.StatusStaleAmber
import com.example.ui.viewmodel.DeviceActivityKind
import com.example.ui.viewmodel.buildUnifiedDeviceTimeline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DeviceActivityCenterScreen(
    language: String,
    devices: List<PairedDevice>,
    networkEvents: List<NetworkEventModel>,
    networkSessions: List<NetworkSessionModel>,
    locations: List<LocationRecord>,
    onRenameDevice: (deviceId: String, newName: String) -> Unit,
    onTogglePauseDevice: (deviceId: String, revoke: Boolean) -> Unit,
    onRemoveDevice: (deviceId: String) -> Unit
) {
    val strings = stringsFor(language)
    var selectedDeviceId by remember(devices) { mutableStateOf<String?>(devices.firstOrNull()?.id) }
    var kindFilter by remember { mutableStateOf<DeviceActivityKind?>(null) }
    var renameTarget by remember { mutableStateOf<PairedDevice?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<PairedDevice?>(null) }

    val selectedDevice = devices.firstOrNull { it.id == selectedDeviceId }
    val timeline = remember(devices, networkEvents, networkSessions, locations, selectedDeviceId, kindFilter) {
        buildUnifiedDeviceTimeline(
            devices = devices,
            networkEvents = networkEvents,
            networkSessions = networkSessions,
            locations = locations,
            selectedDeviceId = selectedDeviceId
        ).filter { kindFilter == null || it.kind == kindFilter }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("device_activity_center_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(strings.activityCenterTitle, style = MaterialTheme.typography.titleLarge)
            Text(
                "Unified device status, network transitions, sessions, battery & GPS history (excludes private chat messages).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (devices.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedDeviceId == null,
                            onClick = { selectedDeviceId = null },
                            label = { Text("All Devices") }
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

        selectedDevice?.let { dev ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dev.deviceName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${dev.ownerName} • ${dev.deviceModel} • ${dev.androidVersion.ifBlank { "Android" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = {
                                    renameTarget = dev
                                    renameDraft = dev.deviceName
                                },
                                modifier = Modifier.testTag("rename_device_button")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename device")
                            }
                        }
                        Text(
                            "Health: ${dev.deviceHealth} • Bat ${dev.batteryPercent}% • Net: ${dev.networkType} • Revoked: ${if (dev.revoked) "Yes" else "No"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (dev.revoked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        if (dev.role != "ADMIN") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { onTogglePauseDevice(dev.id, !dev.revoked) },
                                    modifier = Modifier.testTag("toggle_pause_device_button")
                                ) {
                                    Icon(
                                        if (dev.revoked) Icons.Default.PlayCircle else Icons.Default.PauseCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (dev.revoked) "Resume Access" else "Pause / Revoke")
                                }
                                OutlinedButton(
                                    onClick = { removeTarget = dev },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove device", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Category Filter
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = kindFilter == null,
                        onClick = { kindFilter = null },
                        label = { Text("All (${timeline.size})") }
                    )
                }
                DeviceActivityKind.entries.forEach { k ->
                    item {
                        FilterChip(
                            selected = kindFilter == k,
                            onClick = { kindFilter = k },
                            label = { Text(k.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }
        }

        if (timeline.isEmpty()) {
            item { EmptyHubCard("No activity items recorded for this selection yet.") }
        } else {
            items(timeline, key = { it.id }) { entry ->
                val icon = when (entry.kind) {
                    DeviceActivityKind.NETWORK -> Icons.Default.Wifi
                    DeviceActivityKind.SESSION -> Icons.Default.Timeline
                    DeviceActivityKind.LOCATION -> Icons.Default.LocationOn
                    DeviceActivityKind.BATTERY_STATUS -> Icons.Default.BatteryAlert
                    DeviceActivityKind.CONSENT_PERM -> Icons.Default.VerifiedUser
                    DeviceActivityKind.BOOT -> Icons.Default.PowerSettingsNew
                }
                val tint = when (entry.kind) {
                    DeviceActivityKind.NETWORK, DeviceActivityKind.SESSION -> StatusLiveGreen
                    DeviceActivityKind.LOCATION -> MaterialTheme.colorScheme.primary
                    DeviceActivityKind.BATTERY_STATUS -> StatusStaleAmber
                    else -> MaterialTheme.colorScheme.secondary
                }
                val timeStr = remember(entry.timestampMs) {
                    SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(entry.timestampMs))
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(tint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = entry.kind.name, tint = tint, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(entry.subtitle, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${entry.deviceName} • $timeStr",
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

    renameTarget?.let { dev ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Device") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it.take(60) },
                    label = { Text("Device Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameDevice(dev.id, renameDraft)
                        renameTarget = null
                    },
                    enabled = renameDraft.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    removeTarget?.let { dev ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove Paired Device?") },
            text = { Text("Remove ${dev.deviceName} from the family device list? The user will need a new pairing code to rejoin.") },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveDevice(dev.id)
                        removeTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            }
        )
    }
}
