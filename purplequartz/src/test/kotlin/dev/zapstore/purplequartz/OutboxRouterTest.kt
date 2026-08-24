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
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class OutboxRouterTest {
    private val bootstrap = setOf("wss://bootstrap.example".normalizeRelayUrl())
    private val fallback = setOf("wss://fallback.example".normalizeRelayUrl())
    private val outbox = "wss://outbox.example".normalizeRelayUrl()
    private val author = "2".repeat(64)

    @Test
    fun `resolveReadRelays returns read and unmarked relays from the local store`() = runBlocking {
        val store = RouterTestStore()
        store.insert(relayListEvent(arrayOf(
            arrayOf("r", "wss://read.example"),
            arrayOf("r", "wss://read2.example", "read"),
            arrayOf("r", "wss://write.example", "write"),
            arrayOf("r", "http://insecure.example"),
        )))
        val client = RouterTestClient()
        val purpleQuartz = PurpleQuartz.createForTesting(store, client, this)
        val router = OutboxRouter(purpleQuartz, bootstrap)

        var resolved: Set<NormalizedRelayUrl>? = null
        val resolveJob = launch { resolved = router.resolveReadRelays(author) }
        client.eose(client.awaitSub(kinds = listOf(OutboxRouter.RELAY_LIST_KIND)))
        withTimeout(2.seconds) { resolveJob.join() }

        assertEquals(
            setOf("wss://read.example".normalizeRelayUrl(), "wss://read2.example".normalizeRelayUrl()),
            resolved,
        )
        purpleQuartz.close()
    }

    @Test
    fun `resolveReadRelays uses the freshness cache without touching relays`() = runBlocking {
        val store = RouterTestStore()
        store.insert(relayListEvent(arrayOf(arrayOf("r", "wss://read.example"))))
        val refreshCache = InMemoryQueryRefreshCache()
        val filter = Filter(authors = listOf(author), kinds = listOf(OutboxRouter.RELAY_LIST_KIND), limit = 1)
        refreshCache.recordRefresh(QueryFingerprint.create(listOf(filter), bootstrap), System.currentTimeMillis())
        val client = RouterTestClient()
        val purpleQuartz = PurpleQuartz.createForTesting(store, client, this, refreshCache = refreshCache)
        val router = OutboxRouter(purpleQuartz, bootstrap)

        val resolved = withTimeout(2.seconds) { router.resolveReadRelays(author) }

        assertEquals(setOf("wss://read.example".normalizeRelayUrl()), resolved)
        assertTrue(client.subs.isEmpty())
        purpleQuartz.close()
    }

    @Test
    fun `queryWithOutbox restarts the remote side on the expanded relay set`() = runBlocking {
        val store = RouterTestStore()
        store.insert(relayListEvent(arrayOf(arrayOf("r", outbox.url))))
        val client = RouterTestClient()
        val purpleQuartz = PurpleQuartz.createForTesting(store, client, this)
        val router = OutboxRouter(purpleQuartz, bootstrap)

        val collection = launch {
            router.queryWithOutbox(
                listOf(Filter(kinds = listOf(9735), limit = 10)),
                authors = listOf(author),
                fallbackRelays = fallback,
            ).collect { }
        }

        val fallbackSub = client.awaitSub(kinds = listOf(9735), relays = fallback)
        client.eose(client.awaitSub(kinds = listOf(OutboxRouter.RELAY_LIST_KIND)))
        val expandedSub = client.awaitSub(kinds = listOf(9735), relays = fallback + outbox, skip = fallbackSub)

        assertEquals(fallback + outbox, expandedSub.relays)
        collection.cancelAndJoin()
        purpleQuartz.close()
    }

    @Test
    fun `queryWithOutbox can replace the fallback set with resolved relays`() = runBlocking {
        val store = RouterTestStore()
        store.insert(relayListEvent(arrayOf(arrayOf("r", outbox.url))))
        val client = RouterTestClient()
        val purpleQuartz = PurpleQuartz.createForTesting(store, client, this)
        val router = OutboxRouter(purpleQuartz, bootstrap)

        val collection = launch {
            router.queryWithOutbox(
                listOf(Filter(kinds = listOf(0), limit = 1)),
                authors = listOf(author),
                fallbackRelays = fallback,
                unionWithFallback = false,
            ).collect { }
        }

        client.awaitSub(kinds = listOf(0), relays = fallback)
        client.eose(client.awaitSub(kinds = listOf(OutboxRouter.RELAY_LIST_KIND)))
        val expandedSub = client.awaitSub(kinds = listOf(0), relays = setOf(outbox))

        assertEquals(setOf(outbox), expandedSub.relays)
        collection.cancelAndJoin()
        purpleQuartz.close()
    }

    @Test
    fun `queryWithOutbox keeps the fallback relays when nothing resolves`() = runBlocking {
        val store = RouterTestStore()
        val client = RouterTestClient()
        val purpleQuartz = PurpleQuartz.createForTesting(store, client, this)
        val router = OutboxRouter(purpleQuartz, bootstrap)

        val collection = launch {
            router.queryWithOutbox(
                listOf(Filter(kinds = listOf(9735), limit = 10)),
                authors = listOf(author),
                fallbackRelays = fallback,
            ).collect { }
        }

        client.awaitSub(kinds = listOf(9735), relays = fallback)
        client.eose(client.awaitSub(kinds = listOf(OutboxRouter.RELAY_LIST_KIND)))
        delay(300)

        assertEquals(1, client.subs.count { it.kinds == listOf(9735) })
        collection.cancelAndJoin()
        purpleQuartz.close()
    }

    @Test
    fun `resolveReadRelays returns empty when the fetch cannot complete`() = runBlocking {
        val store = RouterTestStore()
        val client = RouterTestClient()
        val purpleQuartz = PurpleQuartz.createForTesting(store, client, this)
        val router = OutboxRouter(purpleQuartz, bootstrap, resolveTimeout = 1.seconds)

        var resolved: Set<NormalizedRelayUrl>? = null
        val resolveJob = launch { resolved = router.resolveReadRelays(author) }
        client.fail(client.awaitSub(kinds = listOf(OutboxRouter.RELAY_LIST_KIND)))
        withTimeout(4.seconds) { resolveJob.join() }

        assertEquals(emptySet<NormalizedRelayUrl>(), resolved)
        purpleQuartz.close()
    }

    private fun relayListEvent(tags: Array<Array<String>>): Event = Event(
        id = "3".repeat(64),
        pubKey = author,
        createdAt = 1_750_000_000,
        kind = OutboxRouter.RELAY_LIST_KIND,
        tags = tags,
        content = "",
        sig = "4".repeat(128),
    )
}

