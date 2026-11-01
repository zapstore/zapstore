package dev.zapstore.iolite

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayLivenessTest {
    private val relay = "wss://relay.example.com".normalizeRelayUrl()

    @Test
    fun backoffEndsAndUnhealthyFollows() = runTest {
        val liveness = liveness()
        liveness.recordFailure(relay)
        assertEquals(setOf(relay), liveness.unhealthy.value)
        assertEquals(emptyList<RelayUrl>(), liveness.filter(listOf(relay)))
        advance(1_000)
        assertEquals(emptySet<RelayUrl>(), liveness.unhealthy.value)
        assertEquals(listOf(relay), liveness.filter(listOf(relay)))
    }

    @Test
    fun consecutiveFailuresDoubleUpToTheMax() = runTest {
        val liveness = liveness()
        listOf(1_000L, 2_000L, 4_000L, 4_000L).forEach { expected ->
            liveness.recordFailure(relay)
            assertEquals(expected, liveness.backoffRemaining(relay))
            advance(expected)
        }
    }

    @Test
    fun failureDuringBackoffIsTheSameOutage() = runTest {
        val liveness = liveness()
        liveness.recordFailure(relay)
        advance(500)
        liveness.recordFailure(relay)
        assertEquals(500L, liveness.backoffRemaining(relay))
    }

    @Test
    fun successClearsTheStreak() = runTest {
        val liveness = liveness()
        liveness.recordFailure(relay)
        liveness.recordSuccess(relay)
        assertEquals(emptySet<RelayUrl>(), liveness.unhealthy.value)
        liveness.recordFailure(relay)
        assertEquals(1_000L, liveness.backoffRemaining(relay))
    }

    @Test
    fun offlineFailuresDoNotCountAndReturningForgets() = runTest {
        val liveness = liveness()
        liveness.recordFailure(relay)
        liveness.setNetworkAvailable(false)
        advance(1_000)
        liveness.recordFailure(relay)
        assertEquals(0L, liveness.backoffRemaining(relay))
        liveness.setNetworkAvailable(true)
        liveness.recordFailure(relay)
        assertEquals(1_000L, liveness.backoffRemaining(relay))
        liveness.networkChanged()
        assertEquals(emptySet<RelayUrl>(), liveness.unhealthy.value)
    }

    @Test
    fun timerRearmsWhenTheClockMovesBack() = runTest {
        var skew = 0L
        val liveness = RelayLiveness(OPTIONS, backgroundScope) { testScheduler.currentTime + skew }
        liveness.recordFailure(relay)
        skew = -500
        advance(1_000)
        assertTrue(liveness.backoffRemaining(relay) > 0)
        assertEquals(setOf(relay), liveness.unhealthy.value)
        advance(500)
        assertEquals(emptySet<RelayUrl>(), liveness.unhealthy.value)
    }

    private fun TestScope.liveness() = RelayLiveness(OPTIONS, backgroundScope) { testScheduler.currentTime }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    companion object {
        private val OPTIONS = LivenessOptions(backoffBaseDelay = 1_000.milliseconds, backoffMaxDelay = 4_000.milliseconds)
    }
}
