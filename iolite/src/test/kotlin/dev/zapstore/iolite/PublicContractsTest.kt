package dev.zapstore.iolite

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicContractsTest {
    @Test
    fun configRejectsInvalidLifecycleValues() {
        assertFails { IoliteConfig(databaseName = "../store.db").validate() }
        assertFails { IoliteConfig(oneShotTimeout = 0.milliseconds).validate() }
        assertFails { IoliteConfig(eoseGrace = 0.milliseconds).validate() }
        assertFails { IoliteConfig(ingestionCapacity = 0).validate() }
        assertFails { IoliteConfig(expirationSweepInterval = 0.milliseconds).validate() }
        assertFails { IoliteConfig(ingestBatchSize = 0).validate() }
        assertFails { IoliteConfig(ingestFlushInterval = 0.milliseconds).validate() }
        assertFails { IoliteConfig(pruneRules = mapOf(9735 to 0.milliseconds)).validate() }
        assertFails { IoliteConfig(pruneRules = mapOf(-1 to 1.hours)).validate() }
    }

    @Test
    fun queryOptionsEnforceModes() {
        assertFails { QueryOptions(SourceMode.Remote, RemoteMode.Stream) }
        assertFails { QueryOptions(SourceMode.LocalAndRemote, RemoteMode.Stream) }
        assertFails { QueryOptions(SourceMode.Remote) }
        assertFails { QueryOptions(SourceMode.Local, RemoteMode.Stream) }
        assertFails { QueryOptions(SourceMode.Local, relays = setOf("wss://relay.example".normalizeRelayUrl())) }
        assertFails { RemoteMode.OneShot(0.milliseconds) }

        val relay = "wss://relay.example".normalizeRelayUrl()
        assertFails {
            QueryOptions.localAndRemote(setOf(relay), RemoteMode.Stream, cachedFor = 0.milliseconds)
        }
        assertFails {
            QueryOptions.localAndRemote(setOf(relay), RemoteMode.Stream, cachedFor = Duration.INFINITE)
        }
        assertFails {
            QueryOptions(SourceMode.Remote, RemoteMode.Stream, setOf(relay), cachedFor = 6.hours)
        }
        assertTrue(QueryOptions.remote(setOf(relay), RemoteMode.Stream).relays.contains(relay))
    }

    @Test
    fun queryIsUnreachableOnlyWhenEveryRelayFailed() {
        val one = "wss://one.example".normalizeRelayUrl()
        val two = "wss://two.example".normalizeRelayUrl()
        val failed = RelayStatus.Failed("refused")
        assertTrue(!QueryState(Unit).unreachable)
        assertTrue(!QueryState(Unit, mapOf(one to failed, two to RelayStatus.Pending), settled = false).unreachable)
        assertTrue(!QueryState(Unit, mapOf(one to failed, two to RelayStatus.Answered)).unreachable)
        assertTrue(QueryState(Unit, mapOf(one to failed, two to failed)).unreachable)
    }

    @Test
    fun bundleUrlUsesRelayOrigin() {
        assertEquals(
            "https://relay.example.com/v1/bundle?from=0&v=2.0.1",
            "wss://relay.example.com/nostr?x=1#frag".normalizeRelayUrl().bundleUrl(0, "2.0.1"),
        )
        assertEquals(
            "http://127.0.0.1:3334/v1/bundle?from=7",
            "ws://127.0.0.1:3334".normalizeRelayUrl().bundleUrl(7),
        )
    }

    @Test
    fun bolt11ParsesMilliAmount() {
        assertEquals(100_000, Bolt11.amountSats("lnbc1m1p..."))
        assertEquals(100_000_000L, Bolt11.amountMsat("lnbc1m1p..."))
    }

    @Test
    fun bolt11ReadsOnlyTheHumanReadablePart() {
        // 2500 BTC: the data part starts with "p", which must not read as a pico multiplier.
        assertEquals(250_000_000_000_000L, Bolt11.amountMsat("lnbc25001pvjluezpp5qqqsyqcyq5rqwzqf"))
        assertEquals(2_500L, Bolt11.amountMsat("lnbc25n1pvjluezpp5qqqsyqcyq5rqwzqf"))
        assertEquals(null, Bolt11.amountMsat("lnbc1pvjluezpp5qqqsyqcyq5rqwzqf"))
        assertEquals(null, Bolt11.amountMsat("lntb20m1pvjluezpp5qqqsyqcyq5rqwzqf"))
        assertEquals(null, Bolt11.amountMsat("lnbc15p1pvjluezpp5qqqsyqcyq5rqwzqf"))
        assertEquals(null, Bolt11.amountMsat("lnbc99999999999999999m1pvjluezpp5qqqsyqcyq5rqwzqf"))
    }

    @Test
    fun filterMatchesOnlyWhatItAsksFor() {
        val secret = ByteArray(32) { 7 }
        val event = Event.sign(secret, 100, Kinds.WalletResponse, listOf(listOf("e", "a".repeat(64))), "x")
        val author = event.pubkey
        assertTrue(Filter(authors = listOf(author), kinds = listOf(Kinds.WalletResponse), tags = mapOf("e" to listOf("a".repeat(64)))).matches(event))
        assertFalse(Filter(authors = listOf("b".repeat(64))).matches(event))
        assertFalse(Filter(kinds = listOf(Kinds.WalletRequest)).matches(event))
        assertFalse(Filter(tags = mapOf("e" to listOf("c".repeat(64)))).matches(event))
        assertFalse(Filter(since = 101).matches(event))
        assertFalse(Filter(until = 99).matches(event))
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
