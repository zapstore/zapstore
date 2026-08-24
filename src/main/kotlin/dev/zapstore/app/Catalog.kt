package dev.zapstore.app

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import dev.zapstore.purplequartz.OutboxRouter
import dev.zapstore.purplequartz.PurpleQuartz
import dev.zapstore.purplequartz.PurpleQuartzConfig
import dev.zapstore.purplequartz.QueryState
import dev.zapstore.purplequartz.QuerySource
import dev.zapstore.purplequartz.RemoteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

object Catalog {
    const val relay = "wss://relay.zapstore.dev"
    const val profileRelay = "wss://relay.vertexlab.io"
    const val assetKind = 3_063
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
internal val PROFILE_CACHE_DURATION = 6.hours

interface CatalogRepository {
    fun query(
        filter: Filter,
        type: QueryType,
        cachedFor: Duration? = null,
        relays: Set<String> = setOf(Catalog.relay),
    ): Flow<QueryState> = query(listOf(filter), type, cachedFor, relays)

    fun query(
        filters: List<Filter>,
        type: QueryType,
        cachedFor: Duration? = null,
        relays: Set<String> = setOf(Catalog.relay),
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
        unionWithFallback: Boolean = true,
    ): Flow<QueryState> = query(filters, QueryType.LocalAndRemote, cachedFor, relays)

    /**
     * Latest kind-0 metadata for [pubkey]. Implementations may share and cache the
     * upstream session so a feed of rows costs at most one query per author.
     */
    fun profile(pubkey: String): Flow<ProfileInfo?> = queryWithOutbox(
        listOf(Filter(authors = listOf(pubkey), kinds = listOf(Catalog.profileKind), limit = 1)),
        authors = listOf(pubkey),
        cachedFor = PROFILE_CACHE_DURATION,
        relays = PROFILE_RELAYS,
        unionWithFallback = false,
    ).map { state -> state.items.maxByOrNull(Event::createdAt)?.let(::ProfileInfo) }

    fun refreshConnections()

    /**
     * Enables or suspends all relay traffic for app foreground/background.
     * Local data remains fully queryable while suspended.
     */
    fun setRelayTrafficEnabled(enabled: Boolean) = Unit
}

enum class QueryType {
    Local,
    LocalAndRemote,
    Remote,
}

class PurpleQuartzCatalogRepository(context: Context) : CatalogRepository {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val profileFlows = ConcurrentHashMap<String, Flow<ProfileInfo?>>()
    private var client: PurpleQuartz? = null
    private var outboxRouter: OutboxRouter? = null

    private fun client(): PurpleQuartz =
        client ?: PurpleQuartz.create(
            applicationContext,
            BasicOkHttpWebSocket.Builder { OkHttpClient() },
            scope,
            config = PurpleQuartzConfig(
                // Zap receipts are the unbounded firehose. Addressable catalog kinds are
                // bounded by supersession; assets back the release feed and stay.
                pruneRules = mapOf(Catalog.zapReceiptKind to 90.days),
            ),
        ).also { client = it }

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
        unionWithFallback: Boolean,
    ): Flow<QueryState> = router().queryWithOutbox(
        filters,
        authors,
        fallbackRelays = relays.map(String::normalizeRelayUrl).toSet(),
        cachedFor = cachedFor,
        unionWithFallback = unionWithFallback,
    )

    override fun profile(pubkey: String): Flow<ProfileInfo?> =
        profileFlows.getOrPut(pubkey) {
            super<CatalogRepository>.profile(pubkey)
                .distinctUntilChanged()
                .shareIn(scope, SharingStarted.WhileSubscribed(PROFILE_SESSION_GRACE_MILLIS), replay = 1)
        }

    override fun query(
        filters: List<Filter>,
        type: QueryType,
        cachedFor: Duration?,
        relays: Set<String>,
    ): Flow<QueryState> {
        require(type == QueryType.LocalAndRemote || cachedFor == null) {
            "cachedFor is supported only for local-and-remote queries"
        }

        val source = when (type) {
            QueryType.Local -> QuerySource.Local
            QueryType.LocalAndRemote -> QuerySource.LocalAndRemote(
                relays = relays.map(String::normalizeRelayUrl).toSet(),
                mode = RemoteMode.Stream,
                cachedFor = cachedFor,
            )
            QueryType.Remote -> QuerySource.Remote(
                relays = setOf(Catalog.relay.normalizeRelayUrl()),
                mode = RemoteMode.OneShot(),
            )
        }

        return client().query(filters = filters, source = source)
    }

    override fun refreshConnections() {
        client?.refreshConnections()
    }

    override fun setRelayTrafficEnabled(enabled: Boolean) {
        client?.setRelayTrafficEnabled(enabled)
    }

    private companion object {
        /** Keeps a profile session warm briefly after the last row leaves the screen. */
        const val PROFILE_SESSION_GRACE_MILLIS = 5 * 60 * 1000L
    }
}

data class AppInfo(val event: Event) {
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

data class ReleaseInfo(val event: Event) {
    val appIdentifier: String?
        get() = event.tagValue("i")
            ?: event.tagValue("d")
                ?.substringBefore("@")
                ?.takeIf(String::isNotBlank)
    val version: String get() = event.tagValue("version") ?: "Unknown version"
    val channel: String? get() = event.tagValue("c")
    val notes: String get() = event.content
}

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
