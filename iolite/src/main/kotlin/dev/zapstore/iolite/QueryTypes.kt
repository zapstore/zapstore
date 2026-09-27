package dev.zapstore.iolite

import kotlin.time.Duration

enum class SourceMode {
    /** SQLite only. Emits on every commit touching the observed tables. */
    Local,
    /** SQLite first, then relays; re-emits after each commit. */
    LocalAndRemote,
    /** Empty first, then relays; re-emits after each commit. */
    Remote,
}

sealed interface RemoteMode {
    /** Ends 200 ms after the first EOSE, or at [timeout]. */
    data class OneShot(val timeout: Duration? = null) : RemoteMode {
        init {
            require(timeout == null || (timeout.isFinite() && timeout.isPositive())) {
                "One-shot timeout must be finite and positive"
            }
        }
    }

    /** Keeps the subscription open until the collector is cancelled. */
    data object Stream : RemoteMode
}

data class QueryOptions(
    val sourceMode: SourceMode = SourceMode.Local,
    val remoteMode: RemoteMode? = null,
    val relays: Set<RelayUrl> = emptySet(),
    /** Skip relay work when the same query completed within this duration. Local-and-remote only. */
    val cachedFor: Duration? = null,
    /** Also query the NIP-65 read relays of the observed authors. */
    val useAuthorRelays: Boolean = false,
) {
    init {
        when (sourceMode) {
            SourceMode.Local -> {
                require(remoteMode == null) { "Local queries cannot have a remote mode" }
                require(relays.isEmpty()) { "Local queries cannot have relays" }
                require(cachedFor == null) { "Local queries cannot be cached" }
                require(!useAuthorRelays) { "Local queries cannot use author relays" }
            }
            SourceMode.LocalAndRemote,
            SourceMode.Remote,
            -> {
                require(remoteMode != null) { "Remote-enabled queries require a remote mode" }
                require(relays.isNotEmpty()) { "Remote-enabled queries require at least one relay" }
            }
        }
        require(sourceMode == SourceMode.LocalAndRemote || cachedFor == null) {
            "cachedFor is supported only for local-and-remote queries"
        }
        require(cachedFor == null || (cachedFor.isFinite() && cachedFor.isPositive())) {
            "Cache duration must be finite and positive"
        }
    }

    val isRemote: Boolean get() = sourceMode != SourceMode.Local

    companion object {
        fun local(): QueryOptions = QueryOptions()

        fun localAndRemote(
            relays: Set<RelayUrl>,
            remoteMode: RemoteMode,
            cachedFor: Duration? = null,
            useAuthorRelays: Boolean = false,
        ): QueryOptions = QueryOptions(SourceMode.LocalAndRemote, remoteMode, relays, cachedFor, useAuthorRelays)

        fun remote(
            relays: Set<RelayUrl>,
            remoteMode: RemoteMode,
            useAuthorRelays: Boolean = false,
        ): QueryOptions = QueryOptions(SourceMode.Remote, remoteMode, relays, useAuthorRelays = useAuthorRelays)
    }
}

/** One emission of a collected query: the current SQLite read plus relay progress. */
data class QueryState<out T>(
    val items: T,
    val phase: QueryPhase,
    val relays: Map<RelayUrl, RelayQueryState> = emptyMap(),
    val error: QueryError? = null,
) {
    /** True while the first remote request is still unfinished. */
    val isLoading: Boolean get() = phase == QueryPhase.Connecting || phase == QueryPhase.CatchingUp

    /** True once no further relay work will happen for this session. */
    val isTerminal: Boolean get() = phase.isTerminal

    fun <R> map(transform: (T) -> R): QueryState<R> = QueryState(transform(items), phase, relays, error)
}

sealed interface QueryPhase {
    data object LocalOnly : QueryPhase
    data object Cached : QueryPhase
    data object Connecting : QueryPhase
    data object CatchingUp : QueryPhase
    data object Live : QueryPhase
    data object Complete : QueryPhase
    data object TimedOut : QueryPhase
    data object Failed : QueryPhase

    val isTerminal: Boolean
        get() = this == LocalOnly || this == Cached || this == Complete || this == TimedOut || this == Failed
}

internal fun isLegalPhaseTransition(from: QueryPhase?, to: QueryPhase): Boolean = when {
    from == null || from == to -> true
    from == QueryPhase.LocalOnly -> to == QueryPhase.Failed
    from == QueryPhase.Cached -> to == QueryPhase.Connecting || to == QueryPhase.Failed
    from == QueryPhase.Connecting -> to == QueryPhase.CatchingUp ||
        to == QueryPhase.Live ||
        to == QueryPhase.Complete ||
        to == QueryPhase.TimedOut ||
        to == QueryPhase.Failed
    from == QueryPhase.CatchingUp -> to == QueryPhase.Connecting ||
        to == QueryPhase.Live ||
        to == QueryPhase.Complete ||
        to == QueryPhase.TimedOut ||
        to == QueryPhase.Failed
    from == QueryPhase.Live -> to == QueryPhase.Connecting ||
        to == QueryPhase.CatchingUp ||
        to == QueryPhase.Failed
    else -> false
}

data class RelayQueryState(
    val generation: Long,
    val connection: RelayConnectionState,
    val eose: Boolean,
    val lastError: String? = null,
)

enum class RelayConnectionState {
    Connecting,
    Connected,
    Disconnected,
    Closed,
}

sealed interface QueryError {
    val message: String

    data class RelayFailure(val relay: RelayUrl, override val message: String) : QueryError
    data class VerificationRejected(val relay: RelayUrl, val eventId: String?, override val message: String) : QueryError
    data class Timeout(val duration: Duration, override val message: String) : QueryError
    data class IncompatibleDependency(override val message: String) : QueryError
}
