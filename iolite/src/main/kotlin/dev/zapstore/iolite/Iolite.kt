package dev.zapstore.iolite

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
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
import kotlinx.coroutines.withContext

/**
 * The local-first Nostr client. Every event, whether it arrives in a catalog bundle or over a relay
 * socket, is verified, turned into rows by [IoliteStore], and committed; commits notify the observers
 * reading those tables. UI code reads records and never touches event JSON.
 */
class Iolite private constructor(
    internal val store: IoliteStore,
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

    /** The proof behind [AppRecord.proofPubkey], for the NIP-C1 check against the downloaded APK. */
    fun proof(app: AppRecord): CertificateProof? {
        val owner = app.proofPubkey ?: return null
        val hash = app.certificateHash ?: return null
        return store.proof(app.catalogId, hash, owner)
    }

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
     * With no bundle and no stored relays, fetches `GET /v1/bundle?from=0` from [endpoint] and imports
     * that body. Both bodies go through [importCatalogUpdate]. [CatalogSyncResult.imported] is null on
     * HTTP 304 when nothing was imported. A catalog-key kind 10067 in a bundle replaces the stored
     * relays. [endpoint] is stored only when that event is absent and the catalog has no relays.
     * [useOnion] selects a stored Tor endpoint when one is present.
     * [client] is the Zapstore version sent as the `v` query. Empty omits it.
     */
    fun syncCatalog(
        catalogId: Long = defaultCatalog().id,
        transport: HttpTransport? = http,
        useOnion: Boolean = false,
        bundled: ByteArray = ByteArray(0),
        endpoint: RelayUrl? = null,
        client: String = "",
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
                httpClient.get(relay.bundleUrl(0, client), emptyMap()),
                seeded = null,
                seededBytes = 0,
                endpoint = relay,
            )
        }
        val relay = catalog.syncRelay(useOnion)
        val response = httpClient.get(relay.bundleUrl(catalog.epoch, client), emptyMap())
        return applyCatalogResponse(catalogId, response, seeded = seeded, seededBytes = bundled.size.toLong(), endpoint = null)
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
            400 -> throw CatalogImportException(
                response.headers["X-Reason"]
                    ?: body?.toString(Charsets.UTF_8)?.trim()?.take(200)?.ifEmpty { null }
                    ?: "catalog protocol error",
            )
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
        val createdAt = afterStored(kind, signer.publicKey, tags, createdAt)
        val work = Pow.required(kind, content)
        val template = if (work > 0 && signer.publicKey != deviceSigner?.publicKey) {
            withContext(Dispatchers.Default) { Pow.mine(signer.publicKey, createdAt, kind, tags, content, work) }
        } else {
            tags
        }
        val event = signer.sign(createdAt, kind, template, content)
        store.commitPublish(event, ingestContext)
        scope.launch { outboxLock.withLock { send(event, relays) } }
        return event
    }

    /**
     * Sends a finished event without storing it, such as a gift wrap. True once one relay in
     * [relays] accepts it; false when every relay refused or [timeout] passed.
     */
    suspend fun sendUnstored(event: Event, relays: Set<RelayUrl>, timeout: Duration = config.oneShotTimeout): Boolean {
        checkOpen()
        val relayPool = pool ?: return false
        if (relays.isEmpty()) return false
        val result = CompletableDeferred<Boolean>()
        val refused = ConcurrentHashMap.newKeySet<RelayUrl>()
        relayPool.publish(event, relays) { relay, accepted, _ ->
            if (accepted) result.complete(true)
            else if (refused.add(relay) && refused.size == relays.size) result.complete(false)
        }
        return withTimeoutOrNull(timeout) { result.await() } ?: false
    }

    /**
     * A replacement must be newer than what it replaces: on a same-second tie the lower id wins,
     * so two quick edits could keep the older one.
     */
    private fun afterStored(kind: Int, pubkey: String, tags: List<List<String>>, createdAt: Long): Long {
        val identifier = tags.firstOrNull { it.size > 1 && it[0] == "d" }?.get(1) ?: return createdAt
        val stored = store.storedCreatedAt(kind, pubkey, identifier) ?: return createdAt
        return maxOf(createdAt, stored + 1)
    }

    /**
     * Events straight from relays, not stored: for events the local tables do not keep, such as an
     * account-signed `zapstore-device-key-backup`. Returns once every relay sent EOSE, or at [timeout].
     */
    suspend fun fetch(filters: List<Filter>, relays: Set<RelayUrl>, timeout: Duration = config.oneShotTimeout): List<Event> {
        checkOpen()
        val relayPool = pool ?: return emptyList()
        if (relays.isEmpty()) return emptyList()
        val events = ConcurrentHashMap<String, Event>()
        val finished = ConcurrentHashMap.newKeySet<RelayUrl>()
        val done = CompletableDeferred<Unit>()
        val unsubscribe = relayPool.subscribe(
            id = "f" + Hex.encode(Crypto.randomBytes(4)),
            filters = filters,
            relays = relays,
            onEvent = { _, event -> if (event.verify()) events[event.id] = event },
            onEose = { relay -> if (finished.add(relay) && finished.size >= relays.size) done.complete(Unit) },
            onState = { relay, state ->
                if (state.lastError != null && finished.add(relay) && finished.size >= relays.size) done.complete(Unit)
            },
        )
        try {
            withTimeoutOrNull(timeout) { done.await() }
        } finally {
            unsubscribe()
        }
        return events.values.sortedByDescending { it.createdAt }
    }

    /**
     * One NIP-47 request: sends [method] with [params] to the wallet and returns the `result`.
     * Uses NIP-44 when the wallet's info event offers it, else NIP-04. Nothing is stored.
     */
    suspend fun walletRequest(
        connection: NwcConnection,
        method: String,
        params: JSONObject,
        timeout: Duration = config.walletTimeout,
    ): JSONObject {
        checkOpen()
        val relayPool = pool ?: throw NwcException("OFFLINE", "relays are off")
        val secret = Hex.decode(connection.secret)
        val wallet = Hex.decode(connection.walletPubkey)
        val info = fetch(
            listOf(Filter(authors = listOf(connection.walletPubkey), kinds = listOf(Kinds.WalletInfo), limit = 1)),
            connection.relays,
            timeout = minOf(timeout, 5.seconds),
        )
        val nip44 = info.firstOrNull()?.tags?.any { it.firstOrNull() == "encryption" && "nip44_v2" in it.getOrNull(1).orEmpty() } == true
        val body = JSONObject().put("method", method).put("params", params).toString()
        val conversation = if (nip44) Nip44.conversationKey(secret, wallet) else null
        val content = conversation?.let { Nip44.encrypt(body, it) } ?: Nip04.encrypt(body, secret, wallet)
        val tags = buildList {
            add(listOf("p", connection.walletPubkey))
            if (nip44) add(listOf("encryption", "nip44_v2"))
        }
        val request = Event.sign(secret, System.currentTimeMillis() / 1_000, Kinds.WalletRequest, tags, content)
        val reply = CompletableDeferred<Event>()
        val sent = AtomicBoolean(false)
        val unsubscribe = relayPool.subscribe(
            id = "w" + Hex.encode(Crypto.randomBytes(4)),
            filters = listOf(
                Filter(authors = listOf(connection.walletPubkey), kinds = listOf(Kinds.WalletResponse), tags = mapOf("e" to listOf(request.id))),
            ),
            relays = connection.relays,
            onEvent = { _, event -> if (event.verify()) reply.complete(event) },
            onEose = { if (sent.compareAndSet(false, true)) relayPool.publish(request, connection.relays) { _, _, _ -> } },
            onState = { _, _ -> },
        )
        val event = try {
            withTimeoutOrNull(timeout) { reply.await() } ?: throw NwcException("TIMEOUT", "the wallet did not answer")
        } finally {
            unsubscribe()
        }
        val plain = conversation?.let { Nip44.decrypt(event.content, it) } ?: Nip04.decrypt(event.content, secret, wallet)
        val json = JSONObject(plain)
        json.optJSONObject("error")?.let { throw NwcException(it.optString("code", "OTHER"), it.optString("message")) }
        return json.optJSONObject("result") ?: JSONObject()
    }

    /** Plaintext of a kind 30078 the device key signed, or null. */
    fun observeDevicePreference(identifier: String): Flow<String?> =
        observe(setOf(Table.Preferences)) { it.devicePreference(identifier, deviceSigner) }

    /** Publishes a device-private kind 30078, NIP-44 to the device key. */
    suspend fun publishDevicePreference(identifier: String, plaintext: String): Event {
        val signer = deviceSigner ?: error("device signer is required for $identifier")
        return publish(Kinds.Preferences, listOf(listOf("d", identifier)), signer.encryptToSelf(plaintext), signer)
    }

    /** App ids in a device-private app stack ([DeviceEvents.BOOKMARKS], [DeviceEvents.UPDATE_IGNORE]), stored order. */
    fun observeDeviceList(identifier: String): Flow<List<String>> = observe(setOf(Table.Stacks)) { store ->
        val signer = deviceSigner ?: return@observe emptyList()
        store.stacks(signer.publicKey, identifier, limit = 1, deviceSigner = signer)
            .firstOrNull()?.apps?.map { it.appId }?.distinct().orEmpty()
    }

    /**
     * Replaces a device-private app stack. Every entry is private, written as
     * `32267:<device-pubkey>:<app-id>` and encrypted to the device key; an empty list is still
     * encrypted, so the event stays private and carries its proof of work.
     */
    suspend fun setDeviceList(identifier: String, appIds: List<String>): Event {
        val signer = deviceSigner ?: error("device signer is required for $identifier")
        val tags = buildList {
            add(listOf("d", identifier))
            add(listOf("name", DeviceEvents.listName(identifier)))
            device.platforms.forEach { add(listOf("f", it)) }
        }
        val coordinates = appIds.distinct().map { "${Kinds.App}:${signer.publicKey}:$it" }
        return publish(Kinds.AppStack, tags, signer.encryptToSelf(JSONArray(coordinates).toString()), signer)
    }

    /**
     * Replaces one of [signer]'s public stacks with [apps] as `a` tags. Its description, content,
     * and tags this client does not model are kept. A stack with no apps is hidden by every client.
     */
    suspend fun setStack(signer: Signer, identifier: String, name: String, apps: List<AppCoordinate>): Event {
        val (stored, content) = store.storedStack(signer.publicKey, identifier) ?: (emptyList<List<String>>() to "")
        val tags = buildList {
            add(listOf("d", identifier))
            add(listOf("name", name))
            stored.firstOrNull { it.firstOrNull() == "description" }?.let(::add)
            apps.distinct().forEach { add(listOf("a", it.toString())) }
            device.platforms.forEach { add(listOf("f", it)) }
            addAll(EventRows.leftoverTags(stored, EventRows.STACK_MODELED_TAGS))
        }
        return publish(Kinds.AppStack, tags, content, signer)
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
        private val answered = ConcurrentHashMap.newKeySet<RelayUrl>()
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
                onEose = { relay ->
                    answered += relay
                    if (error is QueryError.RelayFailure) error = null
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
                    val allFailed = answered.isEmpty() && relays.all { relayStates[it]?.lastError != null }
                    if (state.lastError != null && phase == QueryPhase.Connecting && allFailed) {
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
        // Off the caller's thread: opening the store must not wait on proof expiry.
        scope.launch { store.recomputeProofs(nowMillis() / 1_000) }
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
            store.pendingOutbox().forEach { send(it, writeRelays) }
        }
    }

    /**
     * Device-private events (the ones that need proof of work) go only to the catalog relays,
     * per `SPEC.md#device-private-events`; everything else goes where the caller asked.
     */
    private fun destinations(event: Event, relays: Set<RelayUrl>): Set<RelayUrl> {
        if (Pow.required(event.kind, event.content) > 0) {
            val catalogRelays = store.selectedCatalogs().map { it.relayUrl }.toSet()
            if (catalogRelays.isNotEmpty()) return catalogRelays
        }
        return relays.ifEmpty { writeRelays }
    }

    private suspend fun send(event: Event, relays: Set<RelayUrl>) {
        if (pool == null) return
        val ready = withWork(event) ?: return
        publishOutbox(ready, relays)
    }

    /**
     * A device-key draft is committed without work so the screen updates at once; the work is added here,
     * before it leaves the phone. Null when it needs work and no local key can re-sign it.
     */
    private suspend fun withWork(event: Event): Event? {
        val work = Pow.required(event.kind, event.content)
        if (work == 0 || Pow.meets(event, work)) return event
        val signer = deviceSigner?.takeIf { it.publicKey == event.pubkey } ?: return null
        val tags = withContext(Dispatchers.Default) {
            Pow.mine(event.pubkey, event.createdAt, event.kind, event.tags, event.content, work)
        }
        val mined = signer.sign(event.createdAt, event.kind, tags, event.content)
        store.replaceOutbox(event.id, mined, ingestContext)
        return mined
    }

    private fun publishOutbox(event: Event, relays: Set<RelayUrl>) {
        val dest = destinations(event, relays)
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
