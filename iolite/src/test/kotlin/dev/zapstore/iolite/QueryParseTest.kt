package dev.zapstore.iolite

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryParseTest {
    @Test
    fun offlineIsPickedUpAndE2eeIsNot() {
        val offline = parseSearchQuery("offline maps")
        assertEquals("maps", offline.residual)
        assertEquals(0, offline.hard)
        assertEquals(FACT_OFFLINE, offline.boost)
        assertEquals(listOf(SearchFact.WorksOffline), offline.facts)

        val e2ee = parseSearchQuery("e2ee chat")
        assertEquals("e2ee chat", e2ee.residual)
        assertEquals(0, e2ee.boost)
        assertEquals(emptyList<SearchFact>(), e2ee.facts)
    }

    @Test
    fun openSourceLabelIsABoost() {
        val parsed = parseSearchQuery("open source maps")
        assertEquals("maps", parsed.residual)
        assertEquals(0, parsed.hard)
        assertEquals(FACT_OPEN_SOURCE, parsed.boost)
        assertEquals(listOf(SearchFact.OpenSource), parsed.facts)
        assertEquals(FACT_OPEN_SOURCE, parseSearchQuery("open-source").boost)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("foss maps").facts)
        assertEquals("foss maps", parseSearchQuery("foss maps").residual)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("OSS").facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("source code").facts)
    }

    @Test
    fun googleServicesLabelIsABoost() {
        val parsed = parseSearchQuery("maps no google services")
        assertEquals("maps", parsed.residual)
        assertEquals(0, parsed.boost)
        assertEquals(FACT_GOOGLE, parsed.penalty)
        assertEquals(listOf(SearchFact.GoogleServices), parsed.facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("google services").facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("google maps").facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("degoogled maps").facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("w/o gms").facts)
    }

    @Test
    fun retiredFactsStayInTheQuery() {
        assertEquals("accountless notes", parseSearchQuery("accountless notes").residual)
        assertEquals(0, parseSearchQuery("accountless notes").boost)
        assertEquals("decentralized chat", parseSearchQuery("decentralized chat").residual)
        assertEquals(0, parseSearchQuery("decentralized chat").boost)
    }

    @Test
    fun trackingAndAdsStayInTheQuery() {
        val ads = parseSearchQuery("no ads")
        assertEquals("no ads", ads.residual)
        assertEquals(0, ads.boost)
        assertEquals(0, ads.penalty)
        assertEquals(emptyList<SearchFact>(), ads.facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("ads").facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("works").facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("closed source").facts)
        val tracking = parseSearchQuery("no tracking")
        assertEquals("no tracking", tracking.residual)
        assertEquals(0, tracking.penalty)
        assertEquals(emptyList<SearchFact>(), tracking.facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("tracking").facts)
    }

    @Test
    fun factBitsUseYesRowsOfSearchableFacts() {
        val csv = """
            "google_services","yes",""
            "offline_capable","yes","No INTERNET"
            "accountless","yes",""
            "e2ee","yes",""
            "open_source","yes","Apache-2.0"
            "tracking","yes","Firebase Analytics sends usage."
            "ads","yes","AdMob"
            "camera","yes","CAMERA. No shown use."
        """.trimIndent()
        assertEquals(
            FACT_OPEN_SOURCE or FACT_GOOGLE or FACT_OFFLINE,
            factBits(csv),
        )
        assertEquals(0, factBits("\"accountless\",\"yes\",\"\"\n"))
        assertEquals(0, factBits("\"e2ee\",\"yes\",\"\"\n"))
        assertEquals(0, factBits("\"google_services\",\"no\",\"\"\n"))
    }
}
