package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.service.LocationCaptureHelper
import com.example.service.QrCodeHelper
import com.example.ui.i18n.stringsFor
import java.util.concurrent.Executors

@Composable
fun OnboardingRoleScreen(
    language: String,
    allowedAdminEmail: Boolean,
    signedInEmail: String = "",
    isBusy: Boolean,
    bannerError: String?,
    onClearError: () -> Unit,
    onCreateFamily: (String) -> Unit,
    onJoinFamily: (code: String, deviceName: String) -> Unit
) {
    val strings = stringsFor(language)
    val context = LocalContext.current
    var selectedRole by remember(allowedAdminEmail) {
        mutableStateOf(if (allowedAdminEmail) "ADMIN" else "FAMILY")
    }
    var familyName by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("${Build.MANUFACTURER} ${Build.MODEL}".trim()) }
    var showQrScanner by remember { mutableStateOf(false) }
    var cameraDeniedMessage by remember { mutableStateOf<String?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraDeniedMessage = null
            showQrScanner = true
        } else {
            cameraDeniedMessage = "Camera permission is required to scan QR codes. You can still type the 8-character code above."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 580.dp)
        ) {
            Text(
                text = if (allowedAdminEmail) strings.chooseRoleTitle else strings.familyModeTitle,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (allowedAdminEmail) strings.chooseRoleSubtitle else strings.pairDeviceDesc,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (signedInEmail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = if (allowedAdminEmail) {
                            "Admin verified: $signedInEmail"
                        } else {
                            "Signed in as: $signedInEmail (Family Mode)"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))

            val activeError = bannerError ?: cameraDeniedMessage
            if (activeError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = activeError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            cameraDeniedMessage = null
                            onClearError()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }

            // Admin Card ONLY shown when logged in with authorized Admin Gmail
            if (allowedAdminEmail) {
                Card(
                    onClick = { selectedRole = "ADMIN" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("select_admin_role_card"),
                    border = if (selectedRole == "ADMIN") BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedRole == "ADMIN")
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.AdminPanelSettings,
                            contentDescription = strings.adminModeTitle,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(strings.adminModeTitle, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                strings.adminModeDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }

            // Family Card
            Card(
                onClick = { selectedRole = "FAMILY" },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("select_family_role_card"),
                border = if (selectedRole == "FAMILY") BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedRole == "FAMILY")
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.FamilyRestroom,
                        contentDescription = strings.familyModeTitle,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(strings.familyModeTitle, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            strings.familyModeDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            if (allowedAdminEmail && selectedRole == "ADMIN") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.createFamilyTitle, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = familyName,
                            onValueChange = { familyName = it },
                            label = { Text(strings.familyNameHint) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("family_name_input")
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { onCreateFamily(familyName) },
                            enabled = !isBusy && familyName.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("create_family_button")
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(strings.createFamilyBtn)
                            }
                        }
                    }
                }
            } else if (selectedRole == "FAMILY") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.pairDeviceTitle, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            strings.pairDeviceDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = pairingCode,
                            onValueChange = { pairingCode = it.uppercase().take(12) },
                            label = { Text(strings.enterPairingCodeHint) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pairing_code_input")
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it.take(60) },
                            label = { Text("Device Name (e.g. Mom's Infinix)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("device_name_input")
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    val hasCam = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.CAMERA
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasCam) showQrScanner = true
                                    else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("scan_qr_button")
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = strings.scanQrBtn)
                                Spacer(Modifier.width(6.dp))
                                Text(strings.scanQrBtn)
                            }
                            Button(
                                onClick = { onJoinFamily(pairingCode, deviceName) },
                                enabled = !isBusy && pairingCode.trim().length >= 6,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("join_family_button")
                            ) {
                                if (isBusy) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(strings.joinFamilyBtn)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showQrScanner) {
        QrScannerDialog(
            onCodeScanned = { code ->
                pairingCode = code
                showQrScanner = false
            },
            onDismiss = { showQrScanner = false }
        )
    }
}

