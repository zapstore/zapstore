package dev.zapstore.app

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import dev.zapstore.app.catalogsync.CatalogSchema
import dev.zapstore.iolite.OutboxRouter
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.IoliteConfig
import dev.zapstore.iolite.QueryOptions
import dev.zapstore.iolite.QueryPhase
import dev.zapstore.iolite.QueryState
import dev.zapstore.iolite.RemoteMode
import dev.zapstore.iolite.SourceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.days

object Catalog {
    const val updatesUrl = "http://127.0.0.1:3336"
    /** Temporary: browse the compact catalog only. Flip false to restore relays. */
    const val catalogLocalOnly = true
    const val catalogRelayPubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    const val relay = "wss://relay.zapstore.dev"
    const val profileRelay = "wss://relay.vertexlab.io"
    const val assetKind = 3_063
    const val c1Kind = 30_509
    const val releaseKind = 30_063
    const val appKind = 32_267
    const val appStackKind = 30_267
    const val profileKind = 0
    const val relayListKind = 10_002
    const val zapReceiptKind = 9_735
    const val communityPubkey = "acfeaea6e51420e8068fac446ca9d17d7a9ef6a5d20d93894e50fee3d4902a84"
    val releaseKinds = listOf(releaseKind, assetKind)
    val defaultRelays = setOf(
        "wss://relay.primal.net",
        "wss://relay.damus.io",
        "wss://nos.lol",
    )
    val defaultZapRelays = defaultRelays + relay
}

val PROFILE_RELAYS = setOf(Catalog.relay, Catalog.profileRelay)
internal val DEFAULT_CACHE_DURATION = 30.seconds
internal val PROFILE_CACHE_DURATION = 1.days
private const val VERIFIED_ASSET_AND_C1_LIMIT = 500

fun catalogQueryOptions(
    sourceMode: SourceMode = if (Catalog.catalogLocalOnly) SourceMode.Local else SourceMode.LocalAndRemote,
    remoteMode: RemoteMode? = if (sourceMode == SourceMode.Local) null else RemoteMode.OneShot(),
    cachedFor: Duration? = if (sourceMode == SourceMode.LocalAndRemote) DEFAULT_CACHE_DURATION else null,
    relays: Set<String> = setOf(Catalog.relay),
): QueryOptions {
    if (Catalog.catalogLocalOnly) {
        return QueryOptions(
            sourceMode = SourceMode.Local,
            remoteMode = null,
            relays = emptySet(),
            cachedFor = null,
        )
    }
    return QueryOptions(
        sourceMode = sourceMode,
        remoteMode = remoteMode,
        relays = if (sourceMode == SourceMode.Local) emptySet() else relays.map(String::normalizeRelayUrl).toSet(),
        cachedFor = cachedFor,
    )
}

interface CatalogRepository {
    fun query(
        filter: Filter,
        options: QueryOptions = catalogQueryOptions(),
    ): Flow<QueryState> = query(listOf(filter), options)

    fun query(
        filters: List<Filter>,
        options: QueryOptions = catalogQueryOptions(),
    ): Flow<QueryState>

    /**
     * Local-first query whose remote side expands onto the [authors]' NIP-65 read
     * relays once resolved. [relays] is the fallback set used until (and unless)
     * resolution lands; [unionWithFallback] controls whether resolved relays are
     * added to the fallback set or replace it.
     */
    fun queryWithOutbox(
        filters: List<Filter>,
        authors: List<String>,
        cachedFor: Duration? = null,
        relays: Set<String>,
        remoteMode: RemoteMode = RemoteMode.Stream,
        unionWithFallback: Boolean = true,
    ): Flow<QueryState> = query(
        filters,
        catalogQueryOptions(
            remoteMode = remoteMode,
            cachedFor = cachedFor,
            relays = relays,
        ),
    )

