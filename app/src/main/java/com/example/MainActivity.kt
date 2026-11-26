package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.local.AppPreferences
import com.example.data.local.FamilyTrackDatabase
import com.example.data.remote.AuthHelper
import com.example.data.remote.CommunicationRepository
import com.example.data.remote.FamilyRepository
import com.example.data.remote.ResilientAuthSession
import com.example.service.NearbyCommunicationManager
import com.example.service.VoiceRecorderPlayer
import com.example.service.WebRtcCallManager
import com.example.ui.i18n.stringsFor
import com.example.ui.screens.AdminDashboardScreen
import com.example.ui.screens.AdminMapScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CallsScreen
import com.example.ui.screens.ConsentAndPermissionsScreen
import com.example.ui.screens.DeviceActivityCenterScreen
import com.example.ui.screens.FamilyHubScreen
import com.example.ui.screens.GlobalSearchScreen
import com.example.ui.screens.LocationHistoryScreen
import com.example.ui.screens.MessengerScreen
import com.example.ui.screens.NearbyScreen
import com.example.ui.screens.NetworkActivityScreen
import com.example.ui.screens.NotificationsCenterScreen
import com.example.ui.screens.OnboardingRoleScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TicTacToeScreen
import com.example.ui.theme.FamilyTrackTheme
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

sealed class AppRoute(val route: String) {
    data object Dashboard : AppRoute("dashboard")
    data object Devices : AppRoute("devices")
    data object LiveMap : AppRoute("live_map")
    data object History : AppRoute("history")
    data object NetworkActivity : AppRoute("network_activity")
    data object ActivityCenter : AppRoute("activity_center")
    data object Chats : AppRoute("chats")
    data object VoiceMessages : AppRoute("voice_messages")
    data object Calls : AppRoute("calls")
    data object TicTacToe : AppRoute("tic_tac_toe")
    data object FamilyHub : AppRoute("family_hub")
    data object Nearby : AppRoute("nearby")
    data object Settings : AppRoute("settings")
    data object Notifications : AppRoute("notifications")
    data object Search : AppRoute("search")
}

