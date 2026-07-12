package dev.zapstore.purplequartz

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlin.time.Duration

sealed interface QuerySource {
    data object Local : QuerySource

    data class LocalAndRemote(
        val relays: Set<NormalizedRelayUrl>,
        val mode: RemoteMode = RemoteMode.Stream,
    ) : QuerySource {
        init {
            require(relays.isNotEmpty()) { "At least one relay is required" }
        }
    }

    data class Remote(
        val relays: Set<NormalizedRelayUrl>,
        val mode: RemoteMode = RemoteMode.Stream,
    ) : QuerySource {
        init {
            require(relays.isNotEmpty()) { "At least one relay is required" }
        }
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
    val sync: QuerySync,
    val relays: Map<NormalizedRelayUrl, RelayQueryState> = emptyMap(),
    val error: QueryError? = null,
)

sealed interface QuerySync {
    data object LocalOnly : QuerySync
    data object Connecting : QuerySync
    data object CatchingUp : QuerySync
    data object Live : QuerySync
    data object Complete : QuerySync
    data object TimedOut : QuerySync
    data object Failed : QuerySync
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
