package dev.zapstore.iolite

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.EmptyNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.store.FtsReindexProgress
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class ObservationTest {
    private val relay = "wss://relay.example".normalizeRelayUrl()
    private val eventSequence = AtomicInteger()

    @Test
    fun `sessions do not re-emit on other sessions inserts`() = runBlocking {
        val client = MultiSubClient()
        val purpleQuartz = Iolite.createForTesting(
            RecordingEventStore(),
            client,
            this,
            eventVerifier = { true },
        )
        val statesA = CopyOnWriteArrayList<QueryState>()
        val statesB = CopyOnWriteArrayList<QueryState>()
        val collectionA = collect(purpleQuartz, Filter(kinds = listOf(1)), statesA)
        val collectionB = collect(purpleQuartz, Filter(kinds = listOf(7)), statesB)

        client.awaitSubscriptions(2)
        client.started(relay)
        client.eose(relay)
        awaitState(statesA) { it.sync == QuerySync.Live }
        awaitState(statesB) { it.sync == QuerySync.Live }
        val emittedA = statesA.size

        client.event(relay, signedEvent(kind = 7, content = "for-b"))
        awaitState(statesB) { state -> state.items.any { it.content == "for-b" } }
        delay(150)

        assertEquals(emittedA, statesA.size)

        collectionA.cancel()
        collectionB.cancel()
        collectionA.join()
        collectionB.join()
        purpleQuartz.close()
    }

    @Test
    fun `replaceable update re-emits with the new version`() = runBlocking {
        val client = MultiSubClient()
        val purpleQuartz = Iolite.createForTesting(
            RecordingEventStore(),
            client,
            this,
            eventVerifier = { true },
        )
        val states = CopyOnWriteArrayList<QueryState>()
        val collection = collect(purpleQuartz, Filter(kinds = listOf(0)), states)

        client.awaitSubscriptions(1)
        client.started(relay)
        client.eose(relay)
        awaitState(states) { it.sync == QuerySync.Live }

        val v1 = signedEvent(kind = 0, content = "v1", createdAt = 100)
        client.event(relay, v1)
        awaitState(states) { state -> state.items.any { it.id == v1.id } }

        val v2 = signedEvent(kind = 0, content = "v2", createdAt = 200)
        client.event(relay, v2)
        val updated = awaitState(states) { it.items.singleOrNull()?.id == v2.id }
        assertEquals("v2", updated.items.single().content)

        collection.cancel()
        collection.join()
        purpleQuartz.close()
    }

    @Test
    fun `events are persisted via a single batch at eose`() = runBlocking {
        val client = MultiSubClient()
        val store = RecordingEventStore()
        val purpleQuartz = Iolite.createForTesting(
            store,
            client,
            this,
            config = IoliteConfig(ingestFlushInterval = 10.seconds),
            eventVerifier = { true },
        )
        val states = CopyOnWriteArrayList<QueryState>()
        val source = QuerySource.LocalAndRemote(
            relays = setOf(relay),
            mode = RemoteMode.OneShot(5.seconds),
            cachedFor = 6.hours,
        )
        val collection = launch {
            purpleQuartz.query(Filter(kinds = listOf(1)), source).collect(states::add)
        }

        client.awaitSubscriptions(1)
        client.started(relay)
        val events = listOf(
            signedEvent(content = "one"),
            signedEvent(content = "two"),
            signedEvent(content = "three"),
        )
        events.forEach { client.event(relay, it) }
        client.eose(relay)

        val complete = awaitState(states) { it.sync == QuerySync.Complete }
        assertEquals(events.map { it.id }.toSet(), complete.items.map { it.id }.toSet())
        assertEquals(listOf(events.map { it.id }), store.batches)

        withTimeout(2.seconds) { collection.join() }
        purpleQuartz.close()
    }

    @Test
    fun `remote burst is coalesced and complete state contains every event`() = runBlocking {
        val client = MultiSubClient()
        val purpleQuartz = Iolite.createForTesting(
            RecordingEventStore(),
            client,
            this,
            config = IoliteConfig(ingestBatchSize = 1_000, ingestFlushInterval = 10.seconds),
            eventVerifier = { true },
        )
        val states = CopyOnWriteArrayList<QueryState>()
        val collection = launch {
            purpleQuartz.query(
                Filter(kinds = listOf(1)),
                QueryOptions.remote(setOf(relay), RemoteMode.OneShot(5.seconds)),
            ).collect(states::add)
        }

        client.awaitSubscriptions(1)
        client.started(relay)
        val events = (0 until 500).map { signedEvent(content = "burst-$it") }
        events.forEach { client.event(relay, it) }
        client.eose(relay)

        val complete = awaitState(states) { it.phase == QueryPhase.Complete }
        assertEquals(events.map(Event::id), complete.items.map(Event::id))
        assertTrue("burst snapshots were not coalesced", states.size < events.size / 2)

        withTimeout(2.seconds) { collection.join() }
        purpleQuartz.close()
    }

    @Test
    fun `duplicate batch rejection is verified with one id query`() = runBlocking {
        val client = MultiSubClient()
        val store = RecordingEventStore(rejectBatchInserts = true)
        val events = (0 until 100).map { signedEvent(content = "duplicate-$it") }
        events.forEach { store.insert(it) }
        val purpleQuartz = Iolite.createForTesting(
            store,
            client,
            this,
            config = IoliteConfig(ingestBatchSize = 100, ingestFlushInterval = 10.seconds),
            eventVerifier = { true },
        )
        val states = CopyOnWriteArrayList<QueryState>()
        val collection = launch {
            purpleQuartz.query(
                Filter(kinds = listOf(1)),
                QueryOptions.remote(setOf(relay), RemoteMode.OneShot(5.seconds)),
            ).collect(states::add)
        }

        client.awaitSubscriptions(1)
        client.started(relay)
        events.forEach { client.event(relay, it) }
        client.eose(relay)

        awaitState(states) { it.phase == QueryPhase.Complete }
        assertEquals(1, store.queryCalls.get())

        withTimeout(2.seconds) { collection.join() }
        purpleQuartz.close()
    }

    @Test
    fun `slow local collector cannot block remote persistence`() = runBlocking {
        val client = MultiSubClient()
        val purpleQuartz = Iolite.createForTesting(
            RecordingEventStore(),
            client,
            this,
            config = IoliteConfig(ingestBatchSize = 500, ingestFlushInterval = 10.seconds),
            eventVerifier = { true },
        )
        val slowCollection = launch {
            purpleQuartz.query(Filter(kinds = listOf(1))).collect {
                delay(100)
            }
        }
        val remoteStates = CopyOnWriteArrayList<QueryState>()
        val remoteCollection = launch {
            purpleQuartz.query(
                Filter(kinds = listOf(1)),
                QueryOptions.remote(setOf(relay), RemoteMode.OneShot(5.seconds)),
            ).collect(remoteStates::add)
        }

        client.awaitSubscriptions(1)
        client.started(relay)
        repeat(400) { client.event(relay, signedEvent(content = "write-$it")) }
        client.eose(relay)

        val complete = awaitState(remoteStates) { it.phase == QueryPhase.Complete }
        assertEquals(400, complete.items.size)

        withTimeout(2.seconds) { remoteCollection.join() }
        slowCollection.cancel()
        slowCollection.join()
        purpleQuartz.close()
    }

    private fun kotlinx.coroutines.CoroutineScope.collect(
        purpleQuartz: Iolite,
        filter: Filter,
        states: MutableList<QueryState>,
    ): Job = launch {
        purpleQuartz.query(
            filter,
            QuerySource.LocalAndRemote(setOf(relay), RemoteMode.Stream, cachedFor = 6.hours),
        ).collect(states::add)
    }

    private suspend fun awaitState(
        states: List<QueryState>,
        predicate: (QueryState) -> Boolean,
    ): QueryState = withTimeout(5.seconds) {
        while (true) {
            states.lastOrNull(predicate)?.let { return@withTimeout it }
            delay(10)
        }
        error("unreachable")
    }

    private fun signedEvent(
        kind: Int = 1,
        content: String,
        createdAt: Long = 1_750_000_000,
    ): Event {
        val id = eventSequence.incrementAndGet().toString(16).padStart(64, '0')
        return Event(
            id = id,
            pubKey = "1".repeat(64),
            createdAt = createdAt,
            kind = kind,
            tags = emptyArray(),
            content = content,
            sig = "2".repeat(128),
        )
    }
}