@Composable
fun QrScannerDialog(
    onCodeScanned: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val provider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val analyzer = ImageAnalysis.Builder()
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(640, 480),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                            )
                                        )
                                        .build()
                                )
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { analysis ->
                                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                        val plane = imageProxy.planes.firstOrNull()
                                        if (plane != null) {
                                            val buffer = plane.buffer
                                            val bytes = ByteArray(buffer.remaining())
                                            buffer.get(bytes)
                                            val decoded = QrCodeHelper.decodeYuvQr(
                                                yuvBytes = bytes,
                                                rowStride = plane.rowStride,
                                                width = imageProxy.width,
                                                height = imageProxy.height
                                            )
                                            if (!decoded.isNullOrBlank()) {
                                                val extracted = QrCodeHelper.extractPairingCode(decoded)
                                                if (extracted.length >= 6) {
                                                    previewView.post { onCodeScanned(extracted) }
                                                }
                                            }
                                        }
                                        imageProxy.close()
                                    }
                                }
                            runCatching {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analyzer
                                )
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Point camera at Admin's QR code",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close scanner")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConsentAndPermissionsScreen(
    language: String,
    isAdmin: Boolean,
    onAgreeAndStart: () -> Unit,
    onSkipLocation: () -> Unit,
    onAgreeAndStartWithOptions: ((enableLocation: Boolean, enableNetwork: Boolean) -> Unit)? = null
) {
    val strings = stringsFor(language)
    val context = LocalContext.current
    var networkConsentChecked by remember { mutableStateOf(true) }
    var hasFgLocation by remember {
        mutableStateOf(LocationCaptureHelper.hasForegroundLocationPermission(context))
    }
    var hasBgLocation by remember {
        mutableStateOf(LocationCaptureHelper.hasBackgroundLocationPermission(context))
    }

    val fgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasFgLocation = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasFgLocation && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            hasBgLocation = true
        }
    }

    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBgLocation = granted
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 580.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = strings.consentTitle,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(strings.consentTitle, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                strings.consentBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    strings.consentBullets.forEach { bullet ->
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(bullet, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Permission Steps (Android 11-14 compliant sequential flow)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Android Permissions", style = MaterialTheme.typography.titleMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (hasFgLocation) "Foreground Location: Granted" else "1. Foreground Location (Required to share)",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (!hasFgLocation) {
                            FilledTonalButton(
                                onClick = {
                                    fgLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("grant_fg_location_button")
                            ) { Text("Allow") }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasFgLocation && !hasBgLocation) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(Modifier.width(32.dp))
                            Text(
                                "2. Background Location (Optional for hourly updates)",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalButton(
                                onClick = { bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                                modifier = Modifier.testTag("grant_bg_location_button")
                            ) { Text("Allow") }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val notifGranted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!notifGranted) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Spacer(Modifier.width(32.dp))
                                Text(
                                    "3. Notifications (Shows active sharing & incoming alerts)",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalButton(
                                    onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                    modifier = Modifier.testTag("grant_notifications_button")
                                ) { Text("Allow") }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Background Network Activity Consent Toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Checkbox(
                        checked = networkConsentChecked,
                        onCheckedChange = { networkConsentChecked = it },
                        modifier = Modifier.testTag("consent_network_checkbox")
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Background Network Activity Monitoring (Consent-Based)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            "Records Wi-Fi, Mobile Data, validated Internet availability transitions, and sync status for the Family Admin — even while the app is closed. Never accesses websites visited, browsing data, or private message content. You can pause anytime in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    if (onAgreeAndStartWithOptions != null) {
                        onAgreeAndStartWithOptions(true, networkConsentChecked)
                    } else {
                        onAgreeAndStart()
                    }
                },
                enabled = hasFgLocation,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("consent_agree_start_button")
            ) {
                Text(strings.agreeAndStartBtn, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onSkipLocation,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("consent_skip_button")
            ) {
                Text(if (isAdmin) "Continue as Admin (No Own Location)" else strings.skipLocationBtn)
            }
        }
    }
}
