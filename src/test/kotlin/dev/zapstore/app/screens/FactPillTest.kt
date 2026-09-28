package dev.zapstore.app.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FactPillTest {
    @Test
    fun antifeatureAbsenceIsPositiveAndPresenceIsNot() {
        assertTrue(factIsPositive("google_services", yes = false))
        assertFalse(factIsPositive("google_services", yes = true))
        assertFalse(factIsPositive("account_required", yes = true))
        assertTrue(factIsPositive("tracking", yes = false))
    }

    @Test
    fun cameraAndLocationAreNeutral() {
        assertEquals(FactTone.Neutral, factTone("camera", yes = true))
        assertEquals(FactTone.Neutral, factTone("camera", yes = false))
        assertEquals(FactTone.Neutral, factTone("location", yes = true))
        assertEquals(FactTone.Neutral, factTone("location", yes = false))
        assertEquals(FactTone.Negative, factTone("tracking", yes = true))
        assertEquals(FactTone.Positive, factTone("open_source", yes = true))
    }

    @Test
    fun desirablePresenceIsPositive() {
        assertTrue(factIsPositive("open_source", yes = true))
        assertTrue(factIsPositive("e2ee", yes = true))
        assertTrue(factIsPositive("offline_capable", yes = true))
        assertTrue(factIsPositive("self_hostable", yes = true))
        assertFalse(factIsPositive("open_source", yes = false))
    }

    @Test
    fun absentLabelKeepsBrandsAndAcronyms() {
        assertEquals("Google Play services", factAbsentBody("Google Play services"))
        assertEquals("Firebase Cloud Messaging", factAbsentBody("Firebase Cloud Messaging"))
        assertEquals("E2EE", factAbsentBody("E2EE"))
        assertEquals("open source", factAbsentBody("Open source"))
        assertEquals("offline capable", factAbsentBody("Offline capable"))
    }
}