private class MultiSubClient : INostrClient by EmptyNostrClient() {
    private data class Sub(
        val listener: SubscriptionListener,
        val filters: Map<NormalizedRelayUrl, List<Filter>>,
    )

    private val subs = CopyOnWriteArrayList<Sub>()

    override fun subscribe(
        subId: String,
        filters: Map<NormalizedRelayUrl, List<Filter>>,
        listener: SubscriptionListener?,
    ) {
        if (listener != null) subs += Sub(listener, filters)
    }

    override fun unsubscribe(subId: String) = Unit

    suspend fun awaitSubscriptions(count: Int) {
        withTimeout(2.seconds) {
            while (subs.size < count) delay(10)
        }
    }

    fun started(relay: NormalizedRelayUrl) {
        subs.forEach { it.listener.onSubscriptionStarted(relay.url, it.filters[relay].orEmpty()) }
    }

    fun eose(relay: NormalizedRelayUrl) {
        subs.forEach { it.listener.onEose(relay, it.filters[relay]) }
    }

    fun event(relay: NormalizedRelayUrl, event: Event) {
        subs.forEach { sub ->
            val filters = sub.filters[relay].orEmpty()
            if (filters.any { it.match(event) }) {
                sub.listener.onEvent(event, true, relay, filters)
            }
        }
    }
}

