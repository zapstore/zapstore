package dev.zapstore.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SocialSwitchTest {
    private val steps = mutableListOf<String>()
    private val social = MutableStateFlow(true)
    private val switch = SocialSwitch(
        social = social,
        setReads = { steps += "reads $it" },
        signOut = { steps += "sign out" },
        wipe = { steps += "wipe" },
    )

    @Test
    fun turningItOffClosesReadsThenSignsOutThenWipes() = runTest(UnconfinedTestDispatcher()) {
        val job = launch { switch.run() }
        social.value = false
        assertEquals(listOf("reads true", "reads false", "sign out", "wipe"), steps)
        job.cancel()
    }

    @Test
    fun turningItBackOnOnlyReopensReads() = runTest(UnconfinedTestDispatcher()) {
        val job = launch { switch.run() }
        social.value = false
        steps.clear()
        social.value = true
        assertEquals(listOf("reads true"), steps)
        job.cancel()
    }

    @Test
    fun startingWhileOffFinishesTheWipe() = runTest(UnconfinedTestDispatcher()) {
        social.value = false
        val job = launch { switch.run() }
        assertEquals(listOf("reads false", "sign out", "wipe"), steps)
        job.cancel()
    }
}
