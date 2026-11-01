package dev.zapstore.iolite

import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LivenessOptions(
    val backoffBaseDelay: Duration = 30.seconds,
    val backoffMaxDelay: Duration = 5.minutes,
) {
    internal fun validate() {
        listOf(backoffBaseDelay, backoffMaxDelay)
            .forEach { require(it.isFinite() && it.isPositive()) { "liveness delays must be finite and positive" } }
    }
}

/**
 * Relay health from this phone's own connections, in memory. A failing relay is skipped for
 * [LivenessOptions.backoffBaseDelay], doubling with each further failure up to
 * [LivenessOptions.backoffMaxDelay]; a success clears it. Failures while the network is unavailable
 * do not count, and the network returning or changing forgets every failure, since they may have
 * been the phone's. [unhealthy] holds the relays in backoff and changes when a backoff ends.
 */
class RelayLiveness(
    private val options: LivenessOptions = LivenessOptions(),
    private val scope: CoroutineScope? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Backoff(val failures: Int, val until: Long)

    private val lock = Any()
    private val backoffs = HashMap<RelayUrl, Backoff>()
    private val timers = HashMap<RelayUrl, Job>()
    private var networkAvailable = true
    private val unhealthySet = MutableStateFlow<Set<RelayUrl>>(emptySet())

    init {
        options.validate()
    }

    val unhealthy: StateFlow<Set<RelayUrl>> = unhealthySet.asStateFlow()

    /** Milliseconds until [relay] may be tried again; zero when it may be tried now. */
    fun backoffRemaining(relay: RelayUrl): Long = synchronized(lock) {
        ((backoffs[relay]?.until ?: 0) - nowMillis()).coerceAtLeast(0)
    }

    fun filter(relays: Collection<RelayUrl>): List<RelayUrl> = relays.filter { backoffRemaining(it) == 0L }

    fun recordSuccess(relay: RelayUrl): Unit = synchronized(lock) {
        if (backoffs.remove(relay) == null) return
        timers.remove(relay)?.cancel()
        publish()
    }

    fun recordFailure(relay: RelayUrl): Unit = synchronized(lock) {
        if (!networkAvailable) return
        val now = nowMillis()
        val current = backoffs[relay]
        // A second report of the same outage arrives while the relay is already backing off.
        if (current != null && current.until > now) return
        val failures = (current?.failures ?: 0) + 1
        val delay = min(
            options.backoffBaseDelay.inWholeMilliseconds * (1L shl min(failures - 1, 30)),
            options.backoffMaxDelay.inWholeMilliseconds,
        )
        backoffs[relay] = Backoff(failures, now + delay)
        schedule(relay, delay)
        publish()
    }

    fun setNetworkAvailable(available: Boolean): Unit = synchronized(lock) {
        if (available == networkAvailable) return
        networkAvailable = available
        if (available) forget()
    }

    /** The network path changed (for example a Tor switch): old failures say nothing about the new path. */
    fun networkChanged(): Unit = synchronized(lock) { forget() }

    fun close(): Unit = synchronized(lock) {
        timers.values.forEach(Job::cancel)
        timers.clear()
    }

    private fun forget() {
        timers.values.forEach(Job::cancel)
        timers.clear()
        backoffs.clear()
        publish()
    }

    /** Re-publishes [unhealthy] when the backoff ends; re-arms if the clock moved back. */
    private fun schedule(relay: RelayUrl, delayMillis: Long) {
        val scope = scope ?: return
        timers.remove(relay)?.cancel()
        timers[relay] = scope.launch {
            delay(delayMillis)
            synchronized(lock) {
                val left = (backoffs[relay] ?: return@synchronized).until - nowMillis()
                if (left > 0) schedule(relay, left) else publish()
            }
        }
    }

    private fun publish() {
        val now = nowMillis()
        val next = backoffs.filterValues { it.until > now }.keys.toSet()
        if (next != unhealthySet.value) unhealthySet.value = next
    }
}
