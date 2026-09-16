package dev.zapstore.iolite

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * NIP-65 outbox routing on top of [Iolite].
 *
 * Resolution is local-first: kind-10002 relay lists are read from the local
 * store and only fetched from [bootstrapRelays] when the TTL'd freshness cache
 * ([QueryOptions.cachedFor]) reports a miss or staleness. A fetch
 * that cannot complete within [resolveTimeout] resolves to whatever the local
 * store holds.
 */
class OutboxRouter(
    private val client: Iolite,
    private val bootstrapRelays: Set<NormalizedRelayUrl>,
    private val cacheDuration: Duration = 6.hours,
    private val resolveTimeout: Duration = 10.seconds,
) {
    init {
        require(bootstrapRelays.isNotEmpty()) { "bootstrapRelays must not be empty" }
    }

    /**
     * Read relays advertised by [pubkey] (unmarked `r` tags count as read+write,
     * per NIP-65). Empty when no relay list is known or reachable.
     */
    suspend fun resolveReadRelays(pubkey: String): Set<NormalizedRelayUrl> {
        val state = client.query(
            Filter(authors = listOf(pubkey), kinds = listOf(RELAY_LIST_KIND), limit = 1),
            QueryOptions.localAndRemote(
                relays = bootstrapRelays,
                remoteMode = RemoteMode.OneShot(resolveTimeout),
                cachedFor = cacheDuration,
            ),
        ).first { it.phase.isTerminal }
        return state.items.maxByOrNull(Event::createdAt)?.readRelayUrls().orEmpty()
    }

    /**
     * Runs [filters] against [fallbackRelays] immediately so local data flows without
     * waiting, resolves the [authors]' read relays in parallel, then transparently
     * restarts the remote side on the expanded relay set when resolution lands.
     *
     * With [unionWithFallback] the resolved relays are added to the fallback set;
     * otherwise the resolved set replaces it whenever it is non-empty.
     */
    fun queryWithOutbox(
        filters: List<Filter>,
        authors: List<String>,
        fallbackRelays: Set<NormalizedRelayUrl>,
        cachedFor: Duration? = null,
        remoteMode: RemoteMode = RemoteMode.Stream,
        unionWithFallback: Boolean = true,
    ): Flow<QueryState> = channelFlow {
        fun options(relays: Set<NormalizedRelayUrl>) =
            QueryOptions.localAndRemote(
                relays = relays,
                remoteMode = remoteMode,
                cachedFor = cachedFor,
            )

        if (authors.isEmpty()) {
            client.query(filters, options(fallbackRelays)).collect { send(it) }
            return@channelFlow
        }

        val fallbackJob = launch {
            client.query(filters, options(fallbackRelays)).collect { send(it) }
        }
        val resolved = authors
            .map { async { resolveReadRelays(it) } }
            .flatMap { it.await() }
            .toSet()
        val expanded = when {
            resolved.isEmpty() -> fallbackRelays
            unionWithFallback -> fallbackRelays + resolved
            else -> resolved
        }
        if (expanded == fallbackRelays) {
            fallbackJob.join()
        } else {
            fallbackJob.cancel()
            fallbackJob.join()
            client.query(filters, options(expanded)).collect { send(it) }
        }
    }

    companion object {
        const val RELAY_LIST_KIND = 10_002

        private val QueryPhase.isTerminal: Boolean
            get() = this == QueryPhase.Cached ||
                this == QueryPhase.Complete ||
                this == QueryPhase.TimedOut ||
                this == QueryPhase.Failed

        private fun Event.readRelayUrls(): Set<NormalizedRelayUrl> =
            tags.asSequence()
                .filter { tag -> tag.getOrNull(0) == "r" }
                .filter { tag -> tag.getOrNull(2).let { marker -> marker == null || marker == "read" } }
                .mapNotNull { tag -> tag.getOrNull(1) }
                .filter { it.startsWith("wss://") }
                .mapNotNull { runCatching { it.normalizeRelayUrl() }.getOrNull() }
                .toSet()
    }
}
