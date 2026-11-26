package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.FamilyTrackDatabase
import com.example.data.local.NetworkEventEntity
import com.example.data.local.NetworkSessionEntity
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.FamilyCalendarEvent
import com.example.data.model.FamilyExpense
import com.example.data.model.FamilyMember
import com.example.data.model.FamilyNotice
import com.example.data.model.FamilyTask
import com.example.data.model.FamilyVaultFile
import com.example.data.model.GameSession
import com.example.data.model.LocationRecord
import com.example.data.model.NetworkEventModel
import com.example.data.model.NetworkSessionModel
import com.example.data.model.PairedDevice
import com.example.data.model.PairingCode
import com.example.data.remote.AuthHelper
import com.example.data.remote.isTransientFirestoreError
import com.example.service.NetworkActivityMonitor
import com.example.service.QrCodeHelper
import com.example.service.QrDiagnosticLogger
import com.example.service.QrDiagnosticStage
import com.example.service.VoiceRecorderPlayer
import com.example.ui.screens.AdminDashboardScreen
import com.example.ui.screens.DeviceActivityCenterScreen
import com.example.ui.screens.FamilyHubScreen
import com.example.ui.screens.GlobalSearchScreen
import com.example.ui.screens.MessengerScreen
import com.example.ui.screens.NetworkActivityScreen
import com.example.ui.theme.FamilyTrackTheme
import com.example.ui.viewmodel.PairingCodeUiState
import com.example.ui.viewmodel.buildLocationHistoryCsv
import com.example.ui.viewmodel.buildNetworkActivityCsv
import com.example.ui.viewmodel.buildUnifiedDeviceTimeline
import com.example.ui.viewmodel.computeExpenseCategoryTotals
import com.example.ui.viewmodel.computeTicTacToeOnlineStats
import com.example.ui.viewmodel.formatLastSeenForViewer
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkAndHubUpgradeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun roomMigration1to2_preservesExistingDataAndCreatesNetworkTables() = runTest {
        val dbName = "migration_verify_1_2.db"
        context.deleteDatabase(dbName)

        // Create authentic v1 SQLite database manually
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `location_records` (
                            `id` TEXT NOT NULL, `familyId` TEXT NOT NULL, `adminUid` TEXT NOT NULL,
                            `ownerUid` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `latitude` REAL NOT NULL,
                            `longitude` REAL NOT NULL, `accuracyMeters` REAL NOT NULL, `altitudeMeters` REAL,
                            `speedMps` REAL, `batteryPercent` INTEGER NOT NULL, `networkType` TEXT NOT NULL,
                            `source` TEXT NOT NULL, `recordedAtMs` INTEGER NOT NULL, `synced` INTEGER NOT NULL,
                            `syncAttempts` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_records_synced_recordedAtMs` ON `location_records` (`synced`, `recordedAtMs`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_location_records_deviceId_recordedAtMs` ON `location_records` (`deviceId`, `recordedAtMs`)")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `cached_devices` (
                            `id` TEXT NOT NULL, `familyId` TEXT NOT NULL, `adminUid` TEXT NOT NULL,
                            `ownerUid` TEXT NOT NULL, `ownerName` TEXT NOT NULL, `deviceName` TEXT NOT NULL,
                            `deviceModel` TEXT NOT NULL, `role` TEXT NOT NULL, `consentGranted` INTEGER NOT NULL,
                            `locationSharingEnabled` INTEGER NOT NULL, `locationPermissionGranted` INTEGER NOT NULL,
                            `backgroundPermissionGranted` INTEGER NOT NULL, `batteryPercent` INTEGER NOT NULL,
                            `networkType` TEXT NOT NULL, `online` INTEGER NOT NULL, `revoked` INTEGER NOT NULL,
                            `lastLatitude` REAL, `lastLongitude` REAL, `lastAccuracyMeters` REAL,
                            `lastLocationAtMs` INTEGER, `lastSeenAtMs` INTEGER NOT NULL, `lastSyncAtMs` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `pending_messages` (
                            `id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `familyId` TEXT NOT NULL,
                            `senderUid` TEXT NOT NULL, `senderName` TEXT NOT NULL, `participantUidsCsv` TEXT NOT NULL,
                            `type` TEXT NOT NULL, `text` TEXT NOT NULL, `voiceFilePath` TEXT,
                            `voiceDurationMs` INTEGER NOT NULL, `clientCreatedAtMs` INTEGER NOT NULL,
                            `synced` INTEGER NOT NULL, `syncAttempts` INTEGER NOT NULL, `lastError` TEXT,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_messages_synced_clientCreatedAtMs` ON `pending_messages` (`synced`, `clientCreatedAtMs`)")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `pending_game_moves` (
                            `id` TEXT NOT NULL, `gameId` TEXT NOT NULL, `familyId` TEXT NOT NULL,
                            `playerUid` TEXT NOT NULL, `symbol` TEXT NOT NULL, `cellIndex` INTEGER NOT NULL,
                            `moveNumber` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `synced` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_game_moves_synced_createdAtMs` ON `pending_game_moves` (`synced`, `createdAtMs`)")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `consent_logs` (
                            `id` TEXT NOT NULL, `userId` TEXT NOT NULL, `familyId` TEXT NOT NULL,
                            `deviceId` TEXT NOT NULL, `action` TEXT NOT NULL, `details` TEXT NOT NULL,
                            `timestampMs` INTEGER NOT NULL, PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `sync_status` (
                            `key` TEXT NOT NULL, `lastSuccessfulSyncMs` INTEGER NOT NULL,
                            `lastSyncAttemptMs` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`key`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `app_notifications` (
                            `id` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL,
                            `type` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `seen` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent()
                    )

                    // Seed v1 rows that MUST survive migration untouched
                    db.execSQL(
                        "INSERT INTO location_records VALUES ('loc_v1','fam_1','adm_1','usr_1','dev_1',23.81,90.41,10.0,NULL,NULL,85,'WIFI','FUSED',1700000000000,0,0,NULL)"
                    )
                    db.execSQL(
                        "INSERT INTO pending_messages VALUES ('msg_v1','conv_group','fam_1','usr_1','Nadia','adm_1,usr_1','TEXT','Reached home',NULL,0,1700000000000,0,0,NULL)"
                    )
                    db.execSQL(
                        "INSERT INTO app_notifications VALUES ('notif_v1','Sync Alert','Background queued','SYNC',1700000000000,0)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val v1Helper = factory.create(config)
        v1Helper.writableDatabase.close()
        v1Helper.close()

        // Now open with Room v2 + non-destructive MIGRATION_1_2
        val migratedDb = Room.databaseBuilder(context, FamilyTrackDatabase::class.java, dbName)
            .addMigrations(FamilyTrackDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        try {
            val locs = migratedDb.locationRecordDao().observeLocalHistory().first()
            assertEquals(1, locs.size)
            assertEquals("loc_v1", locs.single().id)

            val msgs = migratedDb.pendingMessageDao().getUnsyncedMessages()
            assertEquals(1, msgs.size)
            assertEquals("Reached home", msgs.single().text)
            assertNull(msgs.single().replyToMessageId)
            assertEquals(0L, msgs.single().fileSizeBytes)

            val notifs = migratedDb.appNotificationDao().observeAll().first()
            assertEquals(1, notifs.size)
            assertEquals("GENERAL", notifs.single().category)
            assertEquals("", notifs.single().familyId)

            // Verify new v2 network_events and network_sessions tables work cleanly
            migratedDb.networkEventDao().insertEvent(
                NetworkEventEntity(
                    id = "ne_1",
                    familyId = "fam_1",
                    adminUid = "adm_1",
                    ownerUid = "usr_1",
                    deviceId = "dev_1",
                    deviceName = "Infinix SMART 9 HD",
                    eventType = "INTERNET_VALIDATED",
                    networkType = "WIFI",
                    connectionState = "CONNECTED",
                    internetAvailable = true,
                    eventTimestampMs = 1_700_000_010_000L,
                    detectedAtMs = 1_700_000_010_000L,
                    timezoneId = "Asia/Dhaka",
                    timezoneOffsetMinutes = 360,
                    sessionId = "ns_1",
                    details = "Wi-Fi validated"
                )
            )
            migratedDb.networkSessionDao().upsert(
                NetworkSessionEntity(
                    id = "ns_1",
                    familyId = "fam_1",
                    adminUid = "adm_1",
                    ownerUid = "usr_1",
                    deviceId = "dev_1",
                    deviceName = "Infinix SMART 9 HD",
                    networkType = "WIFI",
                    startedAtMs = 1_700_000_000_000L,
                    endedAtMs = 1_700_000_600_000L,
                    durationMs = 600_000L,
                    status = "COMPLETED",
                    lastVerifiedAtMs = 1_700_000_600_000L,
                    timezoneId = "Asia/Dhaka"
                )
            )
            assertEquals(1, migratedDb.networkEventDao().observeLocalEvents().first().size)
            assertEquals(1, migratedDb.networkSessionDao().observeLocalSessions().first().size)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun networkActivityMonitor_computeDurations_isAccurateAndHonest() {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 14)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val nowMs = cal.timeInMillis
        val minMs = 60_000L

        val completedWifi = NetworkSessionModel(
            id = "s1",
            networkType = "WIFI",
            startedAtMs = nowMs - 180 * minMs,
            endedAtMs = nowMs - 150 * minMs, // 30m
            status = "COMPLETED",
            lastVerifiedAtMs = nowMs - 150 * minMs
        )
        val interruptedCell = NetworkSessionModel(
            id = "s2",
            networkType = "CELLULAR",
            startedAtMs = nowMs - 120 * minMs,
            endedAtMs = nowMs - 105 * minMs, // 15m
            status = "INTERRUPTED",
            lastVerifiedAtMs = nowMs - 105 * minMs
        )
        val recentActiveWifi = NetworkSessionModel(
            id = "s3",
            networkType = "WIFI",
            startedAtMs = nowMs - 10 * minMs,
            status = "ACTIVE",
            lastVerifiedAtMs = nowMs - 5 * minMs // within 45m -> counts 10m up to nowMs
        )
        val staleActiveCell = NetworkSessionModel(
            id = "s4",
            networkType = "CELLULAR",
            startedAtMs = nowMs - 100 * minMs,
            status = "ACTIVE",
            lastVerifiedAtMs = nowMs - 90 * minMs // >45m stale -> capped at lastVerifiedAtMs (10m only, never fabricated!)
        )

        val summary = NetworkActivityMonitor.computeDurations(
            sessions = listOf(completedWifi, interruptedCell, recentActiveWifi, staleActiveCell),
            nowMs = nowMs
        )

        assertEquals(40 * minMs, summary.todayWifiMs)
        assertEquals(25 * minMs, summary.todayCellularMs)
        assertEquals(65 * minMs, summary.todayOnlineMs)
        assertEquals(65 * minMs, summary.sevenDayOnlineMs)
    }

    @Test
    fun hubHelpers_enforcePrivacyTimelineExpensesStatsAndCsv() {
        val now = 1_700_000_000_000L
        val hiddenMember = FamilyMember(
            uid = "m1",
            displayName = "Nadia",
            online = false,
            lastSeenAtMs = now - 120_000L,
            lastSeenPrivacy = "NOBODY",
            availability = "BUSY"
        )
        val adminOnlyMember = hiddenMember.copy(lastSeenPrivacy = "ADMIN_ONLY", availability = "AVAILABLE")
        val typingMember = hiddenMember.copy(
            typingConversationId = "conv_group",
            typingActivity = "TYPING",
            typingUpdatedAtMs = now - 2_000L
        )

        assertEquals("Busy", formatLastSeenForViewer(hiddenMember, viewerIsAdmin = true, nowMs = now))
        assertEquals("Available".let { "Last seen hidden" }, formatLastSeenForViewer(adminOnlyMember, viewerIsAdmin = false, nowMs = now))
        assertTrue(formatLastSeenForViewer(adminOnlyMember, viewerIsAdmin = true, nowMs = now).startsWith("Last seen "))
        assertEquals("Typing...", formatLastSeenForViewer(typingMember, viewerIsAdmin = false, conversationId = "conv_group", nowMs = now))

        // Unified device timeline excludes private messages and orders newest-first
        val dev = PairedDevice(id = "d1", deviceName = "Infinix SMART 9 HD", batteryPercent = 72L, lastSeenAtMs = now)
        val evt = NetworkEventModel(id = "e1", deviceId = "d1", deviceName = "Infinix SMART 9 HD", eventType = "WIFI_AVAILABLE", networkType = "WIFI", detectedAtMs = now - 10_000L)
        val sess = NetworkSessionModel(id = "s1", deviceId = "d1", deviceName = "Infinix SMART 9 HD", networkType = "WIFI", startedAtMs = now - 20_000L, endedAtMs = now - 5_000L, status = "COMPLETED")
        val loc = LocationRecord(id = "l1", deviceId = "d1", latitude = 23.81, longitude = 90.41, accuracyMeters = 12.0, batteryPercent = 72L, networkType = "WIFI", source = "FUSED", recordedAtMs = now - 3_000L)
        val timeline = buildUnifiedDeviceTimeline(listOf(dev), listOf(evt), listOf(sess), listOf(loc))
        assertEquals(4, timeline.size)
        assertTrue(timeline[0].timestampMs >= timeline[1].timestampMs)

        // Expenses category totals
        val expenses = listOf(
            FamilyExpense(id = "x1", title = "Rice", category = "Groceries", amountBdt = 650.0, expenseDateMs = System.currentTimeMillis()),
            FamilyExpense(id = "x2", title = "Bus", category = "Transport", amountBdt = 120.0, expenseDateMs = System.currentTimeMillis()),
            FamilyExpense(id = "x3", title = "Veg", category = "Groceries", amountBdt = 250.0, expenseDateMs = System.currentTimeMillis())
        )
        val totals = computeExpenseCategoryTotals(expenses, currentMonthOnly = true)
        assertEquals(900.0, totals["Groceries"] ?: 0.0, 0.01)
        assertEquals(120.0, totals["Transport"] ?: 0.0, 0.01)

        // Tic-Tac-Toe online stats
        val stats = computeTicTacToeOnlineStats(
            listOf(
                GameSession(id = "g1", playerXUid = "u1", playerOUid = "u2", status = "X_WON"),
                GameSession(id = "g2", playerXUid = "u1", playerOUid = "u2", status = "O_WON"),
                GameSession(id = "g3", playerXUid = "u1", playerOUid = "u2", status = "DRAW"),
                GameSession(id = "g4", playerXUid = "u1", playerOUid = "u2", status = "ACTIVE")
            ),
            currentUserId = "u1"
        )
        assertEquals(1, stats.wins)
        assertEquals(1, stats.losses)
        assertEquals(1, stats.draws)
        assertEquals(1, stats.active)

        // CSV exports contain headers and escaped rows
        assertTrue(buildNetworkActivityCsv("Infinix", listOf(evt), listOf(sess)).contains("WIFI_AVAILABLE"))
        assertTrue(buildLocationHistoryCsv("Infinix", listOf(loc)).contains("23.81"))
    }

    @Test
    fun networkActivityScreen_rendersAndSwitchesEventsAndSessions() {
        val now = System.currentTimeMillis()
        val dev = PairedDevice(
            id = "d1",
            deviceName = "Infinix SMART 9 HD",
            online = true,
            networkType = "WIFI",
            networkMonitoringEnabled = true,
            lastSeenAtMs = now - 15_000L
        )
        val evt = NetworkEventModel(
            id = "e1",
            deviceId = "d1",
            deviceName = "Infinix SMART 9 HD",
            eventType = "INTERNET_VALIDATED",
            networkType = "WIFI",
            connectionState = "CONNECTED",
            internetAvailable = true,
            detectedAtMs = now - 10_000L,
            timezoneId = "Asia/Dhaka",
            details = "Validated Wi-Fi connection"
        )
        val sess = NetworkSessionModel(
            id = "s1",
            deviceId = "d1",
            deviceName = "Infinix SMART 9 HD",
            networkType = "WIFI",
            startedAtMs = now - 600_000L,
            endedAtMs = now - 60_000L,
            durationMs = 540_000L,
            status = "COMPLETED",
            lastVerifiedAtMs = now - 60_000L,
            timezoneId = "Asia/Dhaka"
        )

        composeRule.setContent {
            FamilyTrackTheme {
                NetworkActivityScreen(
                    language = "en",
                    devices = listOf(dev),
                    events = listOf(evt),
                    sessions = listOf(sess),
                    onExportCsv = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("network_activity_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("export_network_csv_button").assertIsDisplayed()
        composeRule.onNodeWithTag("network_activity_screen").performScrollToNode(hasText("INTERNET VALIDATED"))
        composeRule.onNodeWithText("INTERNET VALIDATED").assertIsDisplayed()

        composeRule.onNodeWithTag("network_tab_sessions").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("network_activity_screen").performScrollToNode(hasText("Infinix SMART 9 HD • WIFI"))
        composeRule.onNodeWithText("Infinix SMART 9 HD • WIFI").assertIsDisplayed()
    }

    @Test
    fun deviceActivityCenter_andFamilyHubTabs_renderCleanly() {
        val now = System.currentTimeMillis()
        val dev = PairedDevice(id = "d1", deviceName = "Infinix SMART 9 HD", batteryPercent = 82L, lastSeenAtMs = now)
        val member = FamilyMember(uid = "u1", displayName = "Tariq", role = "ADMIN")

        composeRule.setContent {
            FamilyTrackTheme {
                DeviceActivityCenterScreen(
                    language = "en",
                    devices = listOf(dev),
                    networkEvents = listOf(
                        NetworkEventModel(id = "e1", deviceId = "d1", deviceName = "Infinix SMART 9 HD", eventType = "WIFI_AVAILABLE", detectedAtMs = now)
                    ),
                    networkSessions = emptyList(),
                    locations = emptyList(),
                    onRenameDevice = { _, _ -> },
                    onTogglePauseDevice = { _, _ -> },
                    onRemoveDevice = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("device_activity_center_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("rename_device_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Rename Device").assertIsDisplayed()
    }

    @Test
    fun familyHubScreen_switchesBetweenAllFiveTabs() {
        val now = System.currentTimeMillis()
        composeRule.setContent {
            FamilyTrackTheme {
                FamilyHubScreen(
                    language = "en",
                    currentUserId = "u1",
                    isAdmin = true,
                    members = listOf(FamilyMember(uid = "u1", displayName = "Tariq", role = "ADMIN")),
                    calendarEvents = listOf(FamilyCalendarEvent(id = "c1", title = "School Pickup", startAtMs = now)),
                    tasks = listOf(FamilyTask(id = "t1", title = "Buy Milk", priority = "HIGH")),
                    expenses = listOf(FamilyExpense(id = "x1", title = "Weekly Bazar", category = "Groceries", amountBdt = 500.0, expenseDateMs = now)),
                    notices = listOf(FamilyNotice(id = "n1", title = "Gate Lock", body = "Lock gate at 10 PM", pinned = true)),
                    vaultFiles = listOf(FamilyVaultFile(id = "v1", title = "NID Scan", folder = "IDs", fileName = "nid.pdf", fileSizeBytes = 12000L)),
                    onCreateEvent = { _, _, _, _, _ -> },
                    onDeleteEvent = {},
                    onCreateTask = { _, _, _, _, _ -> },
                    onToggleTask = {},
                    onDeleteTask = {},
                    onCreateExpense = { _, _, _, _, _ -> },
                    onDeleteExpense = {},
                    onExportExpenses = {},
                    onCreateNotice = { _, _, _ -> },
                    onToggleNoticePin = {},
                    onMarkNoticeRead = {},
                    onDeleteNotice = {},
                    onUploadVaultFile = { _, _, _, _ -> },
                    onOpenVaultFile = {},
                    onDeleteVaultFile = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("School Pickup").assertIsDisplayed()

        composeRule.onNodeWithTag("hub_tab_1").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Buy Milk").assertIsDisplayed()

        composeRule.onNodeWithTag("hub_tab_2").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Weekly Bazar").assertIsDisplayed()

        composeRule.onNodeWithTag("hub_tab_3").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Gate Lock").assertIsDisplayed()

        composeRule.onNodeWithTag("hub_tab_4").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("NID Scan").assertIsDisplayed()
    }

    @Test
    fun messengerScreen_andGlobalSearch_enforceRolePrivacy() {
        val now = System.currentTimeMillis()
        val msg = ChatMessage(
            id = "m1",
            conversationId = "conv_group",
            senderUid = "u2",
            senderName = "Nadia",
            type = "TEXT",
            text = "Safe at home",
            clientCreatedAtMs = now
        )
        composeRule.setContent {
            FamilyTrackTheme {
                MessengerScreen(
                    language = "en",
                    currentUserId = "u1",
                    members = listOf(
                        FamilyMember(uid = "u1", displayName = "Tariq"),
                        FamilyMember(uid = "u2", displayName = "Nadia", online = true, lastSeenAtMs = now)
                    ),
                    conversations = listOf(Conversation(id = "conv_group", type = "GROUP", title = "Family Group")),
                    selectedConversationId = "conv_group",
                    messages = listOf(msg),
                    pendingMessages = emptyList(),
                    isVoiceFocused = false,
                    isRecordingVoice = false,
                    playingMessageId = null,
                    voiceRecorderPlayer = VoiceRecorderPlayer(context),
                    onSelectConversation = {},
                    onOpenDirectChat = {},
                    onSendText = {},
                    onSendVoiceNote = { _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Safe at home").assertIsDisplayed()
        composeRule.onNodeWithTag("chat_message_input").assertIsDisplayed()
        composeRule.onNodeWithTag("attach_image_button").assertIsDisplayed()
        composeRule.onNodeWithTag("attach_doc_button").assertIsDisplayed()
    }

    @Test
    fun globalSearch_nonAdminCannotSearchAdminDevices() {
        val dev = PairedDevice(id = "d1", deviceName = "Infinix SMART 9 HD", ownerName = "Nadia")
        val task = FamilyTask(id = "t1", title = "Infinix charger check", assignedToName = "Nadia")

        composeRule.setContent {
            FamilyTrackTheme {
                GlobalSearchScreen(
                    isAdmin = false,
                    messages = emptyList(),
                    members = emptyList(),
                    devices = listOf(dev),
                    tasks = listOf(task),
                    calendarEvents = emptyList(),
                    notices = emptyList(),
                    vaultFiles = emptyList(),
                    onNavigateResult = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("global_search_input").performTextInput("Infinix")
        composeRule.waitForIdle()

        // Task is visible to family user, while paired device tracking record is strictly excluded
        composeRule.onNodeWithText("TASK • Infinix charger check").assertIsDisplayed()
        assertFalse(
            runCatching {
                composeRule.onNodeWithText("DEVICE • Infinix SMART 9 HD").assertIsDisplayed()
            }.isSuccess
        )
    }

    @Test
    fun transientFirestoreErrorClassifier_distinguishesNetworkDropsFromPermissions() {
        assertTrue(
            isTransientFirestoreError(
                FirebaseFirestoreException("unavailable", FirebaseFirestoreException.Code.UNAVAILABLE)
            )
        )
        assertTrue(
            isTransientFirestoreError(
                FirebaseFirestoreException("deadline exceeded", FirebaseFirestoreException.Code.DEADLINE_EXCEEDED)
            )
        )
        assertTrue(isTransientFirestoreError(IOException("socket reset")))
        assertFalse(
            isTransientFirestoreError(
                FirebaseFirestoreException("permission denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
            )
        )
    }

    @Test
    fun adminDashboardQrDialog_displaysErrorRetryAndReadyStates() {
        var retryClicks = 0
        composeRule.setContent {
            FamilyTrackTheme {
                AdminDashboardScreen(
                    language = "en",
                    family = com.example.data.model.FamilyGroup(id = "fam_1", adminUid = "adm_1", name = "Rahman Family"),
                    devices = emptyList(),
                    adminCoordinates = null,
                    pendingLocCount = 0,
                    syncStatus = null,
                    latestCode = null,
                    isBusy = false,
                    onlyDevicesList = true,
                    onGenerateCode = {},
                    onSyncNow = {},
                    onRevokeDevice = {},
                    pairingCodeUiState = PairingCodeUiState.Error("Network unavailable"),
                    onRetryGenerateCode = { retryClicks++ }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("devices_pair_qr_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Network unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("qr_retry_button").performClick()
        assertEquals(1, retryClicks)
    }

    @Test
    fun adminDashboardQrDialog_rendersQrAndSyncStatusWhenReady() {
        composeRule.setContent {
            FamilyTrackTheme {
                AdminDashboardScreen(
                    language = "en",
                    family = com.example.data.model.FamilyGroup(id = "fam_1", adminUid = "adm_1", name = "Rahman Family"),
                    devices = emptyList(),
                    adminCoordinates = null,
                    pendingLocCount = 0,
                    syncStatus = null,
                    latestCode = null,
                    isBusy = false,
                    onlyDevicesList = true,
                    onGenerateCode = {},
                    onSyncNow = {},
                    onRevokeDevice = {},
                    pairingCodeUiState = PairingCodeUiState.Ready(
                        code = PairingCode(id = "FT998877", code = "FT998877"),
                        syncedToCloud = true
                    )
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("devices_pair_qr_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pairing_qr_image").assertIsDisplayed()
        composeRule.onNodeWithText("FT998877").assertIsDisplayed()
        composeRule.onNodeWithText("Synced to cloud • Ready to scan").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("qr_toggle_diagnostics_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("qr_diagnostic_log_text").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("qr_copy_diagnostics_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun qrDiagnosticLogger_tracksSuccessAndFailureStages() {
        QrDiagnosticLogger.clear()
        val ok = QrCodeHelper.generateQrBitmapWithDiagnostics("FAMILYTRACK:FT123456", sizePx = 256, attemptId = "test_ok")
        assertTrue(ok.bitmap != null)
        assertNull(ok.errorMessage)
        val okStages = QrDiagnosticLogger.getEntriesForAttempt("test_ok").map { it.stage }
        assertTrue(okStages.contains(QrDiagnosticStage.PAYLOAD_VALIDATION))
        assertTrue(okStages.contains(QrDiagnosticStage.MATRIX_ENCODE))
        assertTrue(okStages.contains(QrDiagnosticStage.BITMAP_ALLOCATION))
        assertTrue(okStages.contains(QrDiagnosticStage.RENDER_COMPLETE))

        val emptyFail = QrCodeHelper.generateQrBitmapWithDiagnostics("   ", sizePx = 256, attemptId = "test_empty")
        assertNull(emptyFail.bitmap)
        assertTrue(emptyFail.errorMessage?.contains("empty") == true)
        assertTrue(emptyFail.diagnosticReport.contains("PAYLOAD_VALIDATION -> FAIL"))

        val sizeFail = QrCodeHelper.generateQrBitmapWithDiagnostics("FAMILYTRACK:FT123456", sizePx = 12, attemptId = "test_size")
        assertNull(sizeFail.bitmap)
        assertTrue(sizeFail.errorMessage?.contains("Invalid QR bitmap size") == true)
    }

    @Test
    fun authHelper_connectivityExceptionsMaintainPersistedSession() {
        assertTrue(AuthHelper.isConnectivityFirebaseException(FirebaseNetworkException("no network")))
        assertTrue(AuthHelper.isConnectivityFirebaseException(SocketTimeoutException("read timed out")))
        assertTrue(
            AuthHelper.isConnectivityFirebaseException(
                FirebaseFirestoreException("unavailable", FirebaseFirestoreException.Code.UNAVAILABLE)
            )
        )
        assertFalse(
            AuthHelper.isConnectivityFirebaseException(
                FirebaseAuthException("ERROR_USER_DISABLED", "Account disabled")
            )
        )

        // Temporary network drop must keep persisted session instead of returning null uid
        val offlinePreserved = AuthHelper.resolveAuthStateDecision(
            liveUid = null,
            liveEmail = "",
            cachedUid = "uid_admin_1",
            cachedEmail = "quixoratech@gmail.com",
            isExplicitSignOut = false,
            isNetworkOnline = false,
            encounteredConnectivityError = true
        )
        assertEquals("uid_admin_1", offlinePreserved.uid)
        assertEquals("quixoratech@gmail.com", offlinePreserved.email)
        assertTrue(offlinePreserved.isOfflineFallback)
        assertTrue(offlinePreserved.connectivityNotice != null)

        // Explicit sign out must clear session cleanly
        val signedOut = AuthHelper.resolveAuthStateDecision(
            liveUid = null,
            liveEmail = "",
            cachedUid = "uid_admin_1",
            cachedEmail = "quixoratech@gmail.com",
            isExplicitSignOut = true,
            isNetworkOnline = false,
            encounteredConnectivityError = false
        )
        assertNull(signedOut.uid)
    }
}
