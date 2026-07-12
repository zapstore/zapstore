package dev.zapstore.purplequartz

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.EmptyNostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toRelay.Command
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.store.FtsReindexProgress
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class QueryInvariantTest {
    private val relay = "wss://relay.example".normalizeRelayUrl()
    private val eventSequence = AtomicInteger()

    @Test
    fun `successful empty one-shot is cached across facades and expires`() = runBlocking {
        val cache = InMemoryQueryRefreshCache()
        val clock = MutableEpochClock(1_000)
        val filter = Filter(kinds = listOf(1))
        val source = QuerySource.LocalAndRemote(
            relays = setOf(relay),
            mode = RemoteMode.OneShot(5.seconds),
            cachedFor = 6.hours,
        )
        val firstClient = ControlledClient()
        val first = PurpleQuartz.createForTesting(
            ControlledEventStore(),
            firstClient,
            this,
            databasePath = "persistent-cache-test",
            eventVerifier = { true },
            refreshCache = cache,
            clock = clock,
        )
        val firstStates = CopyOnWriteArrayList<QueryState>()
        val firstCollection = launch { first.query(filter, source).collect(firstStates::add) }

        firstClient.awaitSubscription()
        firstClient.started(relay)
        firstClient.eose(relay)
        awaitState(firstStates) { it.sync == QuerySync.Complete }
        withTimeout(2.seconds) { firstCollection.join() }
        first.close()

        val secondClient = ControlledClient()
        val second = PurpleQuartz.createForTesting(
            ControlledEventStore(),
            secondClient,
            this,
            databasePath = "persistent-cache-test",
            eventVerifier = { true },
            refreshCache = cache,
            clock = clock,
        )
        val cachedStates = second.query(filter, source).toList()

        assertEquals(listOf(QuerySync.Complete), cachedStates.map(QueryState::sync))
        assertTrue(cachedStates.single().relays.isEmpty())
        assertTrue(secondClient.requests.isEmpty())

        clock.advance(6.hours.inWholeMilliseconds)
        val staleCollection = launch { second.query(filter, source).collect { } }
        secondClient.awaitSubscription()
        assertEquals(1, secondClient.requests.size)

        staleCollection.cancel()
        staleCollection.join()
        second.close()
    }

    @Test
    fun `fresh stream defers remote and observes an extended refresh window`() = runBlocking {
        val cache = InMemoryQueryRefreshCache()
        val filter = Filter(kinds = listOf(1))
        val source = QuerySource.LocalAndRemote(
            relays = setOf(relay),
            mode = RemoteMode.Stream,
            cachedFor = 1.seconds,
        )
        val fingerprint = QueryFingerprint.create(listOf(filter), setOf(relay))
        cache.recordRefresh(fingerprint, System.currentTimeMillis())
        val client = ControlledClient()
        val purpleQuartz = PurpleQuartz.createForTesting(
            ControlledEventStore(),
            client,
            this,
            eventVerifier = { true },
            refreshCache = cache,
        )
        val states = CopyOnWriteArrayList<QueryState>()
        val collection = collect(purpleQuartz, source, states)

        val cached = awaitState(states) { it.sync == QuerySync.Cached }
        assertTrue(cached.relays.isEmpty())
        assertTrue(client.requests.isEmpty())

        cache.recordRefresh(fingerprint, System.currentTimeMillis())
        delay(700)
        cache.recordRefresh(fingerprint, System.currentTimeMillis())
        delay(500)
        assertTrue(client.requests.isEmpty())

        client.awaitSubscription()
        assertEquals(1, client.requests.size)

        collection.cancel()
        collection.join()
        purpleQuartz.close()
    }

    @Test
    fun `timeout and persistence failure do not populate freshness`() = runBlocking {
        val filter = Filter(kinds = listOf(1))
        val source = QuerySource.LocalAndRemote(
            relays = setOf(relay),
            mode = RemoteMode.OneShot(50.milliseconds),
            cachedFor = 1.hours,
        )
        val fingerprint = QueryFingerprint.create(listOf(filter), setOf(relay))

        val timeoutCache = InMemoryQueryRefreshCache()
        val timeoutClient = ControlledClient()
        val timeoutQuartz = PurpleQuartz.createForTesting(
            ControlledEventStore(),
            timeoutClient,
            this,
            config = PurpleQuartzConfig(oneShotTimeout = 50.milliseconds),
            eventVerifier = { true },
            refreshCache = timeoutCache,
        )
        val timeoutStates = CopyOnWriteArrayList<QueryState>()
        val timeoutCollection = collect(timeoutQuartz, source, timeoutStates)
        timeoutClient.awaitSubscription()
        awaitState(timeoutStates) { it.sync == QuerySync.TimedOut }
        withTimeout(2.seconds) { timeoutCollection.join() }
        assertEquals(null, timeoutCache.lastRefresh(fingerprint))
        timeoutQuartz.close()

        val failureCache = InMemoryQueryRefreshCache()
        val failureClient = ControlledClient()
        val failureQuartz = PurpleQuartz.createForTesting(
            ControlledEventStore(failInserts = true),
            failureClient,
            this,
            eventVerifier = { true },
            refreshCache = failureCache,
        )
        val failureStates = CopyOnWriteArrayList<QueryState>()
        val failureCollection = collect(failureQuartz, source, failureStates)
        failureClient.awaitSubscription()
        failureClient.started(relay)
        failureClient.event(relay, signedEvent(content = "failed-refresh"))
        awaitState(failureStates) { it.sync == QuerySync.Failed }
        withTimeout(2.seconds) { failureCollection.join() }
        assertEquals(null, failureCache.lastRefresh(fingerprint))
        failureQuartz.close()
    }

    @Test
    fun `failed final local projection fails query without caching`() = runBlocking {
        val cache = InMemoryQueryRefreshCache()
        val client = ControlledClient()
        val filter = Filter(kinds = listOf(1))
        val source = QuerySource.LocalAndRemote(
            relays = setOf(relay),
            mode = RemoteMode.OneShot(5.seconds),
            cachedFor = 1.hours,
        )
        val fingerprint = QueryFingerprint.create(listOf(filter), setOf(relay))
        val purpleQuartz = PurpleQuartz.createForTesting(
            ControlledEventStore(failQueriesAfter = 1),
            client,
            this,
            eventVerifier = { true },
            refreshCache = cache,
        )
        val states = CopyOnWriteArrayList<QueryState>()
        val collection = collect(purpleQuartz, source, states)

        client.awaitSubscription()
        client.started(relay)
        client.eose(relay)

        val failed = awaitState(states) { it.sync == QuerySync.Failed }
        assertTrue(failed.error is QueryError.UnsupportedLocalProjection)
        assertEquals(null, cache.lastRefresh(fingerprint))
        withTimeout(2.seconds) { collection.join() }
        purpleQuartz.close()
    }

    @Test
    fun `reconnect advances generation and accepts replacement EOSE`() = runBlocking {
        val client = ControlledClient()
        val purpleQuartz = PurpleQuartz.createForTesting(
            ControlledEventStore(),
            client,
            this,
            eventVerifier = { true },
        )
        val states = CopyOnWriteArrayList<QueryState>()
        val collection = collect(purpleQuartz, QuerySource.Remote(setOf(relay)), states)

        client.awaitSubscription()
        client.started(relay)
        client.eose(relay)
        awaitState(states) { it.sync == QuerySync.Live && it.relays[relay]?.generation == 1L }

        client.disconnected(relay)
        client.connecting(relay)
        client.started(relay)
        client.eose(relay)

        val reconnected = awaitState(states) {
            it.sync == QuerySync.Live && it.relays[relay]?.generation == 2L
        }
        assertEquals(RelayConnectionState.Connected, reconnected.relays.getValue(relay).connection)
        assertTrue(reconnected.relays.getValue(relay).eose)

        collection.cancel()
        collection.join()
        purpleQuartz.close()
    }

    @Test
    fun `facade close emits lifecycle failure and completes active collectors`() = runBlocking {
        val client = ControlledClient()
        val store = ControlledEventStore()
        val purpleQuartz = PurpleQuartz.createForTesting(store, client, this, eventVerifier = { true })
        val states = CopyOnWriteArrayList<QueryState>()
        val collection = collect(purpleQuartz, QuerySource.Remote(setOf(relay)), states)

        client.awaitSubscription()
        awaitState(states) { it.sync == QuerySync.Connecting }
        purpleQuartz.close()

        withTimeout(2.seconds) { collection.join() }
        assertTrue(states.last().error is QueryError.Lifecycle)
        assertEquals(QuerySync.Failed, states.last().sync)
        assertEquals(1, client.unsubscribeCount.get())
        assertEquals(1, client.closeCount.get())
        assertEquals(1, store.closeCount.get())

        purpleQuartz.close()
    }

    @Test
    fun `local first completion waits for commit and final projection`() = runBlocking {
        val client = ControlledClient()
        val store = ControlledEventStore(blockInserts = true)
        val purpleQuartz = PurpleQuartz.createForTesting(store, client, this, eventVerifier = { true })
        val states = CopyOnWriteArrayList<QueryState>()
        val source = QuerySource.LocalAndRemote(setOf(relay), RemoteMode.OneShot(5.seconds))
        val collection = collect(purpleQuartz, source, states)
        val event = signedEvent(content = "local-first")

        client.awaitSubscription()
        client.started(relay)
        client.event(relay, event)
        client.eose(relay)
        withTimeout(2.seconds) { store.insertionStarted.await() }

        assertFalse(states.any { state -> state.items.any { it.id == event.id } })
        assertFalse(states.any { it.sync == QuerySync.Complete })

        store.releaseInsert.complete(Unit)
        val complete = awaitState(states) { it.sync == QuerySync.Complete }
        assertTrue(complete.items.any { it.id == event.id })
        withTimeout(2.seconds) { collection.join() }
        purpleQuartz.close()
    }

    @Test
    fun `timeout unsubscribes immediately then drains accepted persistence`() = runBlocking {
        val client = ControlledClient()
        val store = ControlledEventStore(blockInserts = true)
        val config = PurpleQuartzConfig(oneShotTimeout = 100.milliseconds)
        val purpleQuartz = PurpleQuartz.createForTesting(store, client, this, config, eventVerifier = { true })
        val states = CopyOnWriteArrayList<QueryState>()
        val source = QuerySource.Remote(setOf(relay), RemoteMode.OneShot())
        val collection = collect(purpleQuartz, source, states)
        val event = signedEvent(content = "timeout")

        client.awaitSubscription()
        client.started(relay)
        client.event(relay, event)
        withTimeout(2.seconds) { store.insertionStarted.await() }
        client.eose(relay)
        withTimeout(2.seconds) {
            while (client.unsubscribeCount.get() == 0) delay(10)
        }

        assertFalse(states.any { it.sync == QuerySync.TimedOut })
        assertFalse(states.any { it.sync == QuerySync.Complete })
        assertTrue(states.any { state -> state.items.any { it.id == event.id } })

        store.releaseInsert.complete(Unit)
        val timedOut = awaitState(states) { it.sync == QuerySync.TimedOut }
        assertTrue(timedOut.items.any { it.id == event.id })
        assertFalse(states.any { it.sync == QuerySync.Complete })
        withTimeout(2.seconds) { collection.join() }
        purpleQuartz.close()
    }

    @Test
    fun `bounded ingestion fails closed instead of dropping EOSE`() = runBlocking {
        val client = ControlledClient()
        val store = ControlledEventStore(blockInserts = true)
        val config = PurpleQuartzConfig(ingestionCapacity = 1)
        val purpleQuartz = PurpleQuartz.createForTesting(store, client, this, config, eventVerifier = { true })
        val states = CopyOnWriteArrayList<QueryState>()
        val source = QuerySource.Remote(setOf(relay), RemoteMode.OneShot(5.seconds))
        val collection = collect(purpleQuartz, source, states)

        client.awaitSubscription()
        client.started(relay)
        client.event(relay, signedEvent(content = "first"))
        withTimeout(2.seconds) { store.insertionStarted.await() }
        client.event(relay, signedEvent(content = "queued"))
        client.eose(relay)

        val failed = awaitState(states) { it.sync == QuerySync.Failed }
        assertTrue(failed.error is QueryError.IngestionSaturated)
        assertFalse(states.any { it.sync == QuerySync.Complete || it.sync == QuerySync.Live })
        assertEquals(1, client.unsubscribeCount.get())
        withTimeout(2.seconds) { collection.join() }
        purpleQuartz.close()
    }

    @Test
    fun `remote persistence failure keeps directly emitted item visible`() = runBlocking {
        val client = ControlledClient()
        val store = ControlledEventStore(failInserts = true)
        val purpleQuartz = PurpleQuartz.createForTesting(store, client, this, eventVerifier = { true })
        val states = CopyOnWriteArrayList<QueryState>()
        val collection = collect(purpleQuartz, QuerySource.Remote(setOf(relay)), states)
        val event = signedEvent(content = "visible-before-save")

        client.awaitSubscription()
        client.started(relay)
        client.event(relay, event)

        val failed = awaitState(states) { it.sync == QuerySync.Failed }
        assertTrue(failed.error is QueryError.PersistenceFailure)
        assertTrue(failed.items.any { it.id == event.id })
        withTimeout(2.seconds) { collection.join() }
        purpleQuartz.close()
    }

    @Test
    fun `relay CLOSED exposes closed state and fails the query`() = runBlocking {
        val client = ControlledClient()
        val purpleQuartz = PurpleQuartz.createForTesting(
            ControlledEventStore(),
            client,
            this,
            eventVerifier = { true },
        )
        val states = CopyOnWriteArrayList<QueryState>()
        val collection = collect(purpleQuartz, QuerySource.Remote(setOf(relay)), states)

        client.awaitSubscription()
        client.started(relay)
        client.closed(relay)

        val failed = awaitState(states) { it.sync == QuerySync.Failed }
        assertTrue(failed.error is QueryError.RelayFailure)
        assertEquals(RelayConnectionState.Closed, failed.relays.getValue(relay).connection)
        withTimeout(2.seconds) { collection.join() }
        purpleQuartz.close()
    }

    @Test
    fun `subscription startup failure is classified as incompatible dependency`() = runBlocking {
        val client = ControlledClient(failSubscribe = true)
        val purpleQuartz = PurpleQuartz.createForTesting(
            ControlledEventStore(),
            client,
            this,
            eventVerifier = { true },
        )
        val states = CopyOnWriteArrayList<QueryState>()
        val collection = collect(purpleQuartz, QuerySource.Remote(setOf(relay)), states)

        val failed = awaitState(states) { it.sync == QuerySync.Failed }
        assertTrue(failed.error is QueryError.IncompatibleDependency)
        withTimeout(2.seconds) { collection.join() }
        purpleQuartz.close()
    }

    private fun kotlinx.coroutines.CoroutineScope.collect(
        purpleQuartz: PurpleQuartz,
        source: QuerySource,
        states: MutableList<QueryState>,
    ): Job = launch {
        purpleQuartz.query(Filter(kinds = listOf(1)), source).collect(states::add)
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

    private fun signedEvent(content: String): Event {
        val id = eventSequence.incrementAndGet().toString(16).padStart(64, '0')
        return Event(
            id = id,
            pubKey = "1".repeat(64),
            createdAt = 1_750_000_000,
            kind = 1,
            tags = emptyArray(),
            content = content,
            sig = "2".repeat(128),
        )
    }
}

private class ControlledClient(
    private val failSubscribe: Boolean = false,
) : INostrClient by EmptyNostrClient() {
    val requests = CopyOnWriteArrayList<Map<NormalizedRelayUrl, List<Filter>>>()
    val unsubscribeCount = AtomicInteger()
    val closeCount = AtomicInteger()
    private val connectionListeners = CopyOnWriteArrayList<RelayConnectionListener>()

    @Volatile
    private var subscriptionListener: SubscriptionListener? = null

    override fun subscribe(
        subId: String,
        filters: Map<NormalizedRelayUrl, List<Filter>>,
        listener: SubscriptionListener?,
    ) {
        if (failSubscribe) throw IllegalStateException("controlled subscription failure")
        requests += filters
        subscriptionListener = listener
    }

    override fun unsubscribe(subId: String) {
        unsubscribeCount.incrementAndGet()
    }

    override fun addConnectionListener(listener: RelayConnectionListener) {
        connectionListeners += listener
    }

    override fun removeConnectionListener(listener: RelayConnectionListener) {
        connectionListeners -= listener
    }

    override fun close() {
        closeCount.incrementAndGet()
    }

    suspend fun awaitSubscription(expectedCount: Int = 1) {
        withTimeout(2.seconds) {
            while (subscriptionListener == null || requests.size < expectedCount) delay(10)
        }
    }

    fun started(relay: NormalizedRelayUrl) {
        subscriptionListener?.onSubscriptionStarted(relay.url, requests.last().getValue(relay))
    }

    fun event(relay: NormalizedRelayUrl, event: Event) {
        subscriptionListener?.onEvent(event, false, relay, requests.last().getValue(relay))
    }

    fun eose(relay: NormalizedRelayUrl) {
        subscriptionListener?.onEose(relay, requests.last().getValue(relay))
    }

    fun closed(relay: NormalizedRelayUrl) {
        subscriptionListener?.onClosed("controlled close", relay, requests.last().getValue(relay))
    }

    fun connecting(relay: NormalizedRelayUrl) {
        val client = TestRelayClient(relay)
        connectionListeners.forEach { it.onConnecting(client) }
    }

    fun disconnected(relay: NormalizedRelayUrl) {
        val client = TestRelayClient(relay)
        connectionListeners.forEach { it.onDisconnected(client) }
    }
}

private class MutableEpochClock(
    private var current: Long,
) : EpochMillisClock {
    override fun now(): Long = current

    fun advance(milliseconds: Long) {
        current += milliseconds
    }
}

private class TestRelayClient(
    override val url: NormalizedRelayUrl,
) : IRelayClient {
    override fun connect() = Unit
    override fun needsToReconnect(): Boolean = false
    override fun connectAndSyncFiltersIfDisconnected(ignoreRetryDelays: Boolean) = Unit
    override fun isConnected(): Boolean = true
    override fun sendOrConnectAndSync(cmd: Command) = Unit
    override fun sendIfConnected(cmd: Command) = Unit
    override fun disconnect() = Unit
}

private class ControlledEventStore(
    private val blockInserts: Boolean = false,
    private val failInserts: Boolean = false,
    private val failQueriesAfter: Int? = null,
) : IEventStore {
    override val relay: NormalizedRelayUrl? = null
    val insertionStarted = CompletableDeferred<Unit>()
    val releaseInsert = CompletableDeferred<Unit>()
    val closeCount = AtomicInteger()
    private val queryCount = AtomicInteger()
    private val events = mutableListOf<Event>()

    override suspend fun insert(event: Event) {
        insertionStarted.complete(Unit)
        if (blockInserts) releaseInsert.await()
        if (failInserts) throw IllegalStateException("controlled insert failure")
        synchronized(events) {
            if (events.none { it.id == event.id }) events += event
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
        beforeQuery()
        return synchronized(events) { events.filter(filter::match).map { it as T } }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Event> query(filters: List<Filter>): List<T> {
        beforeQuery()
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

    override fun close() {
        closeCount.incrementAndGet()
    }

    private fun beforeQuery() {
        val allowed = failQueriesAfter ?: return
        if (queryCount.incrementAndGet() > allowed) {
            throw IllegalStateException("controlled query failure")
        }
    }
}
