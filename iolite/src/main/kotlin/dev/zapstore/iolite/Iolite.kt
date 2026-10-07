package dev.zapstore.iolite

import android.content.Context
import androidx.sqlite.SQLiteConnection
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The local-first Nostr client. Every event, whether it arrives in a catalog bundle or over a relay
 * socket, is verified, turned into rows by [IoliteStore], and committed; commits notify the observers
 * reading those tables. UI code reads records and never touches event JSON.
 */
class Iolite private constructor(
    private val store: IoliteStore,
    private val importer: CatalogImporter,
    private val scope: CoroutineScope,
    private val job: Job,
    private val databasePath: String,
    private val http: HttpTransport?,
    private val pool: RelayPool?,
    private val config: IoliteConfig,
    private val device: DeviceProfile,
    private val deviceSigner: LocalSigner?,
    private val writeRelays: Set<RelayUrl>,
    private val nowMillis: () -> Long,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val ingestContext = IngestContext(device, deviceSigner)
    private val outboxAcks = ConcurrentHashMap<String, MutableSet<RelayUrl>>()
    private val outboxLock = Mutex()
    private val pendingIngest = ArrayList<Event>()

    // --- catalog listings (local only; catalogs are synced, never queried over sockets) -----------

    fun observeApps(filter: AppFilter = AppFilter()): Flow<List<AppRecord>> = observe(APP_TABLES) { it.apps(filter) }

    fun observeApp(appId: String): Flow<AppRecord?> = observe(APP_TABLES) { it.app(appId) }

    fun apps(filter: AppFilter = AppFilter()): List<AppRecord> = store.apps(filter)

    fun app(appId: String): AppRecord? = store.app(appId)

    fun catalogs(): List<CatalogRecord> = store.selectedCatalogs()

    fun allCatalogs(): List<CatalogRecord> = store.allCatalogs()

    fun defaultCatalog(): CatalogRecord = selectedCatalog() ?: error("default catalog is missing")

    fun selectedCatalog(): CatalogRecord? = store.selectedCatalogs().firstOrNull()

    fun observeCatalogs(): Flow<List<CatalogRecord>> = observe(setOf(Table.Catalogs)) { it.selectedCatalogs() }

    fun importCatalogUpdate(catalogId: Long, bundle: ByteArray, endpoint: RelayUrl? = null): CatalogImportResult {
        checkOpen()
        return importer.importBundle(catalogId, bundle, endpoint)
    }

    /**
     * Imports [bundled] when the catalog is missing or still at epoch 0, then fetches the next epoch.
     * With no bundle and no stored catalog, fetches `GET /bundle?from=0` from [endpoint] and imports
     * that body. Both bodies go through [importCatalogUpdate]. [CatalogSyncResult.imported] is null on
     * HTTP 304 when nothing was imported. [endpoint] is stored when the catalog has none. [useOnion]
     * selects a stored Tor endpoint when one is present.
     */
    fun syncCatalog(
        catalogId: Long = defaultCatalog().id,
        transport: HttpTransport? = http,
        useOnion: Boolean = false,
        bundled: ByteArray = ByteArray(0),
        endpoint: RelayUrl? = null,
    ): CatalogSyncResult {
        checkOpen()
        val httpClient = transport ?: throw IllegalStateException("HTTP transport is required for catalog sync")
        val existing = store.catalog(catalogId)
        val seeded = if (bundled.isNotEmpty() && (existing == null || existing.epoch == 0L)) {
            importCatalogUpdate(catalogId, bundled, endpoint)
        } else {
            null
        }
        val catalog = store.catalog(catalogId)
        if (catalog == null || catalog.relays.isEmpty()) {
            val relay = endpoint ?: throw CatalogImportException("catalog relay is missing")
            return applyCatalogResponse(
                catalogId,
                httpClient.get(relay.bundleUrl(0), emptyMap()),
                seeded = null,
                seededBytes = 0,
                endpoint = relay,
            )
        }
        return applyCatalogResponse(
            catalogId,
            httpClient.get(catalog.syncRelay(useOnion).bundleUrl(catalog.epoch), emptyMap()),
            seeded = seeded,
            seededBytes = bundled.size.toLong(),
            endpoint = null,
        )
    }

    private fun applyCatalogResponse(
        catalogId: Long,
        response: HttpResponse,
        seeded: CatalogImportResult?,
        seededBytes: Long,
        endpoint: RelayUrl?,
    ): CatalogSyncResult {
        val body = response.body
        val bytes = (body?.size ?: 0).toLong()
        return when (response.status) {
            304 -> CatalogSyncResult(imported = seeded, bytes = if (seeded != null) seededBytes else bytes)
            200 -> CatalogSyncResult(
                imported = importCatalogUpdate(
                    catalogId,
                    body ?: throw CatalogImportException("empty catalog update"),
                    endpoint,
                ),
                bytes = bytes,
            )
            400 -> throw CatalogImportException(response.headers["X-Reason"] ?: "catalog protocol error")
            else -> throw CatalogImportException("catalog update failed with HTTP ${response.status}")
        }
    }

    /** Avatar WebP from the catalog bundle, keyed by hex pubkey. Absent until that profile is sealed. */
    fun avatarFile(pubkey: String): File = store.avatarFile(pubkey)

    fun wipeLocalData() {
        checkOpen()
        store.wipe()
        val parent = File(databasePath).parentFile
        listOf("icons", "avatars").forEach { name ->
            val dir = File(parent, name)
            dir.deleteRecursively()
            dir.mkdirs()
        }
    }

    // --- typed queries -----------------------------------------------------------------------------

    /**
     * Runs [query] under [options]. The flow re-emits after every commit touching the query's tables and
     * completes only when the collector cancels, so scope it to the screen that shows it.
     */
    fun <T> query(query: Query<T>, options: QueryOptions = QueryOptions.local()): Flow<QueryState<T>> = callbackFlow {
        if (closed.get()) {
            close()
            return@callbackFlow
        }
        when (options.sourceMode) {
            SourceMode.Local -> {
                emitOnChange(query, QueryPhase.LocalOnly)
                awaitClose()
            }
            SourceMode.LocalAndRemote, SourceMode.Remote -> RemoteQuery(query, options, this).run()
        }
    }.buffer(Channel.CONFLATED).flowOn(Dispatchers.IO)

    /** Relay path for events obtained outside the pool (push, share sheet, tests). Returns rows changed. */
    fun ingest(events: List<Event>): Int {
        checkOpen()
        return store.ingest(events, ingestContext)
    }

    // --- publishing --------------------------------------------------------------------------------

    suspend fun publish(
        kind: Int,
        tags: List<List<String>>,
        content: String,
        signer: Signer,
        createdAt: Long = nowMillis() / 1_000,
        relays: Set<RelayUrl> = writeRelays,
    ): Event {
        checkOpen()
        val event = signer.sign(createdAt, kind, tags, content)
        store.commitPublish(event, ingestContext)
        publishOutbox(event, relays)
        return event
    }

    suspend fun rehydrateCatalogRelayList(): Event {
        val signer = deviceSigner ?: error("device signer is required to rehydrate kind 10067")
        val (tags, content) = store.catalogRelayListTemplate(signer)
        return publish(Kinds.CatalogRelayList, tags, content, signer)
    }

    suspend fun rehydratePreferences(identifier: String, signer: Signer): Event {
        val (tags, content) = store.preferenceTemplate(identifier)
        return publish(Kinds.Preferences, tags, content, signer)
    }

    suspend fun rehydrateStack(identifier: String, signer: Signer): Event {
        val (tags, content) = store.stackTemplate(signer.publicKey, identifier, device, deviceSigner)
        return publish(Kinds.AppStack, tags, content, signer)
    }

    fun pendingOutbox(): List<Event> = store.pendingOutbox()

    // --- connectivity ------------------------------------------------------------------------------

    fun refreshConnections() {
        pool?.refreshConnections()
        scope.launch { flushOutbox() }
    }

    fun setRelayTrafficEnabled(enabled: Boolean) {
        pool?.setTrafficEnabled(enabled)
        if (enabled) scope.launch { flushOutbox() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        flushIngest()
        pool?.close()
        job.cancel()
        store.close()
        synchronized(openDatabases) { openDatabases.remove(databasePath) }
    }

    // --- internals ---------------------------------------------------------------------------------

    private fun checkOpen() = check(!closed.get()) { "Iolite is closed" }

    private fun <T> observe(tables: Set<Table>, read: (IoliteStore) -> T): Flow<T> =
        store.changes(tables).map { read(store) }.flowOn(Dispatchers.IO)

    private fun <T> ProducerScope<QueryState<T>>.emitOnChange(query: Query<T>, phase: QueryPhase) {
        launch {
            store.changes(query.tables).collect {
                if (!closed.get()) trySend(QueryState(query.read(store, deviceSigner), phase))
            }
        }
    }

    /** One remote-enabled collection: relay lifecycle, phase transitions, and re-reads on commit. */
    private inner class RemoteQuery<T>(
        private val query: Query<T>,
        private val options: QueryOptions,
        private val producer: ProducerScope<QueryState<T>>,
    ) {
        private val lock = Any()
        @Volatile private var phase: QueryPhase? = null
        @Volatile private var error: QueryError? = null
        private val relayStates = ConcurrentHashMap<RelayUrl, RelayQueryState>()
        private var unsubscribe: (() -> Unit)? = null
        private var graceJob: Job? = null
        private var timeoutJob: Job? = null

        suspend fun run() {
            val fingerprint = QueryFingerprint.create(query.filters, options.relays)
            val cachedFor = options.cachedFor
            if (cachedFor != null) {
                val last = store.lastRefresh(fingerprint)
                if (last != null && nowMillis() - last < cachedFor.inWholeMilliseconds) {
                    producer.emitOnChange(query, QueryPhase.Cached)
                    producer.awaitClose()
                    return
                }
            }

            transition(QueryPhase.Connecting) { if (options.sourceMode == SourceMode.Remote) query.empty else read() }
            producer.launch {
                // The first emission is the subscription itself; the snapshot above already covered it.
                store.changes(query.tables).drop(1).collect { emitCurrent() }
            }

            val relayPool = pool
            if (relayPool == null) {
                error = QueryError.IncompatibleDependency("WebSocket transport is required")
                transition(QueryPhase.Failed)
                producer.awaitClose()
                return
            }

            val relays = if (options.useAuthorRelays) options.relays + resolveReadRelays(query.authors, options.relays) else options.relays
            val timeout = (options.remoteMode as? RemoteMode.OneShot)?.timeout ?: config.oneShotTimeout
            timeoutJob = producer.launch {
                delay(timeout)
                if (phase != QueryPhase.Connecting && phase != QueryPhase.CatchingUp) return@launch
                error = QueryError.Timeout(timeout, "one-shot query timed out after $timeout")
                transition(QueryPhase.TimedOut)
                stopRelayWork()
            }
            unsubscribe = relayPool.subscribe(
                id = "q" + Hex.encode(Crypto.randomBytes(4)),
                filters = query.filters,
                relays = relays,
                onEvent = { relay, event ->
                    if (!event.verify()) {
                        error = QueryError.VerificationRejected(relay, event.id, "event failed verification")
                        return@subscribe
                    }
                    enqueueIngest(event)
                    if (phase == QueryPhase.Connecting) transition(QueryPhase.CatchingUp)
                },
                onEose = { _ ->
                    synchronized(lock) {
                        if (graceJob != null) return@subscribe
                        graceJob = producer.launch {
                            delay(config.eoseGrace)
                            flushIngest()
                            when (options.remoteMode) {
                                is RemoteMode.OneShot -> {
                                    store.recordRefresh(fingerprint, nowMillis())
                                    transition(QueryPhase.Complete)
                                    stopRelayWork()
                                }
                                RemoteMode.Stream -> {
                                    timeoutJob?.cancel()
                                    transition(QueryPhase.Live)
                                }
                                null -> Unit
                            }
                        }
                    }
                },
                onState = { relay, state ->
                    relayStates[relay] = state
                    if (state.lastError != null && phase == QueryPhase.Connecting) {
                        error = QueryError.RelayFailure(relay, state.lastError)
                    }
                    emitCurrent()
                },
            )
            producer.awaitClose { stopRelayWork() }
        }

        private fun read(): T = query.read(store, deviceSigner)

        private fun emitCurrent() {
            if (closed.get()) return
            val current = phase ?: return
            producer.trySend(QueryState(read(), current, relayStates.toMap(), error))
        }

        private fun transition(next: QueryPhase, items: () -> T = ::read) {
            synchronized(lock) {
                if (!isLegalPhaseTransition(phase, next)) return
                phase = next
            }
            if (closed.get()) return
            producer.trySend(QueryState(items(), next, relayStates.toMap(), error))
        }

        private fun stopRelayWork() {
            timeoutJob?.cancel()
            graceJob?.cancel()
            unsubscribe?.invoke()
            unsubscribe = null
        }
    }

    /** NIP-65 read relays of [authors], from SQLite when fresh, else asked of [bootstrap]. */
    private suspend fun resolveReadRelays(authors: List<String>, bootstrap: Set<RelayUrl>): Set<RelayUrl> {
        if (authors.isEmpty()) return emptySet()
        return coroutineScope {
            authors.distinct().map { author ->
                async {
                    runCatching {
                        query(
                            Query.relayList(author),
                            QueryOptions.localAndRemote(
                                relays = bootstrap,
                                remoteMode = RemoteMode.OneShot(config.relayListTimeout),
                                cachedFor = config.relayListCacheFor,
                            ),
                        ).first { it.isTerminal }.items.orEmpty()
                    }.getOrDefault(emptySet())
                }
            }.awaitAll().flatten().toSet()
        }
    }

    private fun enqueueIngest(event: Event) {
        val flushNow = synchronized(pendingIngest) {
            pendingIngest += event
            pendingIngest.size >= config.ingestBatchSize
        }
        if (flushNow) flushIngest()
    }

    private fun flushIngest() {
        val batch = synchronized(pendingIngest) {
            if (pendingIngest.isEmpty()) return
            pendingIngest.toList().also { pendingIngest.clear() }
        }
        runCatching { store.ingest(batch, ingestContext) }
    }

    private fun start() {
        scope.launch { flushOutbox() }
        scope.launch {
            while (isActive) {
                delay(config.ingestFlushInterval)
                flushIngest()
            }
        }
        scope.launch {
            while (isActive) {
                delay(config.expirationSweepInterval)
                pruneExpired()
            }
        }
        scope.launch {
            while (isActive) {
                val now = nowMillis() / 1_000
                val next = store.nextProofExpiry(now)
                val sweep = config.expirationSweepInterval.inWholeMilliseconds
                val waitMs = if (next == null) sweep else minOf(((next - now) * 1_000L + 50L).coerceAtLeast(0L), sweep)
                delay(waitMs)
                store.recomputeProofs(nowMillis() / 1_000)
            }
        }
    }

    private fun pruneExpired() {
        val now = nowMillis()
        config.pruneRules.forEach { (kind, maxAge) ->
            store.pruneOlderThan(kind, (now - maxAge.inWholeMilliseconds) / 1_000L)
        }
    }

    private suspend fun flushOutbox() {
        outboxLock.withLock {
            store.pendingOutbox().forEach { publishOutbox(it, writeRelays) }
        }
    }

    private fun publishOutbox(event: Event, relays: Set<RelayUrl>) {
        val dest = relays.ifEmpty { writeRelays }
        if (dest.isEmpty() || pool == null) return
        val needed = okThreshold(dest.size)
        pool.publish(event, dest) { relay, accepted, _ ->
            if (!accepted) return@publish
            val acks = outboxAcks.getOrPut(event.id) { mutableSetOf() }
            synchronized(acks) {
                acks += relay
                if (acks.size >= needed) {
                    store.deleteOutbox(event.id)
                    outboxAcks.remove(event.id)
                }
            }
        }
    }

    companion object {
        private val APP_TABLES = setOf(Table.Apps, Table.Proofs, Table.Catalogs)
        private val openDatabases = mutableSetOf<String>()

        fun create(
            context: Context,
            parentScope: CoroutineScope,
            config: IoliteConfig = IoliteConfig(),
            http: HttpTransport? = null,
            webSocket: WebSocketFactory? = null,
            signer: Signer? = null,
            deviceSigner: LocalSigner? = signer as? LocalSigner,
            writeRelays: Set<RelayUrl> = emptySet(),
            device: DeviceProfile = DeviceProfile(abis = listOf("arm64-v8a"), sdk = 29),
        ): Iolite = open(
            databasePath = context.applicationContext.getDatabasePath(config.databaseName).canonicalPath,
            parentScope = parentScope,
            config = config,
            device = device,
            http = http,
            webSocket = webSocket,
            signer = signer,
            deviceSigner = deviceSigner,
            writeRelays = writeRelays,
            nowMillis = { System.currentTimeMillis() },
            openConnection = ::openBundled,
        )

        fun createForTesting(
            databasePath: String,
            parentScope: CoroutineScope,
            config: IoliteConfig = IoliteConfig(),
            device: DeviceProfile = DeviceProfile(abis = listOf("arm64-v8a"), sdk = 34),
            http: HttpTransport? = null,
            webSocket: WebSocketFactory? = null,
            signer: Signer? = null,
            deviceSigner: LocalSigner? = signer as? LocalSigner,
            writeRelays: Set<RelayUrl> = emptySet(),
            nowMillis: () -> Long = { System.currentTimeMillis() },
        ): Iolite = open(
            databasePath, parentScope, config, device, http, webSocket, signer, deviceSigner, writeRelays, nowMillis,
            ::openBundled,
        )

        internal fun createForTesting(
            databasePath: String,
            parentScope: CoroutineScope,
            config: IoliteConfig = IoliteConfig(),
            device: DeviceProfile = DeviceProfile(abis = listOf("arm64-v8a"), sdk = 34),
            http: HttpTransport? = null,
            webSocket: WebSocketFactory? = null,
            signer: Signer? = null,
            deviceSigner: LocalSigner? = signer as? LocalSigner,
            writeRelays: Set<RelayUrl> = emptySet(),
            nowMillis: () -> Long = { System.currentTimeMillis() },
            openConnection: (String) -> SQLiteConnection,
        ): Iolite = open(
            databasePath, parentScope, config, device, http, webSocket, signer, deviceSigner, writeRelays, nowMillis,
            openConnection,
        )

        private fun open(
            databasePath: String,
            parentScope: CoroutineScope,
            config: IoliteConfig,
            device: DeviceProfile,
            http: HttpTransport?,
            webSocket: WebSocketFactory?,
            signer: Signer?,
            deviceSigner: LocalSigner?,
            writeRelays: Set<RelayUrl>,
            nowMillis: () -> Long,
            openConnection: (String) -> SQLiteConnection,
        ): Iolite {
            config.validate()
            val parentJob = requireNotNull(parentScope.coroutineContext[Job]) { "parentScope must contain an active Job" }
            require(parentJob.isActive) { "parentScope must be active" }
            synchronized(openDatabases) {
                check(databasePath !in openDatabases) { "An Iolite instance already owns this database" }
                openDatabases += databasePath
            }
            try {
                val job = SupervisorJob(parentJob)
                val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.IO)
                val nowSeconds = { nowMillis() / 1_000 }
                val store = IoliteStore(databasePath, openConnection = openConnection)
                try {
                    store.recomputeProofs(nowSeconds())
                } catch (failure: Throwable) {
                    store.close()
                    throw failure
                }
                val importer = CatalogImporter(store, File(File(databasePath).parentFile, "icons"), config, device, nowSeconds)
                return Iolite(
                    store = store,
                    importer = importer,
                    scope = scope,
                    job = job,
                    databasePath = databasePath,
                    http = http,
                    pool = webSocket?.let { RelayPool(it, scope, signer, nowSeconds) },
                    config = config,
                    device = device,
                    deviceSigner = deviceSigner,
                    writeRelays = writeRelays,
                    nowMillis = nowMillis,
                ).also { it.start() }
            } catch (failure: Throwable) {
                synchronized(openDatabases) { openDatabases.remove(databasePath) }
                throw failure
            }
        }
    }
}