private class RecordingEventStore(
    private val rejectBatchInserts: Boolean = false,
) : IEventStore {
    override val relay: NormalizedRelayUrl? = null
    val batches = CopyOnWriteArrayList<List<String>>()
    val queryCalls = AtomicInteger()
    private val events = mutableListOf<Event>()

    override suspend fun insert(event: Event) {
        synchronized(events) {
            if (events.none { it.id == event.id }) events += event
        }
    }

    override suspend fun batchInsert(events: List<Event>): List<IEventStore.InsertOutcome> {
        batches += events.map { it.id }
        if (rejectBatchInserts) return events.map { IEventStore.InsertOutcome.Rejected("duplicate") }
        return events.map { event ->
            synchronized(this.events) {
                if (this.events.none { it.id == event.id }) this.events += event
            }
            IEventStore.InsertOutcome.Accepted
        }
    }

    override suspend fun transaction(body: IEventStore.ITransaction.() -> Unit) {
        body(object : IEventStore.ITransaction {
            override fun insert(event: Event) {
                synchronized(events) {
                    if (events.none { it.id == event.id }) events += event
                }
            }
        })
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Event> query(filter: Filter): List<T> {
        queryCalls.incrementAndGet()
        return synchronized(events) { events.filter(filter::match).map { it as T } }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Event> query(filters: List<Filter>): List<T> {
        queryCalls.incrementAndGet()
        return synchronized(events) {
            events.filter { event -> filters.any { it.match(event) } }.map { it as T }
        }
    }

    override suspend fun <T : Event> query(filter: Filter, onEach: (T) -> Unit) {
        query<T>(filter).forEach(onEach)
    }

    override suspend fun <T : Event> query(filters: List<Filter>, onEach: (T) -> Unit) {
        query<T>(filters).forEach(onEach)
    }

    override suspend fun count(filter: Filter): Int = query<Event>(filter).size
    override suspend fun count(filters: List<Filter>): Int = query<Event>(filters).size

    override suspend fun delete(filter: Filter) {
        synchronized(events) { events.removeAll(filter::match) }
    }

    override suspend fun delete(filters: List<Filter>) {
        synchronized(events) {
            events.removeAll { event -> filters.any { it.match(event) } }
        }
    }

    override suspend fun deleteExpiredEvents() = Unit
    override suspend fun reindexFullTextSearch() = Unit

    override suspend fun reindexFullTextSearch(
        resumeFrom: String?,
        batchSize: Int,
    ): FtsReindexProgress = throw UnsupportedOperationException()

    override fun close() = Unit
}
