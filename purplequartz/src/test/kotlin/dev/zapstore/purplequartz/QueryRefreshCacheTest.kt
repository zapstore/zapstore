package dev.zapstore.purplequartz

import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryRefreshCacheTest {
    @Test
    fun `fingerprint canonicalizes filter and relay ordering`() {
        val relayOne = "wss://one.example".normalizeRelayUrl()
        val relayTwo = "wss://two.example".normalizeRelayUrl()
        val idOne = "a".repeat(64)
        val idTwo = "b".repeat(64)
        val first = listOf(
            Filter(
                ids = listOf(idOne, idTwo),
                kinds = listOf(1, 3),
                tags = linkedMapOf("t" to listOf("one", "two"), "x" to listOf("value")),
            ),
            Filter(search = "profile", limit = 1),
        )
        val reordered = listOf(
            Filter(search = "profile", limit = 1),
            Filter(
                ids = listOf(idOne, idTwo),
                kinds = listOf(1, 3),
                tags = linkedMapOf("x" to listOf("value"), "t" to listOf("one", "two")),
            ),
        )

        assertEquals(
            QueryFingerprint.create(first, linkedSetOf(relayOne, relayTwo)),
            QueryFingerprint.create(reordered, linkedSetOf(relayTwo, relayOne)),
        )
    }

    @Test
    fun `fingerprint isolates filters and relay sets`() {
        val relayOne = "wss://one.example".normalizeRelayUrl()
        val relayTwo = "wss://two.example".normalizeRelayUrl()
        val authorOne = "a".repeat(64)
        val authorTwo = "b".repeat(64)
        val profile = listOf(Filter(authors = listOf(authorOne), kinds = listOf(0)))

        val baseline = QueryFingerprint.create(profile, setOf(relayOne))

        assertNotEquals(
            baseline,
            QueryFingerprint.create(listOf(Filter(authors = listOf(authorTwo), kinds = listOf(0))), setOf(relayOne)),
        )
        assertNotEquals(baseline, QueryFingerprint.create(profile, setOf(relayTwo)))
    }

    @Test
    fun `freshness expires and treats a backwards clock as stale`() {
        val cache = InMemoryQueryRefreshCache()
        assertTrue(cache.recordRefresh("profile", 1_000))

        val fresh = cache.freshness("profile", 60.seconds, 31_000)
        assertTrue(fresh.isFresh)
        assertEquals(30_000, fresh.remainingMillis)

        assertFalse(cache.freshness("profile", 60.seconds, 61_000).isFresh)
        assertFalse(cache.freshness("profile", 60.seconds, 999).isFresh)
        assertFalse(cache.freshness("missing", 60.seconds, 1_000).isFresh)
    }
}
