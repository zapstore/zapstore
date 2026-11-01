package dev.zapstore.iolite

import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One relay's part in a query. */
sealed interface RelayStatus {
    /** Asked, not yet at EOSE. */
    data object Pending : RelayStatus

    /** Sent EOSE: it has sent everything it had. */
    data object Answered : RelayStatus

    data class Failed(val reason: String) : RelayStatus
}

internal data class SessionStatus(
    val relays: Map<RelayUrl, RelayStatus> = emptyMap(),
    /** The first EOSE's window has passed, or every relay failed, or there was nothing to ask. */
    val settled: Boolean = false,
)

/**
 * One subscription id across a changing set of relays. [reconcile] applies a new plan: `REQ`s go to new
 * relays and to relays whose filters changed, `CLOSE`s to dropped ones. Events go to [onEvent]; nothing
 * here decides what the data is.
 *
 * One-shot: after the first EOSE, every relay gets [eoseGrace] more, then all are closed and later plans
 * are ignored (the window rule in `iolite/SPEC.md`). Streams stay open; EOSE only marks catch-up.
 * A relay silent past [firstResponseTimeout] counts as failed and is reported to relay health.
 *
 * A held plan may still widen (relay lists are in flight), so the session does not settle while held.
 * When the hold lifts, relays added meanwhile get their own window; the one-shot cap still applies.
 */
internal class RelaySession(
    private val pool: RelayPool,
    private val scope: CoroutineScope,
    private val id: String,
    private val oneShot: Boolean,
    private val eoseGrace: Duration,
    private val firstResponseTimeout: Duration,
    oneShotTimeout: Duration?,
    private val onEvent: (RelayUrl, Event) -> Unit,
    private val onSettled: (answered: Boolean) -> Unit = {},
) : SubscriptionListener {
    private val lock = Any()
    private val status = MutableStateFlow(SessionStatus())
    private val plan = HashMap<RelayUrl, List<Filter>>()
    private val timers = HashMap<RelayUrl, Job>()
    private var windowJob: Job? = null
    private var capJob: Job? = null
    private var closed = false
    private var held = false
    private var settleDue = false

    val state: StateFlow<SessionStatus> = status.asStateFlow()

    init {
        if (oneShot && oneShotTimeout != null) {
            capJob = scope.launch {
                delay(oneShotTimeout)
                synchronized(lock) {
                    if (closed) return@synchronized
                    status.value = status.value.copy(
                        relays = status.value.relays.mapValues { (_, it) -> if (it == RelayStatus.Pending) RelayStatus.Failed("timed out") else it },
                    )
                    settleLocked()
                }
            }
        }
    }

    fun reconcile(next: Map<RelayUrl, List<Filter>>, held: Boolean = false) = synchronized(lock) {
        if (closed) return@synchronized
        for (relay in plan.keys - next.keys) {
            pool.unsubscribe(relay, id)
            plan.remove(relay)
            timers.remove(relay)?.cancel()
            // A relay dropped for failing stays listed, so "every relay failed" stays visible.
            if (status.value.relays[relay] !is RelayStatus.Failed) status.value = status.value.copy(relays = status.value.relays - relay)
        }
        for ((relay, filters) in next) {
            if (plan[relay] == filters) continue
            plan[relay] = filters
            status.value = status.value.copy(relays = status.value.relays + (relay to RelayStatus.Pending))
            armTimer(relay)
            pool.subscribe(relay, id, filters, this)
        }
        this.held = held
        when {
            held -> Unit
            settleDue -> {
                settleDue = false
                // Relays added while held start a fresh window on their first EOSE.
                if (status.value.relays.values.any { it == RelayStatus.Pending }) windowJob = null else settleLocked()
            }
            plan.isEmpty() -> settleLocked()
        }
    }

    fun close() = synchronized(lock) { closeLocked() }

    override fun onEvent(relay: RelayUrl, event: Event) {
        synchronized(lock) {
            if (closed || relay !in plan) return
            timers.remove(relay)?.cancel()
        }
        onEvent.invoke(relay, event)
    }

    override fun onEose(relay: RelayUrl) = synchronized(lock) {
        if (closed || relay !in plan) return@synchronized
        timers.remove(relay)?.cancel()
        mark(relay, RelayStatus.Answered)
        if (windowJob == null && !status.value.settled) {
            windowJob = scope.launch {
                delay(eoseGrace)
                synchronized(lock) { trySettle() }
            }
        }
    }

    override fun onClosed(relay: RelayUrl, reason: String) = synchronized(lock) {
        if (closed || relay !in plan) return@synchronized
        timers.remove(relay)?.cancel()
        // The pool resends the REQ once AUTH is accepted; until then the relay has not answered.
        if (reason.startsWith(AUTH_REQUIRED) && pool.canAuthenticate) return@synchronized
        fail(relay, reason)
    }

    override fun onConnectionError(relay: RelayUrl, reason: String) = synchronized(lock) {
        if (closed || relay !in plan) return@synchronized
        if (status.value.relays[relay] == RelayStatus.Answered) return@synchronized
        fail(relay, reason)
    }

    private fun armTimer(relay: RelayUrl) {
        timers.remove(relay)?.cancel()
        timers[relay] = scope.launch {
            delay(firstResponseTimeout)
            synchronized(lock) {
                if (closed || status.value.relays[relay] != RelayStatus.Pending) return@synchronized
                timers.remove(relay)
                pool.liveness.recordFailure(relay)
                fail(relay, "no response")
            }
        }
    }

    private fun fail(relay: RelayUrl, reason: String) {
        mark(relay, RelayStatus.Failed(reason))
        val relays = status.value.relays.values
        val allFailed = relays.isNotEmpty() && relays.all { it is RelayStatus.Failed }
        // After a hold, the last pending relay failing leaves no EOSE to open a window.
        val nothingPending = windowJob == null && relays.any { it == RelayStatus.Answered } && relays.none { it == RelayStatus.Pending }
        if (allFailed || nothingPending) trySettle()
    }

    private fun trySettle() {
        if (held) settleDue = true else settleLocked()
    }

    private fun mark(relay: RelayUrl, next: RelayStatus) {
        status.value = status.value.copy(relays = status.value.relays + (relay to next))
    }

    private fun settleLocked() {
        if (status.value.settled) {
            if (oneShot) closeLocked()
            return
        }
        val answered = status.value.relays.values.any { it == RelayStatus.Answered }
        // Before `settled` is published, so whatever arrived in the window is committed when it is seen.
        onSettled(answered)
        status.value = status.value.copy(settled = true)
        if (oneShot) closeLocked()
    }

    private fun closeLocked() {
        if (closed) return
        closed = true
        plan.keys.forEach { pool.unsubscribe(it, id) }
        timers.values.forEach(Job::cancel)
        timers.clear()
        windowJob?.cancel()
        capJob?.cancel()
    }
}
