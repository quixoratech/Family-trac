package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.local.SyncStatusEntity
import com.example.data.model.FamilyGroup
import com.example.data.model.PairedDevice
import com.example.data.model.PairingCode
import com.example.service.LocationCaptureHelper
import com.example.service.QrCodeHelper
import com.example.service.QrDiagnosticLogger
import com.example.ui.i18n.stringsFor
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.StatusLiveGreen
import com.example.ui.theme.StatusOfflineSlate
import com.example.ui.theme.StatusStaleAmber
import com.example.ui.viewmodel.PairingCodeUiState

@Composable
fun AdminDashboardScreen(
    language: String,
    family: FamilyGroup?,
    devices: List<PairedDevice>,
    adminCoordinates: Pair<Double, Double>?,
    pendingLocCount: Int,
    syncStatus: SyncStatusEntity?,
    latestCode: PairingCode?,
    isBusy: Boolean,
    onlyDevicesList: Boolean = false,
    onGenerateCode: () -> Unit,
    onSyncNow: () -> Unit,
    onRevokeDevice: (String) -> Unit,
    onRenameDevice: (String, String) -> Unit = { _, _ -> },
    onOpenNetworkHistory: () -> Unit = {},
    onOpenActivityCenter: () -> Unit = {},
    pairingCodeUiState: PairingCodeUiState = PairingCodeUiState.Idle,
    onRetryGenerateCode: () -> Unit = onGenerateCode
) {
    val context = LocalContext.current
    val strings = stringsFor(language)
    var showQrDialog by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<PairedDevice?>(null) }
    var renameTarget by remember { mutableStateOf<PairedDevice?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    val activeDevices = devices.filter { !it.revoked }
    val onlineCount = activeDevices.count { it.online && (System.currentTimeMillis() - it.lastSeenAtMs) < 15 * 60 * 1000L }
    val offlineCount = (activeDevices.size - onlineCount).coerceAtLeast(0)
    val sharingCount = activeDevices.count { it.consentGranted && it.locationSharingEnabled && it.locationPermissionGranted }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!onlyDevicesList) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = family?.name ?: strings.appName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${strings.lastSyncLabel}: ${LocationCaptureHelper.formatRelativeTime(syncStatus?.lastSuccessfulSyncMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                        if (pendingLocCount > 0) {
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "${strings.pendingSyncQueue}: $pendingLocCount",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    onGenerateCode()
                                    showQrDialog = true
                                },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("pair_new_device_button")
                            ) {
                                Icon(Icons.Default.QrCode2, contentDescription = strings.generateQrBtn, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(strings.generateQrBtn, maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = onSyncNow,
                                modifier = Modifier.testTag("admin_sync_now_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = strings.syncNowBtn, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(strings.syncNowBtn)
                            }
                        }
                    }
                }
            }

            // 2x2 Summary KPI Grid
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryStatTile(
                        label = strings.totalDevices,
                        value = "${activeDevices.size}",
                        icon = Icons.Default.Devices,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatTile(
                        label = strings.onlineDevices,
                        value = "$onlineCount",
                        icon = Icons.Default.CloudDone,
                        tint = StatusLiveGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryStatTile(
                        label = strings.offlineDevices,
                        value = "$offlineCount",
                        icon = Icons.Default.CloudOff,
                        tint = StatusOfflineSlate,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatTile(
                        label = strings.sharingLocation,
                        value = "$sharingCount",
                        icon = Icons.Default.LocationOn,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onOpenNetworkHistory,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dashboard_open_network_history")
                    ) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.navNetworkHistory, maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = onOpenActivityCenter,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dashboard_open_activity_center")
                    ) {
                        Icon(Icons.Default.Timeline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.navActivityCenter, maxLines = 1)
                    }
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.navDevices, style = MaterialTheme.typography.headlineSmall)
                    Button(
                        onClick = {
                            onGenerateCode()
                            showQrDialog = true
                        },
                        modifier = Modifier.testTag("devices_pair_qr_button")
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = strings.generateQrBtn)
                        Spacer(Modifier.width(6.dp))
                        Text(strings.generateQrBtn)
                    }
                }
            }
        }

        if (devices.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Devices,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No paired family devices yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            items(devices, key = { it.id }) { device ->
                DeviceStatusCard(
                    device = device,
                    adminCoordinates = adminCoordinates,
                    language = language,
                    onRequestRevoke = { revokeTarget = device },
                    onRequestRename = {
                        renameTarget = device
                        renameDraft = device.deviceName
                    }
                )
            }
        }
    }

    if (showQrDialog) {
        val readyCode = (pairingCodeUiState as? PairingCodeUiState.Ready)?.code
        val effectiveCode = readyCode ?: latestCode
        val syncedToCloud = (pairingCodeUiState as? PairingCodeUiState.Ready)?.syncedToCloud ?: true
        val qrResult = remember(effectiveCode?.code) {
            effectiveCode?.code?.let { QrCodeHelper.generateQrBitmapWithDiagnostics("FAMILYTRACK:$it", 480) }
        }
        val qrBitmap = qrResult?.bitmap
        var showDiagnostics by remember(effectiveCode?.code, pairingCodeUiState) {
            mutableStateOf(
                pairingCodeUiState is PairingCodeUiState.Error ||
                    (effectiveCode != null && qrResult?.bitmap == null)
            )
        }
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text(strings.pairingCodeLabel) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    when (pairingCodeUiState) {
                        is PairingCodeUiState.Generating -> {
                            Spacer(Modifier.height(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("qr_loading_spinner")
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Generating one-time pairing code...",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                        is PairingCodeUiState.Error -> {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ErrorOutline,
                                        contentDescription = "Pairing code error",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Text(
                                        text = pairingCodeUiState.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onRetryGenerateCode,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("qr_retry_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Retry")
                            }
                        }
                        else -> {
                            if (effectiveCode != null) {
                                if (qrBitmap != null) {
                                    Surface(
                                        color = Color.White,
                                        shape = RoundedCornerShape(12.dp),
                                        tonalElevation = 2.dp,
                                        shadowElevation = 2.dp
                                    ) {
                                        Image(
                                            bitmap = qrBitmap.asImageBitmap(),
                                            contentDescription = "Pairing QR Code",
                                            modifier = Modifier
                                                .padding(12.dp)
                                                .size(200.dp)
                                                .testTag("pairing_qr_image")
                                        )
                                    }
                                    Spacer(Modifier.height(12.dp))
                                } else {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("qr_bitmap_error_card")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ErrorOutline,
                                                contentDescription = "QR graphic creation error",
                                                tint = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                text = qrResult?.errorMessage ?: "QR bitmap creation failed.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                                    ) {
                                        Text(
                                            text = effectiveCode.code,
                                            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = MonoFontFamily),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.testTag("pairing_code_text")
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        IconButton(
                                            onClick = {
                                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                cm?.setPrimaryClip(ClipData.newPlainText("Pairing Code", effectiveCode.code))
                                                Toast.makeText(context, "Pairing code copied", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.testTag("copy_pairing_code_button")
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "Copy pairing code",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    color = if (syncedToCloud) StatusLiveGreen.copy(alpha = 0.14f) else StatusStaleAmber.copy(alpha = 0.18f),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = if (syncedToCloud) {
                                            "Synced to cloud • Ready to scan"
                                        } else {
                                            "Saved offline • Waiting for internet to sync..."
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (syncedToCloud) StatusLiveGreen else StatusStaleAmber,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                            .testTag("pairing_sync_badge")
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = strings.codeExpiresNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                Spacer(Modifier.height(8.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .testTag("qr_loading_spinner")
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "Preparing pairing code...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = onRetryGenerateCode,
                                    modifier = Modifier.testTag("qr_retry_button")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Retry")
                                }
                            }
                        }
                    }

                    if (pairingCodeUiState !is PairingCodeUiState.Generating) {
                        Spacer(Modifier.height(8.dp))
                        val diagnosticText = remember(qrResult?.diagnosticReport, pairingCodeUiState, showDiagnostics) {
                            qrResult?.diagnosticReport ?: QrDiagnosticLogger.formatReport()
                        }
                        TextButton(
                            onClick = { showDiagnostics = !showDiagnostics },
                            modifier = Modifier.testTag("qr_toggle_diagnostics_button")
                        ) {
                            Text(if (showDiagnostics) "Hide Diagnostics" else "Show Diagnostics")
                        }
                        if (showDiagnostics) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = diagnosticText,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.testTag("qr_diagnostic_log_text")
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    TextButton(
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            cm?.setPrimaryClip(ClipData.newPlainText("QR Diagnostics", diagnosticText))
                                            Toast.makeText(context, "Diagnostic log copied", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .align(Alignment.End)
                                            .testTag("qr_copy_diagnostics_button")
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Copy Log")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Done")
                }
            },
            dismissButton = {
                if (pairingCodeUiState !is PairingCodeUiState.Generating) {
                    TextButton(
                        onClick = onRetryGenerateCode,
                        modifier = Modifier.testTag("qr_new_code_button")
                    ) {
                        Text("New Code")
                    }
                }
            }
        )
    }

    revokeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text(strings.revokeDeviceBtn) },
            text = { Text("Revoke access for ${target.deviceName} (${target.ownerName})? It will stop syncing immediately.") },
            confirmButton = {
                Button(
                    onClick = {
                        onRevokeDevice(target.id)
                        revokeTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(strings.revokeDeviceBtn) }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) { Text("Cancel") }
            }
        )
    }

    renameTarget?.let { target ->
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
                        onRenameDevice(target.id, renameDraft)
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
}

@Composable
private fun SummaryStatTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Card(modifier = modifier) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun DeviceStatusCard(
    device: PairedDevice,
    adminCoordinates: Pair<Double, Double>?,
    language: String,
    onRequestRevoke: () -> Unit,
    onRequestRename: () -> Unit = {}
) {
    val strings = stringsFor(language)
    val nowMs = System.currentTimeMillis()
    val isLive = !device.revoked &&
        device.locationSharingEnabled &&
        LocationCaptureHelper.isLocationLive(device.lastLocationAtMs, nowMs)
    val isOnline = !device.revoked && device.online && (nowMs - device.lastSeenAtMs) < 15 * 60 * 1000L

    val distanceText = if (adminCoordinates != null && device.lastLatitude != null && device.lastLongitude != null) {
        val dist = LocationCaptureHelper.distanceMeters(
            adminCoordinates.first, adminCoordinates.second,
            device.lastLatitude, device.lastLongitude
        )
        LocationCaptureHelper.formatDistance(dist)
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_card_${device.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(device.deviceName, style = MaterialTheme.typography.titleMedium)
                        androidx.compose.material3.IconButton(
                            onClick = onRequestRename,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename device", modifier = Modifier.size(16.dp))
                        }
                    }
                    Text(
                        "${device.ownerName} • ${device.deviceModel}" +
                            if (device.androidVersion.isNotBlank()) " • ${device.androidVersion}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val badgeColor = when {
                    device.revoked -> MaterialTheme.colorScheme.errorContainer
                    isLive -> StatusLiveGreen.copy(alpha = 0.16f)
                    else -> StatusStaleAmber.copy(alpha = 0.18f)
                }
                val badgeTextColor = when {
                    device.revoked -> MaterialTheme.colorScheme.onErrorContainer
                    isLive -> StatusLiveGreen
                    else -> StatusStaleAmber
                }
                Surface(color = badgeColor, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = when {
                            device.revoked -> strings.revokedBadge
                            isLive -> strings.liveLocationBadge
                            else -> strings.lastKnownBadge
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeTextColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BatteryChargingFull, contentDescription = strings.batteryLabel, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${device.batteryPercent}%", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wifi, contentDescription = device.networkType, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isOnline) device.networkType else "OFFLINE", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (device.locationPermissionGranted) Icons.Default.GpsFixed else Icons.Default.GpsOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (device.locationPermissionGranted) StatusLiveGreen else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (device.locationPermissionGranted) strings.gpsPermissionOk else strings.gpsPermissionMissing,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${strings.lastSeenLabel}: ${LocationCaptureHelper.formatRelativeTime(device.lastSeenAtMs, nowMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${strings.lastSyncLabel}: ${LocationCaptureHelper.formatRelativeTime(device.lastSyncAtMs, nowMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (device.lastLatitude != null && device.lastLongitude != null) {
                Text(
                    text = buildString {
                        append("%.5f, %.5f".format(device.lastLatitude, device.lastLongitude))
                        device.lastAccuracyMeters?.let { append(" • ±${it.toInt()}m") }
                        if (distanceText != null) append(" • ${strings.distanceFromYou}: $distanceText")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = strings.noLocationYet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!device.revoked && device.role != "ADMIN") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        onClick = onRequestRevoke,
                        modifier = Modifier.testTag("revoke_device_${device.id}")
                    ) {
                        Icon(Icons.Default.Block, contentDescription = strings.revokeDeviceBtn, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.revokeDeviceBtn)
                    }
                }
            }
        }
    }
}
