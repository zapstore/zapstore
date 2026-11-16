package dev.zapstore.app

import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    private val error = IllegalStateException("boom")

    @Test
    fun repeatedTraceIsOneLineUntilTheLogIsCleared() {
        AppLog.clear()
        val first = AppLog.line(Log.WARN, "T", "first", error, 0)
        val repeat = AppLog.line(Log.WARN, "T", "again", error, 0)
        AppLog.clear()
        val afterClear = AppLog.line(Log.WARN, "T", "after clear", error, 0)

        assertTrue(first.contains("\tat "))
        assertFalse(repeat.contains("\tat "))
        assertTrue(repeat.contains("again: java.lang.IllegalStateException: boom") && repeat.endsWith("(trace above)\n"))
        assertTrue(afterClear.contains("\tat "))
    }

    @Test
    fun crashAlwaysKeepsItsTrace() {
        AppLog.clear()
        AppLog.line(Log.WARN, "T", "logged", error, 0)

        assertTrue(AppLog.line(Log.ASSERT, "crash", "Uncaught", error, 0).contains("\tat "))
    }

    @Test
    fun onlyTheSystemDeathNamesCount() {
        assertTrue(AppLog.isSystemDeathName("android.os.DeadSystemException"))
        assertTrue(AppLog.isSystemDeathName("android.os.DeadSystemRuntimeException"))
        assertFalse(AppLog.isSystemDeathName("android.os.DeadObjectException"))
        assertFalse(AppLog.isSystemDeathName("java.lang.IllegalStateException"))
    }

    @Test
    fun chainReachesCausesAndSuppressedErrors() {
        val deepest = IllegalArgumentException("deepest")
        val suppressed = IllegalStateException("suppressed")
        val top = RuntimeException("top", RuntimeException("middle", deepest)).apply { addSuppressed(suppressed) }

        assertTrue(AppLog.chain(top).containsAll(listOf(top, deepest, suppressed)))
    }

    @Test
    fun chainEndsOnAChainThatPointsBackIntoItself() {
        val loop = RuntimeException("loop")
        val top = RuntimeException("top", loop).apply { addSuppressed(loop) }
        loop.addSuppressed(top)

        assertEquals(2, AppLog.chain(top).size)
    }
}
