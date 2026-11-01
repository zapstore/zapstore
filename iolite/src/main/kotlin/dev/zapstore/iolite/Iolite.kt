package dev.zapstore.iolite

import android.content.Context
import androidx.sqlite.SQLiteConnection
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The local-first Nostr client. Every event, whether it arrives in a catalog bundle or over a relay
 * socket, is verified, turned into rows by [IoliteStore], and committed; commits notify the observers
 * reading those tables. UI code reads records and never touches event JSON.
 */
@OptIn(FlowPreview::class)
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
    private val ingestContext = IngestContext(device, deviceSigner, config.relayMonitors)
    private val pendingIngest = ArrayList<Event>()

    // Publish queue routing, in memory: rows survive restarts, these are rebuilt from them.
    private val requestedRelays = ConcurrentHashMap<String, Set<RelayUrl>>()
    private val sentTo = ConcurrentHashMap<String, MutableSet<RelayUrl>>()
    private val publishAcks = ConcurrentHashMap<String, MutableSet<RelayUrl>>()
    private val mining = ConcurrentHashMap.newKeySet<String>()

    // NIP-65 lists and NIP-66 reports being fetched, so concurrent queries ask once.
    /** Authors whose kind 10002 is being fetched; reads routed by them hold their window open. */
    private val relayListsInFlight = MutableStateFlow<Set<String>>(emptySet())
    private val reportCandidates = MutableStateFlow<Set<RelayUrl>>(emptySet())
    private val reportsRequested = ConcurrentHashMap<RelayUrl, Long>()

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
    fun <T> query(query: Query<T>, options: QueryOptions = QueryOptions.local()): Flow<QueryState<T>> = channelFlow {
        if (closed.get()) return@channelFlow
        val status = if (options.isRemote) relayWork(query, options) else MutableStateFlow(SessionStatus(settled = true))
        // Remote-only shows [Query.empty] until a commit lands after the query starts.
        var showStored = options.sourceMode != SourceMode.Remote
        // The first change is the subscription itself, not a commit.
        val commits = store.changes(query.tables).withIndex().map { it.index > 0 }
        val relayChanges = status.drop(1).map { false }
        // SQLite is re-read on every emission, so a settled status never outruns the commit before it.
        merge(commits, relayChanges).collect { committed ->
            if (closed.get()) return@collect
            if (committed) showStored = true
            val current = status.value
            val items = if (showStored) query.read(store, deviceSigner) else query.empty
            send(QueryState(items, current.relays, current.settled))
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
        requestedRelays[event.id] = relays
        store.commitPublish(event, ingestContext)
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
        fun finish(relay: RelayUrl) {
            if (finished.add(relay) && finished.size >= relays.size) done.complete(Unit)
        }
        val unsubscribe = relayPool.subscribe(
            id = "f" + Hex.encode(Crypto.randomBytes(4)),
            filters = filters,
            relays = relays,
            listener = object : SubscriptionListener {
                override fun onEvent(relay: RelayUrl, event: Event) {
                    if (event.verify()) events[event.id] = event
                }

                override fun onEose(relay: RelayUrl) = finish(relay)

                override fun onClosed(relay: RelayUrl, reason: String) {
                    if (!(reason.startsWith(AUTH_REQUIRED) && relayPool.canAuthenticate)) finish(relay)
                }

                override fun onConnectionError(relay: RelayUrl, reason: String) = finish(relay)
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
            listener = object : SubscriptionListener {
                override fun onEvent(relay: RelayUrl, event: Event) {
                    if (event.verify()) reply.complete(event)
                }

                override fun onEose(relay: RelayUrl) {
                    if (sent.compareAndSet(false, true)) relayPool.publish(request, connection.relays) { _, _, _ -> }
                }

                override fun onClosed(relay: RelayUrl, reason: String) = Unit

                override fun onConnectionError(relay: RelayUrl, reason: String) = Unit
            },
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

    fun pendingPublishes(): List<Event> = store.pendingPublishes()

    // --- connectivity ------------------------------------------------------------------------------

    /** The network path changed: reconnect, and stop holding old failures against relays. */
    fun refreshConnections() {
        pool?.liveness?.networkChanged()
        pool?.refreshConnections()
    }

    /**
     * Sockets open only while enabled (the app is in the foreground). While disabled, failures do not
     * count against relay health; on enabling, relays that were failing get a clean slate.
     */
    fun setRelayTrafficEnabled(enabled: Boolean) {
        pool?.liveness?.setNetworkAvailable(enabled)
        pool?.setTrafficEnabled(enabled)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        flushIngest()
        pool?.close()
        pool?.liveness?.close()
        job.cancel()
        store.close()
        synchronized(openDatabases) { openDatabases.remove(databasePath) }
    }

    // --- internals ---------------------------------------------------------------------------------

    private fun checkOpen() = check(!closed.get()) { "Iolite is closed" }

    private fun <T> observe(tables: Set<Table>, read: (IoliteStore) -> T): Flow<T> =
        store.changes(tables).map { read(store) }.flowOn(Dispatchers.IO)

    /**
     * Starts a query's relay work in [this] scope and returns its status. The relay plan is a stream:
     * it is recomputed whenever relay lists, relay health, or NIP-66 reports change, and the session
     * applies each plan as it comes. Nothing waits for relay lists; until one arrives, its author is
     * read from the query's own relays.
     */
    private fun CoroutineScope.relayWork(query: Query<*>, options: QueryOptions): StateFlow<SessionStatus> {
        val relayPool = pool ?: return MutableStateFlow(SessionStatus(settled = true))
        val fingerprint = QueryFingerprint.create(query.filters, options.relays)
        val cachedFor = options.cachedFor
        if (cachedFor != null) {
            val last = store.lastRefresh(fingerprint)
            if (last != null && nowMillis() - last < cachedFor.inWholeMilliseconds) return MutableStateFlow(SessionStatus(settled = true))
        }
        val oneShot = options.remoteMode as? RemoteMode.OneShot
        val session = RelaySession(
            pool = relayPool,
            scope = this,
            id = "q" + Hex.encode(Crypto.randomBytes(4)),
            oneShot = oneShot != null,
            eoseGrace = config.eoseGrace,
            firstResponseTimeout = config.firstResponseTimeout,
            oneShotTimeout = oneShot?.let { it.timeout ?: config.oneShotTimeout },
            onEvent = { _, event -> if (event.verify()) enqueueIngest(event) },
            onSettled = { answered ->
                flushIngest()
                if (answered && oneShot != null) store.recordRefresh(fingerprint, nowMillis())
            },
        )
        launch {
            try {
                readPlan(query, options, relayPool.liveness).collect { session.reconcile(it.relays, held = it.held) }
            } finally {
                session.close()
            }
        }
        return session.state
    }

    private fun readPlan(query: Query<*>, options: QueryOptions, liveness: RelayLiveness): Flow<ReadPlan> {
        val routed = options.useAuthorRelays && (query.outboxAuthors.isNotEmpty() || query.inboxAuthors.isNotEmpty())
        if (!routed) {
            return liveness.unhealthy.map { unhealthy ->
                ReadPlan(RelayRouting.read(query.filters, options.relays, emptyList(), emptyList(), emptyMap(), { it in unhealthy }, config.maxConnections, config.maxRelaysPerUser))
            }.distinctUntilChanged()
        }
        val pubkeys = (query.outboxAuthors + query.inboxAuthors).distinct()
        requestRelayLists(pubkeys, options.relays)
        val profiles = store.changes(setOf(Table.Profiles))
        val reports = store.changes(setOf(Table.RelayReports))
        // Mailboxes are read from SQLite on every emission: a fetch counts as done only after its commit.
        return combine(profiles, liveness.unhealthy, reports, relayListsInFlight) { _, unhealthy, _, inFlight ->
            val boxes = store.mailboxes(pubkeys)
            val candidates = boxes.values.flatMapTo(LinkedHashSet(options.relays)) { it.outboxes + it.inboxes }
            val standings = if (config.relayMonitors.isEmpty()) emptyMap() else store.relayStandings(candidates)
            if (config.relayMonitors.isNotEmpty()) reportCandidates.update { it + candidates }
            val newest = standings.values.maxOfOrNull { it.lastSeen } ?: 0
            val maxAge = config.relayReportMaxAge.inWholeSeconds
            RelayRouting.read(
                filters = query.filters,
                base = options.relays,
                outboxAuthors = query.outboxAuthors,
                inboxAuthors = query.inboxAuthors,
                mailboxes = boxes,
                skip = { it in unhealthy || standings[it]?.excludes(newest, maxAge) == true },
                maxConnections = config.maxConnections,
                maxRelaysPerUser = config.maxRelaysPerUser,
            ).let { ReadPlan(it, held = pubkeys.any { it in inFlight }) }
        }.distinctUntilChanged()
    }

    /** Fetches kind 10002 for [pubkeys] whose lists are older than `relayListCacheFor`, without waiting. */
    private fun requestRelayLists(pubkeys: List<String>, base: Set<RelayUrl>) {
        val relayPool = pool ?: return
        val now = nowMillis()
        val due = pubkeys.filter { pubkey ->
            val last = store.lastRefresh(relayListKey(pubkey))
            last == null || now - last >= config.relayListCacheFor.inWholeMilliseconds
        }
        var stale = emptyList<String>()
        relayListsInFlight.update { inFlight -> stale = due.filterNot(inFlight::contains); inFlight + stale }
        if (stale.isEmpty()) return
        val relays = config.indexerRelays.ifEmpty { base }.let { all -> relayPool.liveness.filter(all).ifEmpty { all.toList() } }
        val filters = stale.chunked(MAX_AUTHORS_PER_FILTER).map { Filter(authors = it, kinds = listOf(Kinds.RelayList)) }
        oneShot("m", relays.associateWith { filters }, config.relayListTimeout) { answered ->
            if (answered) stale.forEach { store.recordRefresh(relayListKey(it), nowMillis()) }
            relayListsInFlight.update { it - stale.toSet() }
        }
    }

    /** Fetches NIP-66 reports for relays not asked about within half of `relayReportMaxAge`. */
    private fun requestRelayReports(candidates: Set<RelayUrl>) {
        if (config.relayMonitors.isEmpty() || config.monitorRelays.isEmpty()) return
        val now = nowMillis()
        val interval = config.relayReportMaxAge.inWholeMilliseconds / 2
        val due = candidates.filter { relay ->
            val last = reportsRequested[relay]
            (last == null || now - last >= interval).also { if (it) reportsRequested[relay] = now }
        }
        if (due.isEmpty()) return
        // Monitors write the `d` tag with or without the trailing slash. The two spellings go in separate
        // filters: relay.nostr.watch answers nothing when one `#d` list mixes them.
        val filters = due.chunked(MAX_RELAYS_PER_REPORT_FILTER).flatMap { chunk ->
            listOf(chunk.map { it.url + "/" }, chunk.map { it.url }).map { spellings ->
                Filter(authors = config.relayMonitors.toList(), kinds = listOf(Kinds.RelayDiscovery), tags = mapOf("d" to spellings))
            }
        }
        oneShot("r", config.monitorRelays.associateWith { filters }, config.relayListTimeout) {}
    }

    /** A background one-shot fetch whose events land in SQLite like any other. */
    private fun oneShot(prefix: String, plan: Map<RelayUrl, List<Filter>>, timeout: Duration, onSettled: (Boolean) -> Unit) {
        val relayPool = pool ?: return
        RelaySession(
            pool = relayPool,
            scope = scope,
            id = prefix + Hex.encode(Crypto.randomBytes(4)),
            oneShot = true,
            eoseGrace = config.eoseGrace,
            firstResponseTimeout = config.firstResponseTimeout,
            oneShotTimeout = timeout,
            onEvent = { _, event -> if (event.verify()) enqueueIngest(event) },
            onSettled = { answered ->
                flushIngest()
                onSettled(answered)
            },
        ).reconcile(plan)
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
        scope.launch { routePublishQueue() }
        if (config.relayMonitors.isNotEmpty()) {
            scope.launch { reportCandidates.debounce(REPORT_REQUEST_DEBOUNCE).collect(::requestRelayReports) }
        }
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

    /**
     * The publish queue as a stream: whenever a row, a relay list, the catalogs, or relay health
     * changes, every pending event goes to destinations it has not been sent to yet. The pool keeps
     * resending unacknowledged events on reconnect; a row is deleted once enough relays accept it.
     */
    private suspend fun routePublishQueue() {
        val relayPool = pool ?: return
        combine(
            store.changes(setOf(Table.PublishQueue, Table.Profiles, Table.Catalogs)),
            relayPool.liveness.unhealthy,
        ) { _, unhealthy -> unhealthy }.collect { unhealthy -> routePending(relayPool, unhealthy) }
    }

    private fun routePending(relayPool: RelayPool, unhealthy: Set<RelayUrl>) {
        val pending = store.pendingPublishes()
        val ids = pending.mapTo(HashSet()) { it.id }
        sentTo.keys.retainAll(ids)
        publishAcks.keys.retainAll(ids)
        if (pending.isEmpty()) return
        val catalogRelays = store.selectedCatalogs().map { it.relayUrl }.toSet()
        val mailboxes = store.mailboxes(pending.flatMap { listOf(it.pubkey) + RelayRouting.recipients(it) })
        for (event in pending) {
            val work = Pow.required(event.kind, event.content)
            if (work > 0 && !Pow.meets(event, work)) {
                mine(event, work)
                continue
            }
            val destinations = if (work > 0 && catalogRelays.isNotEmpty()) {
                // Device-private events go only to the catalog relays, per `SPEC.md#device-private-events`.
                catalogRelays
            } else {
                RelayRouting.write(
                    event = event,
                    requested = requestedRelays[event.id].orEmpty(),
                    mailboxes = mailboxes,
                    fallback = writeRelays,
                    skip = { it in unhealthy },
                    maxRelaysPerUser = config.maxRelaysPerUser,
                )
            }
            val sent = sentTo.getOrPut(event.id) { ConcurrentHashMap.newKeySet() }
            val fresh = destinations.filterTo(HashSet()) { sent.add(it) }
            if (fresh.isEmpty()) continue
            relayPool.publish(event, fresh) { relay, accepted, _ -> if (accepted) acknowledge(event.id, relay) }
        }
    }

    private fun acknowledge(eventId: String, relay: RelayUrl) {
        val acks = publishAcks.getOrPut(eventId) { mutableSetOf() }
        synchronized(acks) {
            acks += relay
            if (acks.size < okThreshold(sentTo[eventId]?.size ?: acks.size)) return
        }
        store.dequeuePublish(eventId)
        publishAcks.remove(eventId)
        sentTo.remove(eventId)
        requestedRelays.remove(eventId)
    }

    /**
     * A device-key draft is committed without work so the screen updates at once; the work is added
     * before it leaves the phone. Replacing the row re-runs routing with the mined event. A draft no
     * local key can re-sign stays queued.
     */
    private fun mine(event: Event, work: Int) {
        val signer = deviceSigner?.takeIf { it.publicKey == event.pubkey } ?: return
        if (!mining.add(event.id)) return
        scope.launch(Dispatchers.Default) {
            try {
                val tags = Pow.mine(event.pubkey, event.createdAt, event.kind, event.tags, event.content, work)
                val mined = signer.sign(event.createdAt, event.kind, tags, event.content)
                requestedRelays[event.id]?.let { requestedRelays[mined.id] = it }
                store.replaceQueuedPublish(event.id, mined, ingestContext)
                requestedRelays.remove(event.id)
            } finally {
                mining.remove(event.id)
            }
        }
    }

    /** Where a read goes; [held] while the relay lists that could widen it are still being fetched. */
    private data class ReadPlan(val relays: Map<RelayUrl, List<Filter>>, val held: Boolean = false)

    companion object {
        private val APP_TABLES = setOf(Table.Apps, Table.Proofs, Table.Catalogs)
        private const val MAX_AUTHORS_PER_FILTER = 500
        private const val MAX_RELAYS_PER_REPORT_FILTER = 100
        private val REPORT_REQUEST_DEBOUNCE = 1.seconds

        private fun relayListKey(pubkey: String) = "nip65:$pubkey"
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
                    pool = webSocket?.let {
                        RelayPool(it, scope, signer, nowSeconds, RelayLiveness(config.liveness, scope, nowMillis))
                    },
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