private class RouterTestClient : INostrClient by EmptyNostrClient() {
    class Sub(
        val filters: Map<NormalizedRelayUrl, List<Filter>>,
        val listener: SubscriptionListener,
    ) {
        val relays: Set<NormalizedRelayUrl> get() = filters.keys
        val kinds: List<Int> get() = filters.values.flatten().flatMap { it.kinds.orEmpty() }.distinct().sorted()
    }

    val subs = CopyOnWriteArrayList<Sub>()

    override fun subscribe(
        subId: String,
        filters: Map<NormalizedRelayUrl, List<Filter>>,
        listener: SubscriptionListener?,
    ) {
        if (listener != null) subs += Sub(filters, listener)
    }

    override fun unsubscribe(subId: String) = Unit

    suspend fun awaitSub(
        kinds: List<Int>,
        relays: Set<NormalizedRelayUrl>? = null,
        skip: Sub? = null,
    ): Sub = withTimeout(2.seconds) {
        while (true) {
            subs.firstOrNull {
                it !== skip && it.kinds == kinds && (relays == null || it.relays == relays)
            }?.let { return@withTimeout it }
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    fun eose(sub: Sub) {
        sub.filters.forEach { (relay, filters) ->
            sub.listener.onSubscriptionStarted(relay.url, filters)
            sub.listener.onEose(relay, filters)
        }
    }

    fun fail(sub: Sub) {
        sub.filters.forEach { (relay, filters) ->
            sub.listener.onCannotConnect(relay, "relay unreachable", filters)
        }
    }
}

private class RouterTestStore : IEventStore {
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
