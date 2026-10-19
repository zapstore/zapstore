package dev.zapstore.iolite

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryParseTest {
    @Test
    fun offlineAndE2eeAreHardFilters() {
        val offline = parseSearchQuery("offline maps")
        assertEquals("maps", offline.residual)
        assertEquals(FACT_OFFLINE, offline.hard)
        assertEquals(FACT_OFFLINE, offline.boost)
        assertEquals(listOf(SearchFact.WorksOffline), offline.facts)

        val e2ee = parseSearchQuery("e2ee chat")
        assertEquals("chat", e2ee.residual)
        assertEquals(FACT_E2EE, e2ee.hard)
        assertEquals(listOf(SearchFact.E2ee), e2ee.facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("encrypted chat").facts)
    }

    @Test
    fun openSourceLabelIsABoost() {
        val parsed = parseSearchQuery("open source maps")
        assertEquals("maps", parsed.residual)
        assertEquals(FACT_SOURCE_AVAILABLE, parsed.hard)
        assertEquals(FACT_SOURCE_AVAILABLE, parsed.boost)
        assertEquals(listOf(SearchFact.SourceAvailable), parsed.facts)
        assertEquals(FACT_SOURCE_AVAILABLE, parseSearchQuery("open-source").boost)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("foss maps").facts)
        assertEquals("foss maps", parseSearchQuery("foss maps").residual)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("OSS").facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("source code").facts)
    }

    @Test
    fun noGoogleServicesExcludesGoogleApps() {
        val parsed = parseSearchQuery("maps no google services")
        assertEquals("maps", parsed.residual)
        assertEquals(0, parsed.boost)
        assertEquals(0, parsed.hard)
        assertEquals(FACT_GOOGLE, parsed.exclude)
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
        assertEquals(0, ads.exclude)
        assertEquals(emptyList<SearchFact>(), ads.facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("ads").facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("works").facts)
        assertEquals(emptyList<SearchFact>(), parseSearchQuery("closed source").facts)
        val tracking = parseSearchQuery("no tracking")
        assertEquals("no tracking", tracking.residual)
        assertEquals(0, tracking.exclude)
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
            FACT_SOURCE_AVAILABLE or FACT_GOOGLE or FACT_OFFLINE or FACT_E2EE,
            factBits(csv),
        )
        assertEquals(0, factBits("\"accountless\",\"yes\",\"\"\n"))
        assertEquals(FACT_E2EE, factBits("\"e2ee\",\"yes\",\"\"\n"))
        assertEquals(0, factBits("\"google_services\",\"no\",\"\"\n"))
    }

    @Test
    fun repositoryUrlsNormalizeTheSameWay() {
        assertEquals("github.com/zapstore/zapstore", repositoryQuery("https://www.github.com/Zapstore/zapstore.git/"))
        assertEquals("github.com/zapstore/zapstore", repositoryQuery("github.com/zapstore/zapstore/tree/main?tab=readme#top"))
        assertEquals("codeberg.org/a/b", repositoryQuery("codeberg.org/a/b"))
        assertEquals(null, repositoryQuery("github maps"))
        assertEquals(null, repositoryQuery("zapstore"))
        assertEquals("github.com/zapstore/zapstore", normalizeRepositoryUrl("http://github.com/zapstore/zapstore"))
    }
}
