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

/**
 * What a read does while social is off (`Iolite.setSocial`). It is applied live: an open read switches
 * as the user flips the setting.
 */
sealed interface WhenSocialOff {
    /** The read runs as written. */
    data object Unchanged : WhenSocialOff

    /** SQLite only: no relay is asked and no author is routed. What social reads stored is wiped, so this finds nothing social. */
    data object LocalOnly : WhenSocialOff

    /** The same read, asked only of [relays] and never routed by NIP-65. */
    data class Only(val relays: Set<RelayUrl>) : WhenSocialOff
}

/** [options] as they run while social is off. */
internal fun WhenSocialOff.appliedTo(options: QueryOptions): QueryOptions = when (this) {
    WhenSocialOff.Unchanged -> options
    WhenSocialOff.LocalOnly -> QueryOptions.local()
    is WhenSocialOff.Only -> options.copy(relays = relays, useAuthorRelays = false, whenSocialOff = WhenSocialOff.Unchanged)
}

data class QueryOptions(
    val sourceMode: SourceMode = SourceMode.Local,
    val remoteMode: RemoteMode? = null,
    val relays: Set<RelayUrl> = emptySet(),
    /** Skip relay work when the same query completed within this duration. Local-and-remote only. */
    val cachedFor: Duration? = null,
    /**
     * Route by NIP-65 as well: read the query's authors from their outboxes and replies to its
     * recipients from their inboxes. [relays] are always asked too.
     */
    val useAuthorRelays: Boolean = false,
    val whenSocialOff: WhenSocialOff = WhenSocialOff.Unchanged,
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
        require(whenSocialOff == WhenSocialOff.Unchanged || sourceMode != SourceMode.Local) {
            "Local queries have nothing to change while social is off"
        }
        require(whenSocialOff !is WhenSocialOff.Only || whenSocialOff.relays.isNotEmpty()) {
            "A read that stays on some relays while social is off needs at least one"
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
            whenSocialOff: WhenSocialOff = WhenSocialOff.Unchanged,
        ): QueryOptions = QueryOptions(SourceMode.LocalAndRemote, remoteMode, relays, cachedFor, useAuthorRelays, whenSocialOff)

        fun remote(
            relays: Set<RelayUrl>,
            remoteMode: RemoteMode,
            useAuthorRelays: Boolean = false,
            whenSocialOff: WhenSocialOff = WhenSocialOff.Unchanged,
        ): QueryOptions = QueryOptions(SourceMode.Remote, remoteMode, relays, useAuthorRelays = useAuthorRelays, whenSocialOff = whenSocialOff)
    }
}

/**
 * One emission of a collected query: the current SQLite read, and which relays have answered. Items
 * always come from SQLite; relay status only says how much of the network has been heard from.
 */
data class QueryState<out T>(
    val items: T,
    val relays: Map<RelayUrl, RelayStatus> = emptyMap(),
    /**
     * The first EOSE's window has passed, or every relay failed. Always true for local and cached
     * reads. Before this, an empty [items] means "not heard yet"; after it, "nothing there".
     */
    val settled: Boolean = true,
) {
    /** Every asked relay failed, so [items] holds only what was already on the phone. */
    val unreachable: Boolean get() = relays.isNotEmpty() && relays.values.all { it is RelayStatus.Failed }

    fun <R> map(transform: (T) -> R): QueryState<R> = QueryState(transform(items), relays, settled)
}
