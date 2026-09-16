package dev.zapstore.iolite

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.ObservableEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import java.lang.reflect.Modifier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicContractsTest {
    @Test
    fun `config rejects invalid lifecycle values`() {
        assertFails { IoliteConfig(databaseName = "../store.db").validate() }
        assertFails { IoliteConfig(oneShotTimeout = 0.milliseconds).validate() }
        assertFails { IoliteConfig(ingestionCapacity = 0).validate() }
        assertFails { IoliteConfig(expirationSweepInterval = 0.milliseconds).validate() }
        assertFails { IoliteConfig(ingestBatchSize = 0).validate() }
        assertFails { IoliteConfig(ingestFlushInterval = 0.milliseconds).validate() }
        assertFails { IoliteConfig(pruneRules = mapOf(9735 to 0.milliseconds)).validate() }
        assertFails { IoliteConfig(pruneRules = mapOf(-1 to 1.hours)).validate() }
    }

    @Test
    fun `query options enforce complementary source and remote modes`() {
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
        assertTrue(
            QueryOptions.localAndRemote(setOf(relay), RemoteMode.Stream, cachedFor = 6.hours)
                .relays.contains(relay),
        )
    }

    @Test
    fun `published Quartz APIs used by the facade remain callable`() {
        // This is deliberately a compile-level compatibility gate for the public APIs
        // Iolite relies on. Behavioral compatibility is covered by Android tests.
        val invalidEvent = Event(
            id = "0".repeat(64),
            pubKey = "0".repeat(64),
            createdAt = 0,
            kind = 1,
            tags = emptyArray(),
            content = "",
            sig = "0".repeat(128),
        )
        assertTrue(invalidEvent.isExpired().not())

        val filter = Filter(kinds = listOf(1))
        assertTrue(filter.match(invalidEvent))

        assertPublicType<INostrClient>()
        assertPublicType<NostrClient>()
        assertPublicType<SubscriptionListener>()
        assertPublicType<WebsocketBuilder>()
        assertPublicType<IEventStore>()
        assertPublicType<ObservableEventStore>()
        assertPublicType<EventStore>()
    }

    @Test
    fun `query phases reject transitions out of terminal states`() {
        assertTrue(isLegalPhaseTransition(null, QueryPhase.Connecting))
        assertTrue(isLegalPhaseTransition(QueryPhase.Connecting, QueryPhase.CatchingUp))
        assertTrue(isLegalPhaseTransition(QueryPhase.CatchingUp, QueryPhase.Live))
        assertTrue(isLegalPhaseTransition(QueryPhase.Live, QueryPhase.Connecting))
        assertTrue(isLegalPhaseTransition(QueryPhase.Cached, QueryPhase.Connecting))
        assertTrue(!isLegalPhaseTransition(QueryPhase.Complete, QueryPhase.Live))
        assertTrue(!isLegalPhaseTransition(QueryPhase.TimedOut, QueryPhase.Connecting))
        assertTrue(!isLegalPhaseTransition(QueryPhase.Failed, QueryPhase.Connecting))
    }

    private inline fun <reified T> assertPublicType() {
        assertTrue(Modifier.isPublic(T::class.java.modifiers))
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
