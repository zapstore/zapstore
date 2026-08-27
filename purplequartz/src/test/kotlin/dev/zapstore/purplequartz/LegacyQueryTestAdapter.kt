package dev.zapstore.purplequartz

import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * Keeps the pre-refactor behavioral tests readable while they exercise the new
 * QueryOptions API. New contract tests should use QueryOptions directly.
 */
internal sealed interface QuerySource {
    val options: QueryOptions

    data object Local : QuerySource {
        override val options = QueryOptions.local()
    }

    data class LocalAndRemote(
        val relays: Set<NormalizedRelayUrl>,
        val mode: RemoteMode = RemoteMode.Stream,
        val cachedFor: Duration? = null,
    ) : QuerySource {
        override val options = QueryOptions.localAndRemote(relays, mode, cachedFor)
    }

    data class Remote(
        val relays: Set<NormalizedRelayUrl>,
        val mode: RemoteMode = RemoteMode.Stream,
    ) : QuerySource {
        override val options = QueryOptions.remote(relays, mode)
    }
}

internal object QuerySync {
    val LocalOnly: QueryPhase = QueryPhase.LocalOnly
    val Cached: QueryPhase = QueryPhase.Cached
    val Connecting: QueryPhase = QueryPhase.Connecting
    val CatchingUp: QueryPhase = QueryPhase.CatchingUp
    val Live: QueryPhase = QueryPhase.Live
    val Complete: QueryPhase = QueryPhase.Complete
    val TimedOut: QueryPhase = QueryPhase.TimedOut
    val Failed: QueryPhase = QueryPhase.Failed
}

internal val QueryState.sync: QueryPhase
    get() = phase

internal fun PurpleQuartz.query(filter: Filter, source: QuerySource): Flow<QueryState> =
    query(filter, source.options)

internal fun PurpleQuartz.query(filters: List<Filter>, source: QuerySource): Flow<QueryState> =
    query(filters, source.options)
