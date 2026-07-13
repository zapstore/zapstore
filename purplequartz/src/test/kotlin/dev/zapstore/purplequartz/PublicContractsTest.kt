package dev.zapstore.purplequartz

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
        assertFails { PurpleQuartzConfig(databaseName = "../store.db").validate() }
        assertFails { PurpleQuartzConfig(oneShotTimeout = 0.milliseconds).validate() }
        assertFails { PurpleQuartzConfig(ingestionCapacity = 0).validate() }
        assertFails { PurpleQuartzConfig(expirationSweepInterval = 0.milliseconds).validate() }
    }

    @Test
    fun `remote sources require at least one normalized relay`() {
        assertFails { QuerySource.Remote(emptySet()) }
        assertFails { QuerySource.LocalAndRemote(emptySet()) }
        assertFails { RemoteMode.OneShot(0.milliseconds) }

        val relay = "wss://relay.example".normalizeRelayUrl()
        assertFails { QuerySource.LocalAndRemote(setOf(relay), cachedFor = 0.milliseconds) }
        assertFails { QuerySource.LocalAndRemote(setOf(relay), cachedFor = Duration.INFINITE) }
        assertTrue(QuerySource.Remote(setOf(relay)).relays.contains(relay))
        assertTrue(QuerySource.LocalAndRemote(setOf(relay), cachedFor = 6.hours).relays.contains(relay))
    }

    @Test
    fun `published Quartz APIs used by the facade remain callable`() {
        // This is deliberately a compile-level compatibility gate for the public APIs
        // PurpleQuartz relies on. Behavioral compatibility is covered by Android tests.
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
