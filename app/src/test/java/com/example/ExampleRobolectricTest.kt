package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CachedDeviceEntity
import com.example.data.local.ConsentLogEntity
import com.example.data.local.FamilyTrackDatabase
import com.example.data.local.LocationRecordEntity
import com.example.data.local.PendingMessageEntity
import com.example.data.model.FamilyGroup
import com.example.data.model.FamilyMember
import com.example.data.model.GameSession
import com.example.data.model.PairedDevice
import com.example.service.QrCodeHelper
import com.example.ui.screens.AdminDashboardScreen
import com.example.ui.screens.OnboardingRoleScreen
import com.example.ui.screens.TicTacToeScreen
import com.example.ui.theme.FamilyTrackTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: FamilyTrackDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, FamilyTrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun appName_matchesFamilyTrackBd() {
        val appName = context.getString(R.string.app_name)
        assertEquals("FamilyTrack BD", appName)
    }

    @Test
    fun roomLocationQueue_preservesUnsyncedOldRecordsAndPrunesOnlyOldSynced() = runTest {
        val dao = db.locationRecordDao()
        val now = 1_700_000_000_000L
        val eightDaysAgo = now - 8L * 24L * 60L * 60L * 1000L
        val sevenDaysAgo = now - 7L * 24L * 60L * 60L * 1000L

        val oldSynced = LocationRecordEntity(
            id = "loc_old_synced",
            familyId = "fam_1",
            adminUid = "admin_1",
            ownerUid = "member_1",
            deviceId = "dev_1",
            latitude = 23.81,
            longitude = 90.41,
            accuracyMeters = 12f,
            altitudeMeters = null,
            speedMps = null,
            batteryPercent = 80,
            networkType = "WIFI",
            source = "FUSED",
            recordedAtMs = eightDaysAgo,
            synced = true
        )
        val oldUnsynced = oldSynced.copy(
            id = "loc_old_unsynced",
            synced = false
        )
        val recentUnsynced = oldSynced.copy(
            id = "loc_recent_unsynced",
            recordedAtMs = now - 60_000L,
            synced = false
        )

        dao.insertRecord(oldSynced)
        dao.insertRecord(oldUnsynced)
        dao.insertRecord(recentUnsynced)

        assertEquals(2, dao.observePendingCount().first())

        dao.pruneSyncedOlderThan(sevenDaysAgo)

        val remainingIds = dao.observeLocalHistory().first().map { it.id }
        assertTrue("Old unsynced record must NEVER be deleted before sync", remainingIds.contains("loc_old_unsynced"))
        assertTrue(remainingIds.contains("loc_recent_unsynced"))
        assertEquals(2, remainingIds.size)

        dao.markSynced("loc_old_unsynced")
        assertEquals(1, dao.observePendingCount().first())
    }

    @Test
    fun roomPendingMessageQueue_cachedDevices_andConsentLogs_persistCorrectly() = runTest {
        val msgDao = db.pendingMessageDao()
        val pending = PendingMessageEntity(
            id = "msg_offline_1",
            conversationId = "conv_group",
            familyId = "fam_1",
            senderUid = "member_1",
            senderName = "Nadia",
            participantUidsCsv = "admin_1,member_1",
            type = "TEXT",
            text = "Arrived safely",
            clientCreatedAtMs = 1_700_000_000_000L,
            synced = false
        )
        msgDao.insertPending(pending)

        assertEquals(1, msgDao.observePendingMessageCount().first())
        assertEquals("msg_offline_1", msgDao.getUnsyncedMessages().single().id)

        msgDao.markSynced("msg_offline_1")
        assertEquals(0, msgDao.observePendingMessageCount().first())

        db.cachedDeviceDao().upsertAll(
            listOf(
                CachedDeviceEntity(
                    id = "dev_infinix",
                    familyId = "fam_1",
                    adminUid = "admin_1",
                    ownerUid = "member_1",
                    ownerName = "Nadia",
                    deviceName = "Nadia Infinix",
                    deviceModel = "Infinix X6532",
                    role = "FAMILY",
                    consentGranted = true,
                    locationSharingEnabled = true,
                    locationPermissionGranted = true,
                    backgroundPermissionGranted = true,
                    batteryPercent = 76,
                    networkType = "CELLULAR",
                    online = true,
                    revoked = false,
                    lastLatitude = 23.8103,
                    lastLongitude = 90.4125,
                    lastAccuracyMeters = 15.0,
                    lastLocationAtMs = 1_700_000_000_000L,
                    lastSeenAtMs = 1_700_000_000_000L,
                    lastSyncAtMs = 1_700_000_000_000L
                )
            )
        )
        assertEquals(1, db.cachedDeviceDao().observeDevicesForFamily("fam_1").first().size)

        db.consentLogDao().insert(
            ConsentLogEntity(
                id = "c_1",
                userId = "member_1",
                familyId = "fam_1",
                deviceId = "dev_infinix",
                action = "GRANTED",
                details = "Explicit user consent",
                timestampMs = 1_700_000_000_000L
            )
        )
        assertEquals("GRANTED", db.consentLogDao().observeLogs().first().single().action)
    }

    @Test
    fun qrCodeBitmapGenerator_producesValidBitmap() {
        val bmp = QrCodeHelper.generateQrBitmap("FAMILYTRACK:FT8K2M9P", sizePx = 256)
        assertNotNull(bmp)
        assertEquals(256, bmp!!.width)
        assertEquals(256, bmp.height)
    }

    @Test
    fun adminDashboardUi_distinguishesLiveVsLastKnownLocation() {
        val now = System.currentTimeMillis()
        val liveDevice = PairedDevice(
            id = "dev_live",
            familyId = "fam_1",
            adminUid = "admin_1",
            ownerUid = "mem_1",
            ownerName = "Nadia",
            deviceName = "Infinix SMART 9 HD",
            deviceModel = "Infinix X6532",
            role = "FAMILY",
            consentGranted = true,
            locationSharingEnabled = true,
            locationPermissionGranted = true,
            batteryPercent = 85L,
            networkType = "WIFI",
            online = true,
            lastLatitude = 23.8103,
            lastLongitude = 90.4125,
            lastAccuracyMeters = 14.0,
            lastLocationAtMs = now - 30_000L,
            lastSeenAtMs = now - 10_000L,
            lastSyncAtMs = now - 10_000L
        )
        val staleDevice = liveDevice.copy(
            id = "dev_stale",
            deviceName = "Old Tablet",
            lastLocationAtMs = now - 20 * 60 * 1000L
        )

        composeRule.setContent {
            FamilyTrackTheme {
                AdminDashboardScreen(
                    language = "en",
                    family = FamilyGroup(id = "fam_1", adminUid = "admin_1", name = "Rahman Family"),
                    devices = listOf(liveDevice, staleDevice),
                    adminCoordinates = 23.8103 to 90.4125,
                    pendingLocCount = 2,
                    syncStatus = null,
                    latestCode = null,
                    isBusy = false,
                    onGenerateCode = {},
                    onSyncNow = {},
                    onRevokeDevice = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Rahman Family").assertIsDisplayed()
        composeRule.onNodeWithText("Offline Sync Queue: 2").assertIsDisplayed()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("LIVE LOCATION"))
        composeRule.onNodeWithText("LIVE LOCATION").assertIsDisplayed()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("LAST KNOWN LOCATION"))
        composeRule.onNodeWithText("LAST KNOWN LOCATION").assertIsDisplayed()
    }

    @Test
    fun onboardingRoleUi_allowsAdminOnlyForAuthorizedEmail() {
        composeRule.setContent {
            FamilyTrackTheme {
                OnboardingRoleScreen(
                    language = "en",
                    allowedAdminEmail = true,
                    signedInEmail = "quixoratech@gmail.com",
                    isBusy = false,
                    bannerError = null,
                    onClearError = {},
                    onCreateFamily = {},
                    onJoinFamily = { _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("select_admin_role_card").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("family_name_input").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("select_family_role_card").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pairing_code_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("scan_qr_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun onboardingRoleUi_hidesAdminCardForNonAdminEmail() {
        composeRule.setContent {
            FamilyTrackTheme {
                OnboardingRoleScreen(
                    language = "en",
                    allowedAdminEmail = false,
                    signedInEmail = "familymember@gmail.com",
                    isBusy = false,
                    bannerError = null,
                    onClearError = {},
                    onCreateFamily = {},
                    onJoinFamily = { _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("select_admin_role_card").assertDoesNotExist()
        composeRule.onNodeWithTag("pairing_code_input").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("scan_qr_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun ticTacToeEngine_evaluates3x3_4x4_andHardAiBlocksOrWins() {
        val win3 = com.example.game.TicTacToeEngine.evaluate(
            listOf("X", "X", "X", "", "O", "", "O", "", ""),
            com.example.game.BoardSize.CLASSIC_3X3
        )
        assertEquals("X_WON", win3.status)
        assertEquals(listOf(0, 1, 2), win3.winningIndices)

        val win4 = com.example.game.TicTacToeEngine.evaluate(
            listOf(
                "O", "", "", "",
                "", "O", "", "",
                "", "", "O", "",
                "", "", "", "O"
            ),
            com.example.game.BoardSize.PRO_4X4
        )
        assertEquals("O_WON", win4.status)
        assertEquals(listOf(0, 5, 10, 15), win4.winningIndices)

        // Hard AI must block immediate human win at index 2
        val move = com.example.game.TicTacToeEngine.pickAiMove(
            board = listOf("X", "X", "", "", "O", "", "", "", ""),
            boardSize = com.example.game.BoardSize.CLASSIC_3X3,
            aiSymbol = "O",
            difficulty = com.example.game.AiDifficulty.HARD
        )
        assertEquals(2, move)
    }

    @Test
    fun ticTacToeUi_rendersOnlineAndLocalModesCorrectly() {
        var movedIndex = -1
        val game = GameSession(
            id = "game_1",
            familyId = "fam_1",
            playerXUid = "u1",
            playerXName = "Tariq",
            playerOUid = "u2",
            playerOName = "Nadia",
            participantUids = listOf("u1", "u2"),
            board = listOf("X", "", "O", "", "", "", "", "", ""),
            currentTurn = "X",
            status = "ACTIVE",
            moveCount = 2L
        )
        composeRule.setContent {
            FamilyTrackTheme {
                TicTacToeScreen(
                    language = "en",
                    currentUserId = "u1",
                    members = listOf(FamilyMember(uid = "u2", displayName = "Nadia")),
                    games = listOf(game),
                    selectedGameId = "game_1",
                    onInviteMember = {},
                    onSelectGame = {},
                    onMakeMove = { _, cell -> movedIndex = cell },
                    onRestartGame = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ttt_cell_4").performClick()
        assertEquals(4, movedIndex)

        // Switch to Pass & Play and choose 4x4 Pro board
        composeRule.onNodeWithTag("ttt_mode_pass_and_play").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ttt_size_4x4").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasTestTag("ttt_cell_15"), useUnmergedTree = true).performScrollTo().assertIsDisplayed()

        // Switch to Solo vs Smart AI
        composeRule.onNodeWithTag("ttt_mode_solo_ai").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ttt_diff_hard").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("ttt_side_o").performScrollTo().assertIsDisplayed()
    }
}
