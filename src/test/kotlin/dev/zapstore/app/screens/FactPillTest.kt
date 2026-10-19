package dev.zapstore.app.screens

import dev.zapstore.app.facts.FactCatalog
import dev.zapstore.app.facts.label
import dev.zapstore.iolite.SearchFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FactPillTest {
    @Test
    fun yesShowsTheLabelAndNoShowsTheInverse() {
        val google = FactCatalog.pill("google_services")!!
        assertTrue(google.visible(yes = true))
        assertEquals("Google services", google.label(true))
        assertTrue(google.visible(yes = false))
        assertEquals("No Google services", google.label(false))

        val source = FactCatalog.pill("source_available")!!
        assertEquals("Source available", source.label(true))
        assertEquals("No source", source.label(false))

        val tracking = FactCatalog.pill("tracking")!!
        assertEquals("Tracking", tracking.label(true))
        assertFalse(tracking.visible(false))
        val ads = FactCatalog.pill("ads")!!
        assertEquals("Ads", ads.label(true))
        assertFalse(ads.visible(false))
    }

    @Test
    fun toneFollowsWhatIsGoodForTheUser() {
        val google = FactCatalog.pill("google_services")!!
        assertEquals(FactCatalog.Tone.Bad, google.tone(true))
        assertEquals(FactCatalog.Tone.Good, google.tone(false))
        assertEquals(FactCatalog.Tone.Bad, FactCatalog.pill("tracking")!!.tone(true))
        assertEquals(FactCatalog.Tone.Good, FactCatalog.pill("offline_capable")!!.tone(true))
        assertEquals(FactCatalog.Tone.Good, FactCatalog.pill("source_available")!!.tone(true))
        assertEquals(FactCatalog.Tone.Bad, FactCatalog.pill("source_available")!!.tone(false))
    }

    @Test
    fun factsWithoutAnInverseHideANo() {
        for (key in listOf("e2ee", "offline_capable")) {
            val pill = FactCatalog.pill(key)!!
            assertTrue(pill.visible(yes = true))
            assertFalse(pill.visible(yes = false))
        }
        assertEquals("E2EE", FactCatalog.pill("e2ee")!!.label(true))
        assertEquals("Works offline", FactCatalog.pill("offline_capable")!!.label(true))
        assertNull(FactCatalog.pill("decentralized"))
        assertNull(FactCatalog.pill("accountless"))
    }

    @Test
    fun searchChipsUseTheYesLabel() {
        assertEquals("Source available", SearchFact.SourceAvailable.label())
        assertEquals("No Google services", SearchFact.GoogleServices.label())
        assertEquals("Works offline", SearchFact.WorksOffline.label())
    }

    @Test
    fun permissionsAreNamedAndAreNotPills() {
        assertNull(FactCatalog.pill("camera"))
        assertNull(FactCatalog.pill("fine_location"))
        assertNull(FactCatalog.pill("microphone"))
        assertEquals("Camera", FactCatalog.permission("camera"))
        assertEquals("Fine location", FactCatalog.permission("fine_location"))
        assertEquals("Coarse location", FactCatalog.permission("coarse_location"))
        assertEquals("Microphone", FactCatalog.permission("microphone"))
        assertEquals("Read SMS", FactCatalog.permission("read_sms"))
        assertEquals("Receive SMS", FactCatalog.permission("receive_sms"))
        assertEquals("Send SMS", FactCatalog.permission("send_sms"))
        assertEquals("Installed apps", FactCatalog.permission("query_all_packages"))
        assertNull(FactCatalog.permission("location"))
        assertNull(FactCatalog.permission("sms"))
        assertNull(FactCatalog.permission("ACCESS_FINE_LOCATION"))
    }
}
