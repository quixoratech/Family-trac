package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.ConsentLogEntity
import com.example.data.local.LocalAppSettings
import com.example.data.model.PairedDevice
import com.example.service.LocationCaptureHelper
import com.example.service.NearbyPacket
import com.example.service.NearbyPeer
import com.example.ui.i18n.stringsFor
import com.example.ui.theme.ThemePreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NearbyScreen(
    language: String,
    status: String,
    peers: List<NearbyPeer>,
    packets: List<NearbyPacket>,
    onStartDiscovery: () -> Unit,
    onSendNearbyPacket: (peerAddress: String, kind: String, payload: String) -> Unit
) {
    val strings = stringsFor(language)
    val context = LocalContext.current
    var selectedPeer by remember(peers) { mutableStateOf(peers.firstOrNull()) }
    var draft by remember { mutableStateOf("") }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onStartDiscovery()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(strings.nearbyTitle, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(strings.nearbyRangeNote, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val needed = buildList {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    add(Manifest.permission.BLUETOOTH_SCAN)
                                    add(Manifest.permission.BLUETOOTH_CONNECT)
                                    add(Manifest.permission.BLUETOOTH_ADVERTISE)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                                } else if (!LocationCaptureHelper.hasForegroundLocationPermission(context)) {
                                    // Android 7-12 requires location permission for Bluetooth / Wi-Fi Direct peer discovery
                                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            }.toTypedArray()
                            if (needed.isNotEmpty()) permLauncher.launch(needed)
                            else onStartDiscovery()
                        },
                        modifier = Modifier.testTag("discover_nearby_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = strings.scanNearbyBtn)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.scanNearbyBtn)
                    }
                }
            }
        }

        if (peers.isNotEmpty()) {
            item {
                Text("Discovered Nearby Peers (${peers.size})", style = MaterialTheme.typography.titleMedium)
            }
            items(peers, key = { it.address }) { peer ->
                Card(
                    onClick = { selectedPeer = peer },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedPeer?.address == peer.address)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(peer.name, style = MaterialTheme.typography.titleSmall)
                            Text(peer.transport, style = MaterialTheme.typography.labelSmall)
                        }
                        if (selectedPeer?.address == peer.address) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        selectedPeer?.let { peer ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Send nearby message to ${peer.name}") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (draft.isNotBlank()) {
                                onSendNearbyPacket(peer.address, "CHAT", draft)
                                draft = ""
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send nearby")
                    }
                }
            }
        }

        if (packets.isNotEmpty()) {
            item {
                Text("Nearby Messages", style = MaterialTheme.typography.titleMedium)
            }
            items(packets, key = { it.id }) { pkt ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${pkt.senderName} • ${pkt.kind}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(2.dp))
                        Text(pkt.payload, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    language: String,
    settings: LocalAppSettings,
    ownDevice: PairedDevice?,
    consentLogs: List<ConsentLogEntity>,
    pendingLocCount: Int,
    pendingMsgCount: Int,
    onToggleLocationSharing: (Boolean) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelectTheme: (ThemePreference) -> Unit,
    onSaveTurnConfig: (String, String, String) -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    onToggleNetworkMonitoring: (Boolean) -> Unit = {},
    onSetLocationInterval: (Int) -> Unit = {},
    onSetLastSeenPrivacy: (String) -> Unit = {},
    onSetAvailability: (String) -> Unit = {}
) {
    val strings = stringsFor(language)
    val context = LocalContext.current
    var turnUrl by remember(settings.turnServerUrl) { mutableStateOf(settings.turnServerUrl) }
    var turnUser by remember(settings.turnUsername) { mutableStateOf(settings.turnUsername) }
    var turnCred by remember(settings.turnCredential) { mutableStateOf(settings.turnCredential) }

    val fgPerm = LocationCaptureHelper.hasForegroundLocationPermission(context)
    val bgPerm = LocationCaptureHelper.hasBackgroundLocationPermission(context)
    val gpsOn = LocationCaptureHelper.isGpsEnabled(context)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (ownDevice?.revoked == true) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "This device was revoked by the Family Admin. Location and cloud sync are disabled.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        // Location Sharing & Consent Control
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strings.locationSharingToggle, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (settings.locationSharingEnabled) "Active • Notification visible" else "Paused by you",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.locationSharingEnabled,
                            onCheckedChange = onToggleLocationSharing,
                            enabled = ownDevice?.revoked != true,
                            modifier = Modifier.testTag("location_sharing_switch")
                        )
                    }
                    Text(
                        "Permissions: FG=${if (fgPerm) "OK" else "Missing"} • BG=${if (bgPerm) "OK" else "Limited"} • GPS=${if (gpsOn) "ON" else "OFF"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Offline queues: $pendingLocCount location(s), $pendingMsgCount message(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedButton(onClick = onSyncNow, modifier = Modifier.testTag("settings_sync_now_button")) {
                        Text(strings.syncNowBtn)
                    }
                }
            }
        }

        // Background Network Activity History & Sync Interval
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Background Network Activity History", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (settings.networkMonitoringEnabled) "Recording Wi-Fi / Mobile / Internet state changes"
                                    else "Paused — network transitions not recorded",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.networkMonitoringEnabled,
                            onCheckedChange = onToggleNetworkMonitoring,
                            enabled = ownDevice?.revoked != true,
                            modifier = Modifier.testTag("network_monitoring_switch")
                        )
                    }
                    Text(
                        "Android Doze & OEM battery savers may delay background checks; offline events sync automatically when Internet resumes.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Background Sync Interval", style = MaterialTheme.typography.titleSmall)
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(15, 30, 60, 120)) { mins ->
                            FilterChip(
                                selected = settings.locationIntervalMinutes == mins,
                                onClick = { onSetLocationInterval(mins) },
                                label = { Text("${mins}m") },
                                modifier = Modifier.testTag("interval_chip_$mins")
                            )
                        }
                    }
                }
            }
        }

        // Presence & Privacy
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Presence & Last Seen Privacy", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("Who can see your last seen time in chat:", style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            listOf(
                                "EVERYONE" to "Everyone",
                                "ADMIN_ONLY" to "Admin Only",
                                "NOBODY" to "Nobody"
                            )
                        ) { (code, label) ->
                            FilterChip(
                                selected = settings.lastSeenPrivacy == code,
                                onClick = { onSetLastSeenPrivacy(code) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("privacy_chip_$code")
                            )
                        }
                    }
                    Text("My Availability Status:", style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            listOf(
                                "AVAILABLE" to "Available",
                                "BUSY" to "Busy",
                                "AWAY" to "Away"
                            )
                        ) { (code, label) ->
                            FilterChip(
                                selected = settings.availability == code,
                                onClick = { onSetAvailability(code) },
                                label = { Text(label) },
                                modifier = Modifier.testTag("availability_chip_$code")
                            )
                        }
                    }
                }
            }
        }

        // Language & Theme
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Translate, contentDescription = strings.languageLabel)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.languageLabel, style = MaterialTheme.typography.titleMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.language == "en",
                            onClick = { onSelectLanguage("en") },
                            label = { Text("English") }
                        )
                        FilterChip(
                            selected = settings.language == "bn",
                            onClick = { onSelectLanguage("bn") },
                            label = { Text("বাংলা") }
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.DarkMode, contentDescription = strings.themeLabel)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.themeLabel, style = MaterialTheme.typography.titleMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemePreference.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themePreference == mode,
                                onClick = { onSelectTheme(mode) },
                                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                            )
                        }
                    }
                }
            }
        }

        // Android 11-14 / Infinix Battery Optimization Helper
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatterySaver, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Battery & Background Reliability", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(strings.batteryOptHint, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            runCatching { context.startActivity(intent) }
                        }
                    ) {
                        Text("Open App Battery & Permission Settings")
                    }
                }
            }
        }

        // Optional WebRTC TURN Server
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Optional WebRTC TURN Relay", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Free Google STUN is built in. Only add a TURN server if mobile carrier CGNAT blocks voice calls.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = turnUrl,
                        onValueChange = { turnUrl = it },
                        label = { Text("TURN URI (e.g. turn:host:3478)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = turnUser,
                            onValueChange = { turnUser = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = turnCred,
                            onValueChange = { turnCred = it },
                            label = { Text("Credential") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedButton(onClick = { onSaveTurnConfig(turnUrl, turnUser, turnCred) }) {
                        Text("Save TURN Settings")
                    }
                }
            }
        }

        if (consentLogs.isNotEmpty()) {
            item {
                Text("Consent Audit History", style = MaterialTheme.typography.titleMedium)
            }
            items(consentLogs.take(5), key = { it.id }) { log ->
                val date = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(log.timestampMs))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${log.action} • $date", style = MaterialTheme.typography.labelMedium)
                        Text(log.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Button(
                onClick = onSignOut,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sign_out_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = strings.signOutBtn)
                Spacer(Modifier.width(8.dp))
                Text(strings.signOutBtn)
            }
        }
    }
}
