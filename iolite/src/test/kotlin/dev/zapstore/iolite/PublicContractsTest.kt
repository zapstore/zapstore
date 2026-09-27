package dev.zapstore.iolite

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
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
    fun queryPhasesRejectTransitionsOutOfTerminalStates() {
        assertTrue(isLegalPhaseTransition(null, QueryPhase.Connecting))
        assertTrue(isLegalPhaseTransition(QueryPhase.Connecting, QueryPhase.CatchingUp))
        assertTrue(isLegalPhaseTransition(QueryPhase.CatchingUp, QueryPhase.Live))
        assertTrue(isLegalPhaseTransition(QueryPhase.Live, QueryPhase.Connecting))
        assertTrue(isLegalPhaseTransition(QueryPhase.Cached, QueryPhase.Connecting))
        assertTrue(!isLegalPhaseTransition(QueryPhase.Complete, QueryPhase.Live))
        assertTrue(!isLegalPhaseTransition(QueryPhase.TimedOut, QueryPhase.Connecting))
        assertTrue(!isLegalPhaseTransition(QueryPhase.Failed, QueryPhase.Connecting))
    }

    @Test
    fun deltasUrlUsesRelayOrigin() {
        assertEquals(
            "https://relay.example.com/deltas?from=0",
            "wss://relay.example.com/nostr?x=1#frag".normalizeRelayUrl().deltasUrl(0),
        )
        assertEquals(
            "http://127.0.0.1:3334/deltas?from=7",
            "ws://127.0.0.1:3334".normalizeRelayUrl().deltasUrl(7),
        )
    }

    @Test
    fun bolt11ParsesMilliAmount() {
        assertEquals(100_000, Bolt11.amountSats("lnbc1m1p..."))
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
