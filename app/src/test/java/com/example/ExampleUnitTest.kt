package com.example

import com.example.data.remote.CommunicationRepository
import com.example.service.LocationCaptureHelper
import com.example.service.QrCodeHelper
import com.example.ui.i18n.stringsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun distanceCalculation_andFormatting_areAccurate() {
        val dist = LocationCaptureHelper.distanceMeters(23.8103, 90.4125, 23.8193, 90.4125)
        // ~0.009 degrees latitude is roughly 1000 meters
        assertTrue("Expected ~1000m, got $dist", dist in 980.0..1030.0)
        assertEquals("450 m", LocationCaptureHelper.formatDistance(450.0))
        assertEquals("1.25 km", LocationCaptureHelper.formatDistance(1250.0))
    }

    @Test
    fun liveVsLastKnownLocation_enforcesFiveMinuteFreshnessThreshold() {
        val now = 1_700_000_000_000L
        assertTrue(LocationCaptureHelper.isLocationLive(now - 60_000L, now))
        assertTrue(LocationCaptureHelper.isLocationLive(now - 5L * 60L * 1000L, now))
        assertFalse(LocationCaptureHelper.isLocationLive(now - 5L * 60L * 1000L - 1L, now))
        assertFalse(LocationCaptureHelper.isLocationLive(null, now))
        assertFalse(LocationCaptureHelper.isLocationLive(0L, now))
    }

    @Test
    fun ticTacToeWinnerDetection_handlesRowsColsDiagonalsAndDraws() {
        assertEquals(
            "X",
            CommunicationRepository.detectWinner(listOf("X", "X", "X", "O", "O", "", "", "", ""))
        )
        assertEquals(
            "O",
            CommunicationRepository.detectWinner(listOf("O", "X", "", "O", "X", "", "O", "", "X"))
        )
        assertEquals(
            "X",
            CommunicationRepository.detectWinner(listOf("X", "O", "", "", "X", "O", "", "", "X"))
        )
        assertNull(
            CommunicationRepository.detectWinner(listOf("X", "O", "X", "X", "O", "O", "O", "X", "X"))
        )
        assertNull(CommunicationRepository.detectWinner(listOf("X", "O")))
    }

    @Test
    fun qrPayloadExtraction_handlesPrefixedAndRawCodes() {
        assertEquals("FT8K2M9P", QrCodeHelper.extractPairingCode("FAMILYTRACK:ft8k2m9p"))
        assertEquals("FT9922AA", QrCodeHelper.extractPairingCode("  ft9922aa  "))
    }

    @Test
    fun bilingualStrings_provideNonBlankTranslationsForEnAndBn() {
        val en = stringsFor("en")
        val bn = stringsFor("bn")
        assertEquals("FamilyTrack BD", en.appName)
        assertEquals("FamilyTrack BD", bn.appName)
        assertTrue(en.liveLocationBadge.isNotBlank())
        assertTrue(bn.liveLocationBadge.isNotBlank())
        assertTrue(en.consentBullets.size == 4)
        assertTrue(bn.consentBullets.size == 4)
    }
}