private data class NavTab(
    val route: AppRoute,
    val label: String,
    val icon: ImageVector
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context.applicationContext) }
    val settings by prefs.settingsFlow.collectAsStateWithLifecycle(initialValue = com.example.data.local.LocalAppSettings())

    val initialLiveUser = remember { Firebase.auth.currentUser }
    val authSession by remember(context, prefs) {
        AuthHelper.observeResilientAuthSession(context.applicationContext, prefs, Firebase.auth)
    }.collectAsStateWithLifecycle(
        initialValue = ResilientAuthSession(
            uid = initialLiveUser?.uid,
            email = initialLiveUser?.email.orEmpty()
        )
    )
    var autoSignInTriggered by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    LaunchedEffect(Unit) {
        if (!autoSignInTriggered && Firebase.auth.currentUser == null) {
            autoSignInTriggered = true
            AuthHelper.attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {
                    Firebase.auth.currentUser?.let { u ->
                        scope.launch { prefs.setAuthenticatedUserSession(u.uid, u.email.orEmpty()) }
                    }
                },
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    FamilyTrackTheme(themePreference = settings.themePreference) {
        val uid = authSession.uid
        if (uid.isNullOrBlank()) {
            AuthScreen(
                language = settings.language,
                onToggleLanguage = {
                    val next = if (settings.language == "bn") "en" else "bn"
                    scope.launch { prefs.setLanguage(next) }
                },
                onAuthSuccess = {
                    Firebase.auth.currentUser?.let { u ->
                        scope.launch { prefs.setAuthenticatedUserSession(u.uid, u.email.orEmpty()) }
                    }
                }
            )
        } else {
            val email = authSession.email
            key(uid) {
                AuthenticatedAppHost(
                    currentUserId = uid,
                    currentUserEmail = email,
                    authConnectivityNotice = authSession.connectivityNotice
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatedAppHost(
    currentUserId: String,
    currentUserEmail: String,
    authConnectivityNotice: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: MainViewModel = viewModel(
        key = "${currentUserId}_${currentUserEmail.lowercase()}",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                MainViewModel(
                    appContext = app.applicationContext,
                    currentUserId = currentUserId,
                    currentUserEmail = currentUserEmail,
                    familyRepo = FamilyRepository(db),
                    commRepo = CommunicationRepository(db),
                    prefs = AppPreferences(app.applicationContext),
                    localDb = FamilyTrackDatabase.getInstance(app.applicationContext),
                    voiceRecorderPlayer = VoiceRecorderPlayer(app.applicationContext),
                    webRtcManager = WebRtcCallManager(app.applicationContext),
                    nearbyManager = NearbyCommunicationManager(app.applicationContext)
                )
            }
        }
    )

    val settings by viewModel.localSettings.collectAsStateWithLifecycle()
    val familiesState by viewModel.familiesState.collectAsStateWithLifecycle()
    val activeFamily by viewModel.activeFamily.collectAsStateWithLifecycle()
    val isBackendAdmin by viewModel.isBackendAdmin.collectAsStateWithLifecycle()
    val pendingConsent by viewModel.pendingConsentAfterJoin.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val banner by viewModel.actionBanner.collectAsStateWithLifecycle()
    val connectionStatusMsg by viewModel.connectionStatusMessage.collectAsStateWithLifecycle()
    val pairingCodeUiState by viewModel.pairingCodeUiState.collectAsStateWithLifecycle()

    val strings = stringsFor(settings.language)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(banner) {
        val msg = banner
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    val family = activeFamily
    if (family == null) {
        when (familiesState) {
            is UiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = connectionStatusMsg ?: "Loading your family workspace...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            is UiState.Error -> {
                val errMsg = (familiesState as UiState.Error).message
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = "Connection error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "Connection Error",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = errMsg.ifBlank { "Unable to reach family server. Check your internet connection." },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = viewModel::retryFamilyConnection,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("retry_connection_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Retry Connection")
                            }
                            OutlinedButton(
                                onClick = {
                                    AuthHelper.signOut(
                                        credentialManager = credentialManager,
                                        scope = scope,
                                        beforeAuthSignOut = { viewModel.prepareSignOut() }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Sign Out")
                            }
                        }
                    }
                }
            }
            is UiState.Success -> {
                OnboardingRoleScreen(
                    language = settings.language,
                    allowedAdminEmail = viewModel.isAllowedAdminEmail,
                    signedInEmail = currentUserEmail,
                    isBusy = isBusy,
                    bannerError = banner,
                    onClearError = viewModel::clearBanner,
                    onCreateFamily = viewModel::createFamilyAsAdmin,
                    onJoinFamily = viewModel::joinFamilyWithCode
                )
            }
        }
    } else if (pendingConsent) {
        ConsentAndPermissionsScreen(
            language = settings.language,
            isAdmin = isBackendAdmin,
            onAgreeAndStart = viewModel::grantConsentAndEnableSharing,
            onSkipLocation = viewModel::skipLocationSharingForNow,
            onAgreeAndStartWithOptions = { enableLoc, enableNet ->
                viewModel.grantConsentAndEnableSharing(enableLoc, enableNet)
            }
        )
    } else {
        val members by viewModel.familyMembers.collectAsStateWithLifecycle()
        val adminDevices by viewModel.effectiveAdminDevices.collectAsStateWithLifecycle()
        val ownDevice by viewModel.ownDevice.collectAsStateWithLifecycle()
        val sevenDayLocations by viewModel.sevenDayLocations.collectAsStateWithLifecycle()
        val adminCoords by viewModel.adminCurrentLocation.collectAsStateWithLifecycle()
        val pendingLocCount by viewModel.pendingLocationCount.collectAsStateWithLifecycle()
        val pendingMsgCount by viewModel.pendingMessageCount.collectAsStateWithLifecycle()
        val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
        val latestCode by viewModel.latestGeneratedCode.collectAsStateWithLifecycle()
        val consentLogs by viewModel.consentLogs.collectAsStateWithLifecycle()
        val conversations by viewModel.conversations.collectAsStateWithLifecycle()
                val selectedConvId by viewModel.selectedConversationId.collectAsStateWithLifecycle()
                val messages by viewModel.activeMessages.collectAsStateWithLifecycle()
                val pendingMessages by viewModel.pendingConversationMessages.collectAsStateWithLifecycle()
                val isRecording by viewModel.voiceRecorderPlayer.isRecording.collectAsStateWithLifecycle()
                val playingId by viewModel.voiceRecorderPlayer.playingMessageId.collectAsStateWithLifecycle()
                val calls by viewModel.calls.collectAsStateWithLifecycle()
                val activeCallId by viewModel.activeCallId.collectAsStateWithLifecycle()
                val callState by viewModel.webRtcManager.callState.collectAsStateWithLifecycle()
                val callStatusMsg by viewModel.webRtcManager.statusMessage.collectAsStateWithLifecycle()
                val callDuration by viewModel.callDurationSeconds.collectAsStateWithLifecycle()
                val isMuted by viewModel.webRtcManager.isMuted.collectAsStateWithLifecycle()
                val isSpeaker by viewModel.webRtcManager.isSpeakerOn.collectAsStateWithLifecycle()
                val games by viewModel.games.collectAsStateWithLifecycle()
                val selectedGameId by viewModel.selectedGameId.collectAsStateWithLifecycle()
                val nearbyPeers by viewModel.nearbyPeers.collectAsStateWithLifecycle()
                val nearbyPackets by viewModel.nearbyPackets.collectAsStateWithLifecycle()
                val nearbyStatus by viewModel.nearbyStatus.collectAsStateWithLifecycle()
                val networkEvents by viewModel.networkEvents.collectAsStateWithLifecycle()
                val networkSessions by viewModel.networkSessions.collectAsStateWithLifecycle()
                val calendarEvents by viewModel.calendarEvents.collectAsStateWithLifecycle()
                val tasks by viewModel.tasks.collectAsStateWithLifecycle()
                val expenses by viewModel.expenses.collectAsStateWithLifecycle()
                val notices by viewModel.notices.collectAsStateWithLifecycle()
                val vaultFiles by viewModel.vaultFiles.collectAsStateWithLifecycle()
                val cloudNotifications by viewModel.cloudNotifications.collectAsStateWithLifecycle()
                val localNotifications by viewModel.localNotifications.collectAsStateWithLifecycle()
                val unreadNotificationCount by viewModel.unreadNotificationCount.collectAsStateWithLifecycle()
                val replyingToMessage by viewModel.replyingToMessage.collectAsStateWithLifecycle()

                // Strictly role-separated navigation: Family users NEVER see Admin tracking routes!
                val allTabs = remember(isBackendAdmin, strings) {
                    if (isBackendAdmin) {
                        listOf(
                            NavTab(AppRoute.Dashboard, strings.navDashboard, Icons.Default.Dashboard),
                            NavTab(AppRoute.Devices, strings.navDevices, Icons.Default.Devices),
                            NavTab(AppRoute.LiveMap, strings.navMap, Icons.Default.Map),
                            NavTab(AppRoute.History, strings.navHistory, Icons.Default.History),
                            NavTab(AppRoute.NetworkActivity, strings.navNetworkHistory, Icons.Default.Wifi),
                            NavTab(AppRoute.ActivityCenter, strings.navActivityCenter, Icons.Default.Timeline),
                            NavTab(AppRoute.Chats, strings.navChats, Icons.AutoMirrored.Filled.Chat),
                            NavTab(AppRoute.Calls, strings.navCalls, Icons.Default.Call),
                            NavTab(AppRoute.TicTacToe, strings.navTicTacToe, Icons.Default.GridOn),
                            NavTab(AppRoute.FamilyHub, strings.navFamilyHub, Icons.Default.CalendarMonth),
                            NavTab(AppRoute.Nearby, strings.navNearby, Icons.Default.Bluetooth),
                            NavTab(AppRoute.Settings, strings.navSettings, Icons.Default.Settings)
                        )
                    } else {
                        // Family Mode intentionally stays focused on the WhatsApp-style
                        // messenger. Voice notes and Tic-Tac-Toe are launched from the chat
                        // composer; calls remain available as a normal communication tab.
                        listOf(
                            NavTab(AppRoute.Chats, strings.navChats, Icons.AutoMirrored.Filled.Chat),
                            NavTab(AppRoute.Calls, strings.navCalls, Icons.Default.Call)
                        )
                    }
                }

                val startDestination = if (isBackendAdmin) AppRoute.Dashboard.route else AppRoute.Chats.route
                key(isBackendAdmin) {
                val navController = rememberNavController()
                var preferredGameOpponentUid by remember { mutableStateOf<String?>(null) }
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route ?: startDestination
                var showMoreMenu by remember { mutableStateOf(false) }

                val bottomBarPrimaryTabs = remember(allTabs) { allTabs.take(4) }
                val overflowTabs = remember(allTabs) { allTabs.drop(4) }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val useRail = maxWidth >= 600.dp

                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        when (currentRoute) {
                                            AppRoute.Notifications.route -> strings.navNotifications
                                            AppRoute.Search.route -> strings.navSearch
                                            else -> allTabs.firstOrNull { it.route.route == currentRoute }?.label ?: strings.appName
                                        }
                                    )
                                },
                                actions = {
                                    IconButton(
                                        onClick = {
                                            navController.navigate(AppRoute.Search.route) {
                                                launchSingleTop = true
                                            }
                                        },
                                        modifier = Modifier.testTag("top_search_button")
                                    ) {
                                        Icon(Icons.Default.Search, contentDescription = strings.navSearch)
                                    }
                                    IconButton(
                                        onClick = {
                                            navController.navigate(AppRoute.Notifications.route) {
                                                launchSingleTop = true
                                            }
                                        },
                                        modifier = Modifier.testTag("top_notifications_button")
                                    ) {
                                        BadgedBox(
                                            badge = {
                                                if (unreadNotificationCount > 0) {
                                                    Badge {
                                                        Text(
                                                            if (unreadNotificationCount > 99) "99+"
                                                            else unreadNotificationCount.toString()
                                                        )
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Notifications, contentDescription = strings.navNotifications)
                                        }
                                    }
                                    if (!useRail && overflowTabs.isNotEmpty()) {
                                        Box {
                                            IconButton(
                                                onClick = { showMoreMenu = true },
                                                modifier = Modifier.testTag("more_nav_menu_button")
                                            ) {
                                                Icon(Icons.Default.MoreHoriz, contentDescription = "More features")
                                            }
                                            DropdownMenu(
                                                expanded = showMoreMenu,
                                                onDismissRequest = { showMoreMenu = false }
                                            ) {
                                                overflowTabs.forEach { tab ->
                                                    DropdownMenuItem(
                                                        text = { Text(tab.label) },
                                                        leadingIcon = { Icon(tab.icon, contentDescription = tab.label) },
                                                        onClick = {
                                                            showMoreMenu = false
                                                            navController.navigate(tab.route.route) {
                                                                launchSingleTop = true
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        },
                        bottomBar = {
                            if (!useRail) {
                                NavigationBar {
                                    bottomBarPrimaryTabs.forEach { tab ->
                                        NavigationBarItem(
                                            selected = currentRoute == tab.route.route,
                                            onClick = {
                                                navController.navigate(tab.route.route) {
                                                    popUpTo(startDestination) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                                            label = { Text(tab.label, maxLines = 1) },
                                            modifier = Modifier.testTag("nav_${tab.route.route}")
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            val statusMsg = connectionStatusMsg ?: authConnectivityNotice
                            if (statusMsg != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("connection_status_banner")
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.CloudOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = statusMsg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = viewModel::retryFamilyConnection,
                                            modifier = Modifier.testTag("connection_banner_retry")
                                        ) {
                                            Text("Retry")
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                if (useRail) {
                                    NavigationRail {
                                        allTabs.forEach { tab ->
                                            NavigationRailItem(
                                                selected = currentRoute == tab.route.route,
                                                onClick = {
                                                    navController.navigate(tab.route.route) {
                                                        popUpTo(startDestination) { saveState = true }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                },
                                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                                label = { Text(tab.label, maxLines = 1) }
                                            )
                                        }
                                    }
                                }

                                NavHost(
                                    navController = navController,
                                    startDestination = startDestination,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (isBackendAdmin) {
                                        composable(AppRoute.Dashboard.route) {
                                            AdminDashboardScreen(
                                                language = settings.language,
                                                family = family,
                                                devices = adminDevices,
                                                adminCoordinates = adminCoords,
                                                pendingLocCount = pendingLocCount,
                                                syncStatus = syncStatus,
                                                latestCode = latestCode,
                                                isBusy = isBusy,
                                                onlyDevicesList = false,
                                                onGenerateCode = viewModel::generatePairingCode,
                                                onSyncNow = viewModel::captureLocationAndQueueSync,
                                                onRevokeDevice = viewModel::revokeDevice,
                                                onRenameDevice = viewModel::renamePairedDevice,
                                                onOpenNetworkHistory = {
                                                    navController.navigate(AppRoute.NetworkActivity.route) { launchSingleTop = true }
                                                },
                                                onOpenActivityCenter = {
                                                    navController.navigate(AppRoute.ActivityCenter.route) { launchSingleTop = true }
                                                },
                                                pairingCodeUiState = pairingCodeUiState,
                                                onRetryGenerateCode = viewModel::generatePairingCode
                                            )
                                        }
                                        composable(AppRoute.Devices.route) {
                                            AdminDashboardScreen(
                                                language = settings.language,
                                                family = family,
                                                devices = adminDevices,
                                                adminCoordinates = adminCoords,
                                                pendingLocCount = pendingLocCount,
                                                syncStatus = syncStatus,
                                                latestCode = latestCode,
                                                isBusy = isBusy,
                                                onlyDevicesList = true,
                                                onGenerateCode = viewModel::generatePairingCode,
                                                onSyncNow = viewModel::captureLocationAndQueueSync,
                                                onRevokeDevice = viewModel::revokeDevice,
                                                onRenameDevice = viewModel::renamePairedDevice,
                                                onOpenNetworkHistory = {
                                                    navController.navigate(AppRoute.NetworkActivity.route) { launchSingleTop = true }
                                                },
                                                onOpenActivityCenter = {
                                                    navController.navigate(AppRoute.ActivityCenter.route) { launchSingleTop = true }
                                                },
                                                pairingCodeUiState = pairingCodeUiState,
                                                onRetryGenerateCode = viewModel::generatePairingCode
                                            )
                                        }
                                    composable(AppRoute.LiveMap.route) {
                                        AdminMapScreen(
                                            language = settings.language,
                                            devices = adminDevices,
                                            adminCoordinates = adminCoords,
                                            onRefreshAdminCoordinates = viewModel::refreshAdminOwnCoordinates
                                        )
                                    }
                                    composable(AppRoute.History.route) {
                                        LocationHistoryScreen(
                                            language = settings.language,
                                            devices = adminDevices,
                                            records = sevenDayLocations,
                                            onExportHistory = viewModel::exportLocationHistory
                                        )
                                    }
                                    composable(AppRoute.NetworkActivity.route) {
                                        NetworkActivityScreen(
                                            language = settings.language,
                                            devices = adminDevices,
                                            events = networkEvents,
                                            sessions = networkSessions,
                                            onExportCsv = viewModel::exportNetworkHistory
                                        )
                                    }
                                    composable(AppRoute.ActivityCenter.route) {
                                        DeviceActivityCenterScreen(
                                            language = settings.language,
                                            devices = adminDevices,
                                            networkEvents = networkEvents,
                                            networkSessions = networkSessions,
                                            locations = sevenDayLocations,
                                            onRenameDevice = viewModel::renamePairedDevice,
                                            onTogglePauseDevice = viewModel::setDevicePausedOrRevoked,
                                            onRemoveDevice = viewModel::removePairedDevice
                                        )
                                    }
                                }

                                composable(AppRoute.Chats.route) {
                                    MessengerScreen(
                                        language = settings.language,
                                        currentUserId = currentUserId,
                                        members = members,
                                        conversations = conversations,
                                        selectedConversationId = selectedConvId,
                                        messages = messages,
                                        pendingMessages = pendingMessages,
                                        isVoiceFocused = false,
                                        isRecordingVoice = isRecording,
                                        playingMessageId = playingId,
                                        voiceRecorderPlayer = viewModel.voiceRecorderPlayer,
                                        onSelectConversation = viewModel::selectConversation,
                                        onOpenDirectChat = viewModel::openDirectChat,
                                        onSendText = viewModel::sendTextMessage,
                                        onSendVoiceNote = viewModel::sendVoiceNote,
                                        onOpenTicTacToe = { opponent ->
                                            preferredGameOpponentUid = opponent?.uid
                                            navController.navigate(AppRoute.TicTacToe.route)
                                        },
                                        isAdminViewer = isBackendAdmin,
                                        replyingTo = replyingToMessage,
                                        onSetReplyTarget = viewModel::setReplyTarget,
                                        onTypingChanged = viewModel::onChatTypingStateChanged,
                                        onSendImageUri = { viewModel.sendImageAttachment(it) },
                                        onSendDocumentUri = { viewModel.sendDocumentAttachment(it) },
                                        onRetryPending = viewModel::retryPendingMessage,
                                        onDeletePending = viewModel::deletePendingMessage,
                                        onToggleReaction = viewModel::toggleMessageReaction,
                                        onEditMessage = viewModel::editMessage,
                                        onDeleteForAll = viewModel::deleteMessageForAll,
                                        onToggleConversationFlag = viewModel::toggleConversationFlag,
                                        onOpenDocument = viewModel::openChatDocument
                                    )
                                }

                                composable(AppRoute.VoiceMessages.route) {
                                    MessengerScreen(
                                        language = settings.language,
                                        currentUserId = currentUserId,
                                        members = members,
                                        conversations = conversations,
                                        selectedConversationId = selectedConvId,
                                        messages = messages,
                                        pendingMessages = pendingMessages,
                                        isVoiceFocused = true,
                                        isRecordingVoice = isRecording,
                                        playingMessageId = playingId,
                                        voiceRecorderPlayer = viewModel.voiceRecorderPlayer,
                                        onSelectConversation = viewModel::selectConversation,
                                        onOpenDirectChat = viewModel::openDirectChat,
                                        onSendText = viewModel::sendTextMessage,
                                        onSendVoiceNote = viewModel::sendVoiceNote,
                                        onOpenTicTacToe = { opponent ->
                                            preferredGameOpponentUid = opponent?.uid
                                            navController.navigate(AppRoute.TicTacToe.route)
                                        },
                                        isAdminViewer = isBackendAdmin,
                                        replyingTo = replyingToMessage,
                                        onSetReplyTarget = viewModel::setReplyTarget,
                                        onTypingChanged = viewModel::onChatTypingStateChanged,
                                        onSendImageUri = { viewModel.sendImageAttachment(it) },
                                        onSendDocumentUri = { viewModel.sendDocumentAttachment(it) },
                                        onRetryPending = viewModel::retryPendingMessage,
                                        onDeletePending = viewModel::deletePendingMessage,
                                        onToggleReaction = viewModel::toggleMessageReaction,
                                        onEditMessage = viewModel::editMessage,
                                        onDeleteForAll = viewModel::deleteMessageForAll,
                                        onToggleConversationFlag = viewModel::toggleConversationFlag,
                                        onOpenDocument = viewModel::openChatDocument
                                    )
                                }

                                composable(AppRoute.Calls.route) {
                                    CallsScreen(
                                        language = settings.language,
                                        currentUserId = currentUserId,
                                        members = members,
                                        calls = calls,
                                        activeCallId = activeCallId,
                                        callState = callState,
                                        callStatusMessage = callStatusMsg,
                                        callDurationSeconds = callDuration,
                                        isMuted = isMuted,
                                        isSpeakerOn = isSpeaker,
                                        onStartCall = viewModel::startVoiceCall,
                                        onAcceptCall = viewModel::acceptIncomingCall,
                                        onRejectCall = viewModel::rejectIncomingCall,
                                        onEndCall = viewModel::endActiveCall,
                                        onToggleMute = viewModel.webRtcManager::toggleMute,
                                        onToggleSpeaker = viewModel.webRtcManager::toggleSpeaker
                                    )
                                }

                                composable(AppRoute.TicTacToe.route) {
                                    TicTacToeScreen(
                                        language = settings.language,
                                        currentUserId = currentUserId,
                                        members = members,
                                        games = games,
                                        selectedGameId = selectedGameId,
                                        onInviteMember = viewModel::inviteToTicTacToe,
                                        onSelectGame = viewModel::selectGame,
                                        onMakeMove = viewModel::makeTicTacToeMove,
                                        onRestartGame = viewModel::restartTicTacToe,
                                        preferredOpponentUid = preferredGameOpponentUid
                                    )
                                }

                                composable(AppRoute.FamilyHub.route) {
                                    FamilyHubScreen(
                                        language = settings.language,
                                        currentUserId = currentUserId,
                                        isAdmin = isBackendAdmin,
                                        members = members,
                                        calendarEvents = calendarEvents,
                                        tasks = tasks,
                                        expenses = expenses,
                                        notices = notices,
                                        vaultFiles = vaultFiles,
                                        onCreateEvent = viewModel::createCalendarEvent,
                                        onDeleteEvent = viewModel::deleteCalendarEvent,
                                        onCreateTask = viewModel::createTask,
                                        onToggleTask = viewModel::toggleTask,
                                        onDeleteTask = viewModel::deleteTask,
                                        onCreateExpense = viewModel::createExpense,
                                        onDeleteExpense = viewModel::deleteExpense,
                                        onExportExpenses = viewModel::exportExpensesCsv,
                                        onCreateNotice = { t, b, pinned -> viewModel.createNotice(t, b, pinned) },
                                        onToggleNoticePin = viewModel::toggleNoticePin,
                                        onMarkNoticeRead = viewModel::markNoticeRead,
                                        onDeleteNotice = viewModel::deleteNotice,
                                        onUploadVaultFile = viewModel::uploadVaultDocument,
                                        onOpenVaultFile = viewModel::openVaultDocument,
                                        onDeleteVaultFile = viewModel::deleteVaultFile
                                    )
                                }

                                composable(AppRoute.Nearby.route) {
                                    NearbyScreen(
                                        language = settings.language,
                                        status = nearbyStatus,
                                        peers = nearbyPeers,
                                        packets = nearbyPackets,
                                        onStartDiscovery = viewModel::startNearbyDiscovery,
                                        onSendNearbyPacket = viewModel::sendNearbyPacket
                                    )
                                }

                                composable(AppRoute.Settings.route) {
                                    SettingsScreen(
                                        language = settings.language,
                                        settings = settings,
                                        ownDevice = ownDevice,
                                        consentLogs = consentLogs,
                                        pendingLocCount = pendingLocCount,
                                        pendingMsgCount = pendingMsgCount,
                                        onToggleLocationSharing = viewModel::toggleLocationSharing,
                                        onSelectLanguage = viewModel::setLanguage,
                                        onSelectTheme = viewModel::setTheme,
                                        onSaveTurnConfig = viewModel::saveTurnConfig,
                                        onSyncNow = viewModel::captureLocationAndQueueSync,
                                        onSignOut = {
                                            AuthHelper.signOut(
                                                credentialManager = credentialManager,
                                                scope = scope,
                                                beforeAuthSignOut = { viewModel.prepareSignOut() }
                                            )
                                        },
                                        onToggleNetworkMonitoring = viewModel::toggleNetworkMonitoring,
                                        onSetLocationInterval = viewModel::setLocationIntervalMinutes,
                                        onSetLastSeenPrivacy = viewModel::setLastSeenPrivacy,
                                        onSetAvailability = viewModel::setAvailability
                                    )
                                }

                                composable(AppRoute.Notifications.route) {
                                    NotificationsCenterScreen(
                                        cloudNotifications = cloudNotifications,
                                        localNotifications = localNotifications,
                                        onMarkSeen = viewModel::markNotificationSeen,
                                        onMarkAllSeen = viewModel::markAllNotificationsSeen
                                    )
                                }

                                composable(AppRoute.Search.route) {
                                    GlobalSearchScreen(
                                        isAdmin = isBackendAdmin,
                                        messages = messages,
                                        members = members,
                                        devices = if (isBackendAdmin) adminDevices else emptyList(),
                                        tasks = tasks,
                                        calendarEvents = calendarEvents,
                                        notices = notices,
                                        vaultFiles = vaultFiles,
                                        onNavigateResult = { res ->
                                            val dest = if (res.targetRoute == AppRoute.Devices.route && !isBackendAdmin) {
                                                AppRoute.Chats.route
                                            } else {
                                                res.targetRoute
                                            }
                                            navController.navigate(dest) { launchSingleTop = true }
                                        }
                                    )
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    }