    /**
     * Filters app rows using C1 proofs and the relay's APK verification.
     * The relay is trusted for the certificate-to-APK association; the client
     * enforces proof lifecycle and certificate-hash matching.
     */
    suspend fun verifiedApps(apps: List<AppInfo>): List<AppInfo> {
        if (apps.isEmpty()) return emptyList()
        val authors = apps.map { it.event.pubKey }.toSet()
        val filters = apps.map { app ->
            Filter(
                kinds = listOf(Catalog.assetKind),
                tags = mapOf("i" to listOf(app.identifier)),
                limit = VERIFIED_ASSET_AND_C1_LIMIT,
            )
        } + Filter(
            authors = authors.toList(),
            kinds = listOf(Catalog.c1Kind),
            limit = VERIFIED_ASSET_AND_C1_LIMIT,
        )
        val state = query(
            filters,
            catalogQueryOptions(remoteMode = RemoteMode.OneShot()),
        ).first { it.phase.isTerminal }
        val verifiedAuthors = verifiedAppAuthors(state.items, state.items, authors)
        return apps.map { it.copy(hasVerifiedC1 = it.event.pubKey in verifiedAuthors) }
    }

    /**
     * Latest kind-0 metadata for [pubkey]. Implementations may share and cache the
     * upstream session so a feed of rows costs at most one query per author.
     */
    fun profile(pubkey: String): Flow<ProfileInfo?> = queryWithOutbox(
        listOf(Filter(authors = listOf(pubkey), kinds = listOf(Catalog.profileKind), limit = 1)),
        authors = listOf(pubkey),
        cachedFor = PROFILE_CACHE_DURATION,
        relays = PROFILE_RELAYS,
        remoteMode = RemoteMode.OneShot(),
        unionWithFallback = false,
    ).map { state -> state.items.maxByOrNull(Event::createdAt)?.let(::ProfileInfo) }

    /**
     * Resolves the public identity behind an app. A C1 proof takes precedence over
     * the kind-32267 signer; an app signed by the catalog relay has no byline.
     */
    fun appAuthor(app: AppInfo): Flow<String?> = flow { emit(app.event.pubKey) }

    /** The author of the app's relay-validated C1 proof, if present. */
    fun c1Author(app: AppInfo): Flow<String?> = flow { emit(null) }

    fun refreshConnections()

    /**
     * Enables or suspends all relay traffic for app foreground/background.
     * Local data remains fully queryable while suspended.
     */
    fun setRelayTrafficEnabled(enabled: Boolean) = Unit

    fun purpleQuartz(): Iolite? = null
}

