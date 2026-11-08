package dev.zapstore.app

import android.util.Log
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
}
