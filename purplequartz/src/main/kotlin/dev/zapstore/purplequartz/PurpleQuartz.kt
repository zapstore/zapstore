package dev.zapstore.purplequartz

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.vitorpamplona.quartz.nip01Core.cache.projection.EventStoreProjection
import com.vitorpamplona.quartz.nip01Core.core.Address
import com.vitorpamplona.quartz.nip01Core.core.AddressableEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.isEphemeral
import com.vitorpamplona.quartz.nip01Core.core.isReplaceable
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.client.single.newSubId
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.ObservableEventStore
import com.vitorpamplona.quartz.nip01Core.store.ObservableEventStore.StoreChange
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

class PurpleQuartz private constructor(
    private val store: ObservableEventStore,
    private val client: INostrClient,
    private val scope: CoroutineScope,
    private val job: Job,
    private val databasePath: String,
    private val config: PurpleQuartzConfig,
    private val eventVerifier: (Event) -> Boolean,
    private val connectivityManager: ConnectivityManager?,
    private val refreshCache: QueryRefreshCache,
    private val clock: EpochMillisClock,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val networkCallbackRegistered = AtomicBoolean(false)
    private val sessions = mutableSetOf<QuerySession>()
    private val sessionsLock = Any()
    private val refreshLock = Any()
    private val closeResult = CompletableDeferred<Result<Unit>>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // While relay traffic is suspended (app backgrounded) a network flap
            // must not wake the pool.
            if (client.isActive()) reconnectWithoutBackoff()
        }
    }

    init {
        registerNetworkCallback()
    }

    private sealed interface Inbound {
        val relay: NormalizedRelayUrl
        val generation: Long

        data class Started(override val relay: NormalizedRelayUrl, override val generation: Long) : Inbound
        data class EventReceived(override val relay: NormalizedRelayUrl, override val generation: Long, val event: Event) : Inbound
        data class Eose(override val relay: NormalizedRelayUrl, override val generation: Long) : Inbound
        data class Closed(override val relay: NormalizedRelayUrl, override val generation: Long, val message: String) : Inbound
        data class CannotConnect(override val relay: NormalizedRelayUrl, override val generation: Long, val message: String) : Inbound
        data class Connecting(override val relay: NormalizedRelayUrl, override val generation: Long) : Inbound
        data class Disconnected(override val relay: NormalizedRelayUrl, override val generation: Long) : Inbound
    }

    private data class IngestOutcome(
        val batch: List<Event>? = null,
        val eoseAccepted: Boolean = false,
    )

    private val expirationJob = scope.launch {
        while (isActive) {
            delay(config.expirationSweepInterval)
            if (!closed.get()) runCatching { withStoreOperation { sweepStore() } }
        }
    }

    private suspend fun sweepStore() {
        store.deleteExpiredEvents()
        if (config.pruneRules.isNotEmpty()) {
            val nowSeconds = clock.now() / 1_000
            config.pruneRules.forEach { (kind, maxAge) ->
                store.delete(Filter(kinds = listOf(kind), until = nowSeconds - maxAge.inWholeSeconds))
            }
        }
        // Keeps the SQLite query planner honest as the corpus grows; no-op for other stores.
        (store.inner as? EventStore)?.optimize()
    }

    fun query(filter: Filter, source: QuerySource = QuerySource.Local): Flow<QueryState> = query(listOf(filter), source)

    fun query(filters: List<Filter>, source: QuerySource = QuerySource.Local): Flow<QueryState> = callbackFlow {
        if (closed.get()) {
            trySend(QueryState(emptyList(), QuerySync.Failed, error = QueryError.Lifecycle("PurpleQuartz is closed")))
            close()
            return@callbackFlow
        }
        if (filters.isEmpty()) {
            trySend(QueryState(emptyList(), QuerySync.Failed, error = QueryError.InvalidQuery("At least one filter is required")))
            close()
            return@callbackFlow
        }

        val snapshot = filters.map(::copyFilter)
        val session = QuerySession(snapshot, source, this)
        synchronized(sessionsLock) {
            if (closed.get()) {
                trySend(QueryState(emptyList(), QuerySync.Failed, error = QueryError.Lifecycle("PurpleQuartz is closed")))
                close()
                return@callbackFlow
            }
            sessions += session
        }
        session.start()
        awaitClose {
            session.stop()
            synchronized(sessionsLock) { sessions -= session }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val result = runCatching(::performClose)
            closeResult.complete(result)
            result.getOrThrow()
        } else {
            runBlocking {
                closeResult.await()
            }.getOrThrow()
        }
    }

    /**
     * Notifies the facade that its host application has returned to the
     * foreground. Active relay subscriptions are retried immediately.
     */
    fun refreshConnections() {
        reconnectWithoutBackoff()
    }

    /**
     * Enables or suspends all relay traffic for app foreground/background.
     * Suspending closes every relay socket and stops the keep-alive reconnector;
     * local data remains fully queryable. Re-enabling re-dials stale connections
     * immediately, bypassing retry backoff.
     */
    fun setRelayTrafficEnabled(enabled: Boolean) {
        if (closed.get()) return
        if (enabled) {
            client.connect()
            client.reconnect(onlyIfChanged = true, ignoreRetryDelays = true)
        } else {
            client.disconnect()
        }
    }

    private fun performClose() {
        var firstFailure: Throwable? = null

        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                if (firstFailure == null) {
                    firstFailure = failure
                } else {
                    firstFailure.addSuppressed(failure)
                }
            }
        }

        val active = synchronized(sessionsLock) { sessions.toList().also { sessions.clear() } }
        active.forEach { session -> attempt(session::closeFromFacade) }
        expirationJob.cancel()
        attempt(::unregisterNetworkCallback)
        attempt(client::close)
        job.cancel()
        attempt { runBlocking(Dispatchers.IO) { job.join() } }
        attempt(store::close)
        synchronized(openDatabases) { openDatabases.remove(databasePath) }
        firstFailure?.let { throw it }
    }

    private fun registerNetworkCallback() {
        val manager = connectivityManager ?: return
        try {
            manager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered.set(true)
        } catch (_: SecurityException) {
            // Connectivity callbacks are an enhancement; relay keep-alive
            // reconnects remain available if the host denies registration.
        }
    }

    private fun unregisterNetworkCallback() {
        val manager = connectivityManager ?: return
        if (networkCallbackRegistered.compareAndSet(true, false)) {
            manager.unregisterNetworkCallback(networkCallback)
        }
    }

    private fun reconnectWithoutBackoff() {
        if (!closed.get()) {
            client.reconnect(onlyIfChanged = true, ignoreRetryDelays = true)
        }
    }

    private suspend fun <T> withStoreOperation(block: suspend () -> T): T {
        check(!closed.get()) { "PurpleQuartz is closing" }
        return block()
    }

    private inner class QuerySession(
        private val filters: List<Filter>,
        private val source: QuerySource,
        private val producer: kotlinx.coroutines.channels.ProducerScope<QueryState>,
    ) {
        private val stopped = AtomicBoolean(false)
        private val acceptingCallbacks = AtomicBoolean(true)
        private val timeoutRequested = AtomicBoolean(false)
        private val remotePrepared = AtomicBoolean(false)
        private val remoteStarted = AtomicBoolean(false)
        private val remoteSubscribed = AtomicBoolean(false)
        private val remoteDeferred = AtomicBoolean(false)
        private val stateMutex = Mutex()
        private val emitMutex = Mutex()
        private val batchMutex = Mutex()
        private val flushIoMutex = Mutex()
        private val subId = newSubId()
        private val relays = when (source) {
            QuerySource.Local -> emptySet()
            is QuerySource.LocalAndRemote -> source.relays.toSet()
            is QuerySource.Remote -> source.relays.toSet()
        }
        private val mode = when (source) {
            is QuerySource.LocalAndRemote -> source.mode
            is QuerySource.Remote -> source.mode
            QuerySource.Local -> null
        }
        private val cachedFor = (source as? QuerySource.LocalAndRemote)?.cachedFor
        private val queryFingerprint = if (source is QuerySource.LocalAndRemote) {
            runCatching { QueryFingerprint.create(filters, relays) }.getOrNull()
        } else {
            null
        }
        private val relayStates = mutableMapOf<NormalizedRelayUrl, RelayQueryState>()
        private val callbackGenerations = mutableMapOf<NormalizedRelayUrl, AtomicLong>()
        private val callbackGenerationStarted = mutableMapOf<NormalizedRelayUrl, AtomicBoolean>()
        private var items: List<Event> = emptyList()
        private var error: QueryError? = null
        @Volatile
        private var lastState = QueryState(emptyList(), QuerySync.Connecting, relayStates.toMap())
        private val seenRemoteIds = HashSet<String>()
        private val messages = Channel<Inbound>(config.ingestionCapacity)
        private var observerJob: Job? = null
        private var workerJob: Job? = null
        private var timeoutJob: Job? = null
        private var remoteDelayJob: Job? = null
        private var flushJob: Job? = null
        private val pendingInserts = ArrayList<Event>()
        @Volatile
        private var emittedIds: List<String> = emptyList()
        @Volatile
        private var emittedAddresses: Set<Address> = emptySet()

        private val subscriptionListener = object : SubscriptionListener {
            override fun onSubscriptionStarted(relay: String, forFilters: List<Filter>) {
                relay.normalizeRelayUrl().let { normalized ->
                    callbackGenerationStarted[normalized]?.set(true)
                    enqueue(Inbound.Started(normalized, generationOf(normalized)))
                }
            }

            override fun onEvent(event: Event, isLive: Boolean, relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
                enqueue(Inbound.EventReceived(relay, generationOf(relay), event))
            }

            override fun onEose(relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
                enqueue(Inbound.Eose(relay, generationOf(relay)))
            }

            override fun onClosed(message: String, relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
                enqueue(Inbound.Closed(relay, generationOf(relay), "Relay closed the request"))
            }

            override fun onCannotConnect(relay: NormalizedRelayUrl, message: String, forFilters: List<Filter>?) {
                enqueue(Inbound.CannotConnect(relay, generationOf(relay), "Unable to connect to relay"))
            }
        }

        private val connectionListener = object : RelayConnectionListener {
            override fun onConnecting(relay: IRelayClient) {
                val normalized = relay.url
                if (normalized !in relays) return
                val generation = callbackGenerations.getValue(normalized)
                if (callbackGenerationStarted.getValue(normalized).compareAndSet(true, false)) generation.incrementAndGet()
                enqueue(Inbound.Connecting(normalized, generation.get()))
            }

            override fun onDisconnected(relay: IRelayClient) {
                val normalized = relay.url
                enqueue(Inbound.Disconnected(normalized, generationOf(normalized)))
            }
        }

        fun start() {
            when (source) {
                QuerySource.Local -> startLocalOnly()
                is QuerySource.LocalAndRemote -> startLocalAndRemote()
                is QuerySource.Remote -> startRemoteOnly()
            }
        }

        private fun startLocalOnly() {
            observerJob = startObserver(initialSync = { QuerySync.LocalOnly })
        }

        private fun startLocalAndRemote() {
            observerJob = startObserver(
                initialSync = {
                    val fresh = cachedFor?.let(::freshness)?.isFresh == true
                    remoteDeferred.set(fresh)
                    if (!fresh) prepareRemote()
                    when {
                        !fresh -> QuerySync.Connecting
                        mode is RemoteMode.OneShot -> QuerySync.Complete
                        else -> QuerySync.Cached
                    }
                },
                afterSeed = { seedSync ->
                    when (seedSync) {
                        QuerySync.Complete -> finish()
                        QuerySync.Cached -> scheduleRemoteAfterCache()
                        else -> startRemote()
                    }
                },
            )
        }

        private fun scheduleRemoteAfterCache() {
            val cacheDuration = cachedFor ?: return
            remoteDelayJob = scope.launch {
                while (!stopped.get()) {
                    val status = freshness(cacheDuration)
                    if (status.isFresh) {
                        delay(status.remainingMillis.coerceIn(1, CACHE_RECHECK_INTERVAL_MILLIS))
                        continue
                    }
                    var shouldStart = false
                    stateMutex.withLock {
                        shouldStart = synchronized(refreshLock) {
                            !freshnessUnlocked(cacheDuration).isFresh &&
                                remoteDeferred.compareAndSet(true, false)
                        }
                        if (!stopped.get() && shouldStart) {
                            prepareRemote()
                            emitState(QuerySync.Connecting)
                            startRemote()
                        }
                    }
                    if (shouldStart || stopped.get()) return@launch
                }
            }
        }

        private fun freshness(cacheDuration: Duration): QueryFreshness = synchronized(refreshLock) {
            freshnessUnlocked(cacheDuration)
        }

        private fun freshnessUnlocked(cacheDuration: Duration): QueryFreshness {
            val fingerprint = queryFingerprint
                ?: return QueryFreshness(isFresh = false, remainingMillis = 0)
            return runCatching {
                refreshCache.freshness(fingerprint, cacheDuration, clock.now())
            }.getOrDefault(QueryFreshness(isFresh = false, remainingMillis = 0))
        }

        private fun recordRefresh() {
            if (source !is QuerySource.LocalAndRemote) return
            val fingerprint = queryFingerprint ?: return
            synchronized(refreshLock) {
                runCatching { refreshCache.recordRefresh(fingerprint, clock.now()) }
            }
        }

        private fun startRemoteOnly() {
            scope.launch {
                stateMutex.withLock {
                    prepareRemote()
                    emitState(QuerySync.Connecting)
                    startRemote()
                }
            }
        }

        /**
         * Subscribes to the store change feed before seeding the projection, so a change
         * that races the seed is buffered by the SharedFlow and applied right after it.
         * The projection applies each change in memory; collectors only re-emit when the
         * visible id set actually changes (plus in-place updates of visible replaceables).
         */
        private fun startObserver(
            initialSync: () -> QuerySync,
            afterSeed: ((QuerySync) -> Unit)? = null,
        ): Job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val projection = EventStoreProjection<Event>(store, filters)
                try {
                    store.changes
                        .onSubscription {
                            val seedSync = initialSync()
                            projection.seed()
                            stateMutex.withLock {
                                publishLocked(projection.snapshotItems(), seedSync, force = true)
                            }
                            afterSeed?.invoke(seedSync)
                        }
                        .collect { change ->
                            if (stopped.get()) throw CancellationException("query session stopped")
                            val applied = projection.apply(change)
                            if (!applied && !change.isVisibleInPlaceUpdate()) return@collect
                            val fresh = projection.snapshotItems()
                            stateMutex.withLock { publishLocked(fresh, sync()) }
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    fail(QueryError.UnsupportedLocalProjection("Local store projection failed"))
                }
            }

        private fun StoreChange.isVisibleInPlaceUpdate(): Boolean {
            val event = (this as? StoreChange.Insert)?.event ?: return false
            val address = addressOf(event) ?: return false
            return address in emittedAddresses
        }

        private suspend fun publishLocked(fresh: List<Event>, sync: QuerySync, force: Boolean = false) {
            val ids = fresh.map { it.id }
            if (!force && ids == emittedIds) return
            emittedIds = ids
            emittedAddresses = fresh.mapNotNullTo(HashSet(), Companion::addressOf)
            items = fresh
            emitState(sync)
        }

        private fun startRemote() {
            if (stopped.get() || !remoteStarted.compareAndSet(false, true)) return
            prepareRemote()
            workerJob = scope.launch {
                try {
                    for (message in messages) {
                        val outcome = stateMutex.withLock {
                            if (!stopped.get()) processLocked(message) else IngestOutcome()
                        }
                        outcome.batch?.let { flushBatch(it) }
                        if (outcome.eoseAccepted) {
                            flushPendingAndWait()
                            stateMutex.withLock {
                                if (!stopped.get()) postEoseLocked(message as Inbound.Eose)
                            }
                        }
                    }
                } finally {
                    flushPendingAndWait()
                }
            }
            try {
                remoteSubscribed.set(true)
                client.addConnectionListener(connectionListener)
                // Generation 1 has already been allocated in relayStates before this call.
                client.subscribe(subId, relays.associateWith { filters }, subscriptionListener)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                fail(QueryError.IncompatibleDependency("Unable to start the Quartz subscription"))
                return
            }
            val oneShot = mode as? RemoteMode.OneShot
            if (oneShot != null) {
                val timeout = oneShot.timeout ?: config.oneShotTimeout
                timeoutJob = scope.launch {
                    delay(timeout)
                    timeout(timeout)
                }
            }
        }

        private fun prepareRemote() {
            if (!remotePrepared.compareAndSet(false, true)) return
            relays.forEach { relay ->
                relayStates[relay] = RelayQueryState(1, RelayConnectionState.Connecting, eose = false)
                callbackGenerations[relay] = AtomicLong(1)
                callbackGenerationStarted[relay] = AtomicBoolean(false)
            }
        }

        private fun enqueue(message: Inbound) {
            if (message.relay !in relays || stopped.get() || !acceptingCallbacks.get()) return
            val result = messages.trySend(message)
            if (result.isFailure && !result.isClosed) {
                acceptingCallbacks.set(false)
                fail(QueryError.IngestionSaturated(message.relay, "Remote ingestion queue is full"))
            }
        }

        private suspend fun processLocked(message: Inbound): IngestOutcome {
            val currentGeneration = relayStates[message.relay]?.generation ?: return IngestOutcome()
            if (message is Inbound.Connecting) {
                if (message.generation < currentGeneration) return IngestOutcome()
            } else if (message.generation != currentGeneration) {
                return IngestOutcome()
            }
            return when (message) {
                is Inbound.Started -> {
                    updateRelay(message.relay) { it.copy(connection = RelayConnectionState.Connected, eose = false, lastError = null) }
                    emitState(sync())
                    IngestOutcome()
                }
                is Inbound.Connecting -> {
                    updateRelay(message.relay) {
                        it.copy(generation = message.generation, connection = RelayConnectionState.Connecting, eose = false)
                    }
                    emitState(sync())
                    IngestOutcome()
                }
                is Inbound.Disconnected -> {
                    updateRelay(message.relay) { it.copy(connection = RelayConnectionState.Disconnected, eose = false) }
                    emitState(sync())
                    IngestOutcome()
                }
                is Inbound.CannotConnect -> {
                    updateRelay(message.relay) { it.copy(connection = RelayConnectionState.Disconnected, lastError = message.message) }
                    error = QueryError.RelayFailure(message.relay, message.message)
                    emitState(sync())
                    IngestOutcome()
                }
                is Inbound.Closed -> {
                    updateRelay(message.relay) {
                        it.copy(connection = RelayConnectionState.Closed, lastError = message.message)
                    }
                    fail(QueryError.RelayFailure(message.relay, message.message))
                    IngestOutcome()
                }
                is Inbound.EventReceived -> IngestOutcome(batch = ingestLocked(message.relay, message.event))
                is Inbound.Eose -> {
                    updateRelay(message.relay) { it.copy(eose = true) }
                    if (timeoutRequested.get()) return IngestOutcome()
                    IngestOutcome(batch = drainPendingInserts(), eoseAccepted = true)
                }
            }
        }

        private suspend fun postEoseLocked(message: Inbound.Eose) {
            if (timeoutRequested.get()) return
            error = null
            val allRelaysCaughtUp = relayStates.values.all { it.eose }
            if (allRelaysCaughtUp) {
                if (mode is RemoteMode.OneShot) {
                    if (source is QuerySource.LocalAndRemote) {
                        try {
                            terminalRequeryLocked(QuerySync.Complete)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            fail(QueryError.UnsupportedLocalProjection("Local store projection failed"))
                            return
                        }
                        recordRefresh()
                    } else {
                        emitState(QuerySync.Complete)
                    }
                    finish()
                } else {
                    recordRefresh()
                    if (source is QuerySource.LocalAndRemote) {
                        // EOSE is a sync barrier: the final ingest batch was just flushed,
                        // but the observer coroutine may not have applied it yet. Requery
                        // so the terminal-at-EOSE state cannot lag the store.
                        try {
                            terminalRequeryLocked(sync())
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            fail(QueryError.UnsupportedLocalProjection("Local store projection failed"))
                            return
                        }
                    } else {
                        emitState(sync())
                    }
                }
            } else {
                emitState(sync())
            }
        }

        private suspend fun terminalRequeryLocked(sync: QuerySync) {
            val fresh = withStoreOperation { store.query<Event>(filters) }
            publishLocked(fresh, sync, force = true)
        }

        private suspend fun ingestLocked(relay: NormalizedRelayUrl, event: Event): List<Event>? {
            val eventId = validatedId(event.id)
            val valid = try {
                eventVerifier(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            }
            if (!valid) {
                error = QueryError.VerificationRejected(relay, eventId, "Relay event failed verification")
                updateRelay(relay) { it.copy(lastError = error?.message) }
                emitState(sync())
                return null
            }
            if (!filters.any { it.match(event) }) {
                error = QueryError.ProtocolViolation(relay, eventId, "Relay event does not match this query")
                updateRelay(relay) { it.copy(lastError = error?.message) }
                emitState(sync())
                return null
            }
            if (event.isExpired()) return null

            if (source is QuerySource.Remote && seenRemoteIds.add(event.id)) {
                items = items + event
                error = null
                emitState(sync())
            }
            if (event.kind.isEphemeral()) return null

            return batchMutex.withLock {
                pendingInserts += event
                if (pendingInserts.size >= config.ingestBatchSize) {
                    drainPendingInsertsLocked()
                } else {
                    scheduleFlush()
                    null
                }
            }
        }

        private fun scheduleFlush() {
            if (stopped.get() || flushJob?.isActive == true) return
            flushJob = scope.launch {
                delay(config.ingestFlushInterval)
                val batch = drainPendingInserts()
                if (batch != null) flushBatch(batch)
            }
        }

        private fun drainPendingInsertsLocked(): List<Event>? {
            if (pendingInserts.isEmpty()) return null
            flushJob?.cancel()
            return pendingInserts.toList().also { pendingInserts.clear() }
        }

        private suspend fun drainPendingInserts(): List<Event>? =
            batchMutex.withLock { drainPendingInsertsLocked() }

        private suspend fun flushBatch(batch: List<Event>) {
            val outcomes = try {
                flushIoMutex.withLock {
                    withStoreOperation { store.batchInsert(batch) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            val rejected = when {
                outcomes == null -> batch
                else -> batch.filterIndexed { index, _ ->
                    outcomes[index] is IEventStore.InsertOutcome.Rejected
                }
            }
            var genuinelyMissing: Event? = null
            for (event in rejected) {
                // Quartz may reject after a concurrent duplicate commit. A direct exact-ID
                // filter distinguishes that expected outcome from a genuine failed insert.
                val existing = runCatching {
                    withStoreOperation { store.query<Event>(Filter(ids = listOf(event.id))) }
                }.getOrDefault(emptyList())
                if (existing.none { it.id == event.id } && !event.isExpired()) {
                    genuinelyMissing = event
                    break
                }
            }
            if (genuinelyMissing != null) {
                fail(QueryError.PersistenceFailure(validatedId(genuinelyMissing.id), "Unable to persist relay event"))
            } else {
                stateMutex.withLock { if (!stopped.get()) error = null }
            }
        }

        private suspend fun flushPendingAndWait() {
            val batch = drainPendingInserts()
            if (batch != null) flushBatch(batch)
            flushIoMutex.withLock { }
        }

        private fun sync(): QuerySync = when {
            remoteDeferred.get() -> QuerySync.Cached
            relays.isEmpty() -> QuerySync.LocalOnly
            relayStates.values.any { it.connection != RelayConnectionState.Connected } -> QuerySync.Connecting
            relayStates.values.any { !it.eose } -> QuerySync.CatchingUp
            mode is RemoteMode.Stream -> QuerySync.Live
            else -> QuerySync.Complete
        }

        private suspend fun emitState(sync: QuerySync) {
            emitMutex.withLock {
                if (!stopped.get()) {
                    val visibleRelays = if (remoteDeferred.get()) emptyMap() else relayStates.toMap()
                    val state = QueryState(items, sync, visibleRelays, error)
                    lastState = state
                    producer.send(state)
                }
            }
        }

        private fun updateRelay(relay: NormalizedRelayUrl, transform: (RelayQueryState) -> RelayQueryState) {
            relayStates[relay]?.let { relayStates[relay] = transform(it) }
        }

        private fun generationOf(relay: NormalizedRelayUrl): Long = callbackGenerations[relay]?.get() ?: 0

        private fun timeout(duration: Duration) {
            if (stopped.get()) return
            timeoutRequested.set(true)
            if (!acceptingCallbacks.compareAndSet(true, false)) return
            unsubscribeRemote()
            messages.close()
            scope.launch {
                workerJob?.join()
                stateMutex.withLock {
                    if (!stopped.get()) {
                        error = QueryError.Timeout(duration, "Remote query timed out")
                        emitState(QuerySync.TimedOut)
                        finish()
                    }
                }
            }
        }

        private fun fail(queryError: QueryError) {
            if (!stopped.compareAndSet(false, true)) return
            acceptingCallbacks.set(false)
            cleanup()
            scope.launch {
                stateMutex.withLock {
                    error = queryError
                    val state = QueryState(items, QuerySync.Failed, relayStates.toMap(), error)
                    lastState = state
                    producer.send(state)
                    producer.close()
                }
            }
        }

        private fun finish() {
            if (!stopped.compareAndSet(false, true)) return
            producer.close()
            cleanup()
        }

        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            acceptingCallbacks.set(false)
            cleanup()
        }

        fun closeFromFacade() {
            stopped.set(true)
            acceptingCallbacks.set(false)
            cleanup()
            val terminal = lastState.copy(
                sync = QuerySync.Failed,
                error = QueryError.Lifecycle("PurpleQuartz is closed"),
            )
            lastState = terminal
            producer.trySend(terminal)
            producer.close()
        }

        private fun cleanup() {
            acceptingCallbacks.set(false)
            timeoutJob?.cancel()
            remoteDelayJob?.cancel()
            observerJob?.cancel()
            flushJob?.cancel()
            messages.close()
            workerJob?.cancel()
            unsubscribeRemote()
        }

        private fun unsubscribeRemote() {
            if (!remoteSubscribed.compareAndSet(true, false)) return
            runCatching { client.unsubscribe(subId) }
            runCatching { client.removeConnectionListener(connectionListener) }
        }
    }

    companion object {
        private val openDatabases = mutableSetOf<String>()

        fun create(
            context: Context,
            websocketBuilder: WebsocketBuilder,
            parentScope: CoroutineScope,
            config: PurpleQuartzConfig = PurpleQuartzConfig(),
        ): PurpleQuartz {
            config.validate()
            val parentJob = requireActiveParentJob(parentScope)
            val appContext = context.applicationContext
            val databaseFile = appContext.getDatabasePath(config.databaseName).absoluteFile
            val databaseExisted = databaseFile.exists()
            val path = databaseFile.canonicalPath
            synchronized(openDatabases) {
                check(path !in openDatabases) { "A PurpleQuartz instance already owns this database" }
                openDatabases += path
            }
            val job = SupervisorJob(parentJob)
            val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.IO)
            var store: ObservableEventStore? = null
            var client: INostrClient? = null
            try {
                store = ObservableEventStore(EventStore(dbName = path, relay = null))
                val refreshCache = SharedPreferencesQueryRefreshCache.create(
                    context = appContext,
                    databasePath = path,
                    databaseExisted = databaseExisted,
                )
                client = NostrClient(websocketBuilder, scope)
                return PurpleQuartz(
                    store,
                    client,
                    scope,
                    job,
                    path,
                    config,
                    DEFAULT_EVENT_VERIFIER,
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager,
                    refreshCache,
                    SYSTEM_CLOCK,
                )
            } catch (failure: Throwable) {
                runCatching { client?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                job.cancel()
                runCatching { runBlocking(Dispatchers.IO) { job.join() } }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { store?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                synchronized(openDatabases) { openDatabases.remove(path) }
                throw failure
            }
        }

        /**
         * Test-only construction seam. It is internal so consumers cannot replace the
         * canonical Quartz-backed store or client in production.
         */
        internal fun createForTesting(
            eventStore: IEventStore,
            client: INostrClient,
            parentScope: CoroutineScope,
            config: PurpleQuartzConfig = PurpleQuartzConfig(),
            databasePath: String = "test-${System.nanoTime()}",
            eventVerifier: (Event) -> Boolean = DEFAULT_EVENT_VERIFIER,
            refreshCache: QueryRefreshCache = InMemoryQueryRefreshCache(),
            clock: EpochMillisClock = SYSTEM_CLOCK,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): PurpleQuartz {
            config.validate()
            val job = SupervisorJob(requireActiveParentJob(parentScope))
            val scope = CoroutineScope(parentScope.coroutineContext + job + dispatcher)
            return PurpleQuartz(
                store = ObservableEventStore(eventStore),
                client = client,
                scope = scope,
                job = job,
                databasePath = databasePath,
                config = config,
                eventVerifier = eventVerifier,
                connectivityManager = null,
                refreshCache = refreshCache,
                clock = clock,
            )
        }

        private fun copyFilter(filter: Filter): Filter = Filter(
            ids = filter.ids?.toList(),
            authors = filter.authors?.toList(),
            kinds = filter.kinds?.toList(),
            tags = filter.tags?.mapValues { it.value.toList() },
            tagsAll = filter.tagsAll?.mapValues { it.value.toList() },
            since = filter.since,
            until = filter.until,
            limit = filter.limit,
            search = filter.search,
        )

        private fun validatedId(value: String?): String? =
            value?.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }

        private fun requireActiveParentJob(parentScope: CoroutineScope): Job =
            requireNotNull(parentScope.coroutineContext[Job]) {
                "parentScope must contain an active Job"
            }.also { parentJob ->
                require(parentJob.isActive) { "parentScope must be active" }
            }

        private fun addressOf(event: Event): Address? = when {
            event is AddressableEvent -> event.address()
            event.kind.isReplaceable() -> Address(event.kind, event.pubKey, "")
            else -> null
        }

        private fun <T : Event> EventStoreProjection<T>.snapshotItems(): List<T> =
            snapshot().items.map { it.value }

        private val DEFAULT_EVENT_VERIFIER: (Event) -> Boolean = { event ->
            event.verifyId() && event.verifySignature()
        }

        private val SYSTEM_CLOCK = EpochMillisClock(System::currentTimeMillis)
        private const val CACHE_RECHECK_INTERVAL_MILLIS = 60_000L
    }
}