class IoliteCatalogRepository(
    context: Context,
    private val eventStoreFactory: ((String) -> IEventStore)? = null,
) : CatalogRepository {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val profileFlows = ConcurrentHashMap<String, Flow<ProfileInfo?>>()
    private val appAuthorFlows = ConcurrentHashMap<String, Flow<String?>>()
    private val c1AuthorFlows = ConcurrentHashMap<String, Flow<String?>>()
    private val relaySigner = MutableStateFlow<String?>(null)
    private var client: Iolite? = null
    private var outboxRouter: OutboxRouter? = null
    @Volatile
    private var relayTrafficEnabled = true

    init {
        scope.launch(Dispatchers.IO) {
            relaySigner.value = fetchRelaySigner()
        }
    }

    private fun client(): Iolite =
        client ?: Iolite.create(
            applicationContext,
            BasicOkHttpWebSocket.Builder { OkHttpClient() },
            scope,
            config = IoliteConfig(
                databaseName = CatalogSchema.DATABASE_NAME,
                // Zap receipts are the unbounded firehose. Addressable catalog kinds are
                // bounded by supersession; assets back the release feed and stay.
                pruneRules = mapOf(Catalog.zapReceiptKind to 90.days),
            ),
            eventStore = eventStoreFactory ?: { path ->
                com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore(dbName = path, relay = null)
            },
        ).also {
            client = it
            if (!relayTrafficEnabled) it.setRelayTrafficEnabled(false)
        }

    private fun router(): OutboxRouter =
        outboxRouter ?: OutboxRouter(
            client(),
            bootstrapRelays = Catalog.defaultZapRelays.map(String::normalizeRelayUrl).toSet(),
        ).also { outboxRouter = it }

    override fun queryWithOutbox(
        filters: List<Filter>,
        authors: List<String>,
        cachedFor: Duration?,
        relays: Set<String>,
        remoteMode: RemoteMode,
        unionWithFallback: Boolean,
    ): Flow<QueryState> {
        if (Catalog.catalogLocalOnly) return query(filters, catalogQueryOptions())
        return router().queryWithOutbox(
            filters,
            authors,
            fallbackRelays = relays.map(String::normalizeRelayUrl).toSet(),
            cachedFor = cachedFor,
            remoteMode = remoteMode,
            unionWithFallback = unionWithFallback,
        )
    }

    override fun profile(pubkey: String): Flow<ProfileInfo?> =
        profileFlows.getOrPut(pubkey) {
            super<CatalogRepository>.profile(pubkey)
                .distinctUntilChanged()
                .shareIn(scope, SharingStarted.WhileSubscribed(PROFILE_SESSION_GRACE_MILLIS), replay = 1)
        }

    override fun appAuthor(app: AppInfo): Flow<String?> =
        appAuthorFlows.getOrPut(app.address) {
            combine(c1Author(app), relaySigner) { c1Author, relayPubkey ->
                val author = c1Author ?: app.event.pubKey
                author.takeUnless { it == relayPubkey }
            }.distinctUntilChanged()
        }

    override fun c1Author(app: AppInfo): Flow<String?> =
        c1AuthorFlows.getOrPut(app.address) {
            flow { emit(resolveC1Author(app)) }
                .shareIn(scope, SharingStarted.WhileSubscribed(PROFILE_SESSION_GRACE_MILLIS), replay = 1)
        }

    override fun query(
        filters: List<Filter>,
        options: QueryOptions,
    ): Flow<QueryState> = client().query(filters = filters, options = options)

    override fun refreshConnections() {
        client?.refreshConnections()
    }

    override fun setRelayTrafficEnabled(enabled: Boolean) {
        relayTrafficEnabled = enabled
        client?.setRelayTrafficEnabled(enabled)
    }

    override fun purpleQuartz(): Iolite? = client()

    private suspend fun resolveC1Author(app: AppInfo): String? {
        return query(
            listOf(
                Filter(
                    authors = listOf(app.event.pubKey),
                    kinds = listOf(Catalog.c1Kind),
                    limit = VERIFIED_ASSET_AND_C1_LIMIT,
                ),
            ),
            catalogQueryOptions(remoteMode = RemoteMode.OneShot()),
        ).first { it.phase.isTerminal }.items
            .asSequence()
            .filter { it.kind == Catalog.c1Kind }
            .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy(Event::id))
            .firstOrNull()
            ?.pubKey
    }

    private fun fetchRelaySigner(): String? = runCatching {
        val informationUrl = Catalog.relay
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
        OkHttpClient().newCall(
            Request.Builder()
                .url(informationUrl)
                .header("Accept", "application/nostr+json")
                .build(),
        ).execute().use { response ->
            response.takeIf { it.isSuccessful }
                ?.body
                ?.string()
                ?.let(::JSONObject)
                ?.optString("pubkey")
                ?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
        }
    }.getOrNull()

    private companion object {
        /** Keeps a profile session warm briefly after the last row leaves the screen. */
        const val PROFILE_SESSION_GRACE_MILLIS = 5 * 60 * 1000L
    }
}

data class AppInfo(
    val event: Event,
    val hasVerifiedC1: Boolean = false,
) {
    val identifier: String get() = event.tagValue("d") ?: event.id
    val name: String get() = event.tagValue("name") ?: identifier
    val summary: String get() = event.tagValue("summary") ?: event.content
    val iconUrl: String? get() = event.tagValue("icon") ?: event.tagValue("image")
    val screenshots: List<String> get() = event.tagValues("image")
    val website: String? get() = event.tagValue("url")
    val repository: String? get() = event.tagValue("repository")
    val license: String? get() = event.tagValue("license")
    val address: String get() = "${Catalog.appKind}:${event.pubKey}:$identifier"
}

/**
 * C1 proofs are APK-verified by the catalog relay. The client still checks the
 * proof's lifecycle and matches its certificate hash to a fetched asset.
 */
fun verifiedAppAuthors(
    assets: Iterable<Event>,
    proofs: Iterable<Event>,
    appAuthors: Set<String> = emptySet(),
    now: Long = System.currentTimeMillis() / 1_000,
): Set<String> {
    val currentProofs = proofs
        .filter { it.kind == Catalog.c1Kind }
        .groupBy { it.pubKey to it.tagValue("d") }
        .mapNotNull { (key, events) ->
            key.second?.let {
                val newestTimestamp = events.maxOf(Event::createdAt)
                key to events
                    .filter { event -> event.createdAt == newestTimestamp }
                    .minByOrNull(Event::id)
            }
        }
        .mapNotNull { (key, event) -> event?.let { key to it } }
        .toMap()
        .filterValues { it.isWellFormedC1() && it.isActiveC1(now) }

    return assets
        .filter { it.kind == Catalog.assetKind }
        .mapNotNull { asset ->
            val hash = asset.tagValue("apk_certificate_hash") ?: return@mapNotNull null
            currentProofs.entries
                .firstOrNull { (key, proof) ->
                    key.second == hash &&
                        (asset.pubKey == proof.pubKey ||
                            proof.tagValue("delegation") == asset.pubKey) &&
                        (appAuthors.isEmpty() || proof.pubKey in appAuthors)
                }
                ?.key?.first
        }
        .toSet()
}

