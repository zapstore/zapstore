package dev.zapstore.iolite

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlin.time.Duration

enum class SourceMode {
    Local,
    LocalAndRemote,
    Remote,
}

data class QueryOptions(
    val sourceMode: SourceMode = SourceMode.Local,
    val remoteMode: RemoteMode? = null,
    val relays: Set<NormalizedRelayUrl> = emptySet(),
    val cachedFor: Duration? = null,
) {
    init {
        when (sourceMode) {
            SourceMode.Local -> {
                require(remoteMode == null) { "Local queries cannot have a remote mode" }
                require(relays.isEmpty()) { "Local queries cannot have relays" }
                require(cachedFor == null) { "Local queries cannot be cached" }
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

    companion object {
        fun local(): QueryOptions = QueryOptions()

        fun localAndRemote(
            relays: Set<NormalizedRelayUrl>,
            remoteMode: RemoteMode,
            cachedFor: Duration? = null,
        ): QueryOptions = QueryOptions(SourceMode.LocalAndRemote, remoteMode, relays, cachedFor)

        fun remote(
            relays: Set<NormalizedRelayUrl>,
            remoteMode: RemoteMode,
        ): QueryOptions = QueryOptions(SourceMode.Remote, remoteMode, relays)
    }
}

sealed interface RemoteMode {
    data class OneShot(
        val timeout: Duration? = null,
    ) : RemoteMode {
        init {
            require(timeout == null || (timeout.isFinite() && timeout.isPositive())) {
                "One-shot timeout must be finite and positive"
            }
        }
    }

    data object Stream : RemoteMode
}

data class QueryState(
    val items: List<Event>,
    val phase: QueryPhase,
    val relays: Map<NormalizedRelayUrl, RelayQueryState> = emptyMap(),
    val error: QueryError? = null,
)

sealed interface QueryPhase {
    data object LocalOnly : QueryPhase
    data object Cached : QueryPhase
    data object Connecting : QueryPhase
    data object CatchingUp : QueryPhase
    data object Live : QueryPhase
    data object Complete : QueryPhase
    data object TimedOut : QueryPhase
    data object Failed : QueryPhase
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

    data class InvalidQuery(override val message: String) : QueryError
    data class UnsupportedLocalProjection(override val message: String) : QueryError
    data class RelayFailure(
        val relay: NormalizedRelayUrl,
        override val message: String,
    ) : QueryError

    data class VerificationRejected(
        val relay: NormalizedRelayUrl,
        val eventId: String?,
        override val message: String,
    ) : QueryError

    data class ProtocolViolation(
        val relay: NormalizedRelayUrl,
        val eventId: String?,
        override val message: String,
    ) : QueryError

    data class PersistenceFailure(
        val eventId: String?,
        override val message: String,
    ) : QueryError

    data class IngestionSaturated(
        val relay: NormalizedRelayUrl,
        override val message: String,
    ) : QueryError

    data class Timeout(
        val duration: Duration,
        override val message: String,
    ) : QueryError

    data class IncompatibleDependency(override val message: String) : QueryError
    data class Lifecycle(override val message: String) : QueryError
}
