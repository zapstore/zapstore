package dev.zapstore.app

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import dev.zapstore.purplequartz.PurpleQuartz
import dev.zapstore.purplequartz.QueryState
import dev.zapstore.purplequartz.QuerySource
import dev.zapstore.purplequartz.RemoteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import org.json.JSONObject
import kotlin.time.Duration

object Catalog {
    const val relay = "wss://relay.zapstore.dev"
    const val profileRelay = "wss://relay.vertexlab.io"
    const val assetKind = 3_063
    const val releaseKind = 30_063
    const val appKind = 32_267
    const val appStackKind = 30_267
    const val profileKind = 0
    const val communityPubkey = "acfeaea6e51420e8068fac446ca9d17d7a9ef6a5d20d93894e50fee3d4902a84"
    val releaseKinds = listOf(releaseKind, assetKind)
}

val PROFILE_RELAYS = setOf(Catalog.relay, Catalog.profileRelay)

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

    fun refreshConnections()
}

enum class QueryType {
    Local,
    LocalAndRemote,
    Remote,
}

class PurpleQuartzCatalogRepository(context: Context) : CatalogRepository {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var client: PurpleQuartz? = null

    private fun client(): PurpleQuartz =
        client ?: PurpleQuartz.create(
            applicationContext,
            BasicOkHttpWebSocket.Builder { OkHttpClient() },
            scope,
        ).also { client = it }

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