private fun Event.isWellFormedC1(): Boolean {
    if (tags.count { it.firstOrNull() == "d" } != 1) return false
    if (tags.count { it.firstOrNull() == "signature" } != 1) return false
    if (tags.count { it.firstOrNull() == "expiry" } != 1) return false
    if (tags.count { it.firstOrNull() == "cert" } > 1) return false
    if (tags.count { it.firstOrNull() == "delegation" } > 1) return false

    val hash = tagValue("d") ?: return false
    if (!Regex("[0-9a-f]{64}").matches(hash)) return false
    if (tagValue("signature").isNullOrBlank()) return false
    val expiry = tagValue("expiry")?.toLongOrNull() ?: return false
    return expiry > createdAt
}

private fun Event.isActiveC1(now: Long): Boolean =
    !tags.any { it.firstOrNull() == "revoked" } &&
        tagValue("expiry")!!.toLong() > now

data class ReleaseInfo(val event: Event) {
    val appIdentifier: String?
        get() = event.tagValue("i")
            ?: event.tagValue("d")
                ?.substringBefore("@")
                ?.takeIf(String::isNotBlank)
    val version: String get() = event.tagValue("version") ?: "Unknown version"
    val channel: String? get() = event.tagValue("c")
        ?: "main".takeIf { event.kind == Catalog.assetKind }
    val notes: String get() = event.content

    fun outranks(other: ReleaseInfo): Boolean {
        if (version == other.version && event.kind != other.event.kind) {
            return event.kind == Catalog.releaseKind
        }
        if (event.createdAt != other.event.createdAt) return event.createdAt > other.event.createdAt
        if (event.kind != other.event.kind) return event.kind == Catalog.releaseKind
        return event.id < other.event.id
    }
}

fun preferredRelease(current: ReleaseInfo?, candidate: ReleaseInfo): ReleaseInfo =
    if (current == null || candidate.outranks(current)) candidate else current

data class StackInfo(val event: Event) {
    val identifier: String get() = event.tagValue("d") ?: event.id
    val name: String get() = event.tagValue("name") ?: identifier
    val description: String get() = event.tagValue("description") ?: ""
    val appAddresses: List<String> get() = event.tagValues("a").filter { it.startsWith("${Catalog.appKind}:") }
}

data class AppCoordinate(val author: String, val identifier: String) {
    val address: String get() = "${Catalog.appKind}:$author:$identifier"
}

data class ProfileInfo(
    val event: Event,
) {
    val name: String? get() = metadataValue("name")
    val displayName: String? get() = metadataValue("display_name")
    val about: String? get() = metadataValue("about")
    val picture: String? get() = metadataValue("picture") ?: metadataValue("image")
    val banner: String? get() = metadataValue("banner")
    val website: String? get() = metadataValue("website")
    val nip05: String? get() = metadataValue("nip05")

    private fun metadataValue(key: String): String? =
        runCatching { JSONObject(event.content).optString(key).takeIf(String::isNotBlank) }.getOrNull()
}

fun Event.tagValue(name: String): String? =
    tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

fun Event.tagValues(name: String): List<String> =
    tags.mapNotNull { tag -> tag.getOrNull(1)?.takeIf { tag.firstOrNull() == name } }

fun String.toAppCoordinate(): AppCoordinate? {
    val parts = split(":", limit = 3)
    return parts.takeIf { it.size == 3 && it[0] == Catalog.appKind.toString() }
        ?.let { (_, author, identifier) -> AppCoordinate(author, identifier) }
}

private val QueryPhase.isTerminal: Boolean
    get() = this == QueryPhase.Cached ||
        this == QueryPhase.Complete ||
        this == QueryPhase.TimedOut ||
        this == QueryPhase.Failed
