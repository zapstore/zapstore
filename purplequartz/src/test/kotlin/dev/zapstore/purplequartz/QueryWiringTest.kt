package dev.zapstore.purplequartz

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.EmptyNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.store.FtsReindexProgress
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryWiringTest {
    @Test
    fun `local query emits an empty local seed without subscribing`() = runBlocking {
        val client = RecordingClient()
        val purpleQuartz = PurpleQuartz.createForTesting(MemoryEventStore(), client, this)

        val states = purpleQuartz.query(Filter(kinds = listOf(1))).take(1).toList()

        assertEquals(listOf(QuerySync.LocalOnly), states.map { it.sync })
        assertTrue(states.single().items.isEmpty())
        assertTrue(client.requests.isEmpty())
        purpleQuartz.close()
    }

    @Test
    fun `remote request uses exactly the supplied relay set`() = runBlocking {
        val client = RecordingClient()
        val purpleQuartz = PurpleQuartz.createForTesting(MemoryEventStore(), client, this)
        val relays = setOf(
            "wss://one.example".normalizeRelayUrl(),
            "wss://two.example".normalizeRelayUrl(),
        )

        val collection = launch {
            purpleQuartz.query(
                Filter(kinds = listOf(1)),
                QuerySource.Remote(relays),
            ).collect { }
        }
        withTimeout(1_000) {
            while (client.requests.isEmpty()) delay(10)
        }

        assertEquals(1, client.requests.size)
        assertEquals(relays, client.requests.single().keys)
        assertTrue(client.requests.single().values.all { it.single().kinds == listOf(1) })

        collection.cancelAndJoin()
        purpleQuartz.close()
    }

    @Test
    fun `foreground refresh bypasses relay retry delays`() = runBlocking {
        val client = RecordingClient()
        val purpleQuartz = PurpleQuartz.createForTesting(MemoryEventStore(), client, this)

        purpleQuartz.refreshConnections()

        assertEquals(listOf(ReconnectCall(true, true)), client.reconnectCalls)
        purpleQuartz.close()
    }

}

private class RecordingClient : INostrClient by EmptyNostrClient() {
    val requests = mutableListOf<Map<NormalizedRelayUrl, List<Filter>>>()
    val reconnectCalls = mutableListOf<ReconnectCall>()

    override fun subscribe(
        subId: String,
        filters: Map<NormalizedRelayUrl, List<Filter>>,
        listener: SubscriptionListener?,
    ) {
        requests += filters
    }

    override fun reconnect(onlyIfChanged: Boolean, ignoreRetryDelays: Boolean) {
        reconnectCalls += ReconnectCall(onlyIfChanged, ignoreRetryDelays)
    }
}

private data class ReconnectCall(
    val onlyIfChanged: Boolean,
    val ignoreRetryDelays: Boolean,
)

private class MemoryEventStore : IEventStore {
    override val relay: NormalizedRelayUrl? = null
    private val events = mutableListOf<Event>()

    override suspend fun insert(event: Event) {
        events += event
    }

    override suspend fun transaction(body: IEventStore.ITransaction.() -> Unit) {
        body(object : IEventStore.ITransaction {
            override fun insert(event: Event) {
                events += event
            }
        })
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Event> query(filter: Filter): List<T> =
        events.filter(filter::match).map { it as T }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Event> query(filters: List<Filter>): List<T> =
        events.filter { event -> filters.any { it.match(event) } }.map { it as T }

    override suspend fun <T : Event> query(filter: Filter, onEach: (T) -> Unit) {
        query<T>(filter).forEach(onEach)
    }

    override suspend fun <T : Event> query(filters: List<Filter>, onEach: (T) -> Unit) {
        query<T>(filters).forEach(onEach)
    }

    override suspend fun count(filter: Filter): Int = query<Event>(filter).size

    override suspend fun count(filters: List<Filter>): Int = query<Event>(filters).size

    override suspend fun delete(filter: Filter) {
        events.removeAll(filter::match)
    }

    override suspend fun delete(filters: List<Filter>) {
        events.removeAll { event -> filters.any { it.match(event) } }
    }

    override suspend fun deleteExpiredEvents() = Unit

    override suspend fun reindexFullTextSearch() = Unit

    override suspend fun reindexFullTextSearch(
        resumeFrom: String?,
        batchSize: Int,
    ): FtsReindexProgress = throw UnsupportedOperationException()

    override fun close() = Unit
}
