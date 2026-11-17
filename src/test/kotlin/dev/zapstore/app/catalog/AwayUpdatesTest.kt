package dev.zapstore.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class AwayUpdatesTest {
    private val us = "dev.zapstore.app"
    private val hour = TimeUnit.HOURS.toMillis(1)
    private val day = TimeUnit.DAYS.toMillis(1)

    private fun app(id: String, updated: Long, first: Long = 0, installer: String? = us) =
        InstalledApp(id, 1, "1", emptySet(), installingPackage = installer, label = id, firstInstalledMillis = first, updatedMillis = updated)

    @Test
    fun `an update after the mark and before the app came forward is listed by name`() {
        val window = AwayWindow(after = 100 * hour, until = 110 * hour)
        val installed = listOf(app("Zeta", 105 * hour, first = hour), app("alpha", 102 * hour, first = hour))

        assertEquals(listOf("alpha", "Zeta"), awayUpdates(installed, us, window).map { it.label })
    }

    @Test
    fun `an update the user started while the app was open is not listed`() {
        val window = AwayWindow(after = 100 * hour, until = 110 * hour)

        assertEquals(emptyList<InstalledApp>(), awayUpdates(listOf(app("one", 111 * hour, first = hour)), us, window))
    }

    @Test
    fun `an update the user already dismissed is not listed`() {
        val window = AwayWindow(after = 105 * hour, until = 110 * hour)

        assertEquals(emptyList<InstalledApp>(), awayUpdates(listOf(app("one", 104 * hour, first = hour)), us, window))
    }

    @Test
    fun `a first install is not an update`() {
        val window = AwayWindow(after = 100 * hour, until = 110 * hour)

        assertEquals(emptyList<InstalledApp>(), awayUpdates(listOf(app("one", 105 * hour, first = 105 * hour)), us, window))
    }

    @Test
    fun `an app another installer put there is not listed`() {
        val window = AwayWindow(after = 100 * hour, until = 110 * hour)
        val installed = listOf(app("one", 105 * hour, first = hour, installer = "com.android.vending"), app("two", 105 * hour, first = hour, installer = null))

        assertEquals(emptyList<InstalledApp>(), awayUpdates(installed, us, window))
    }

    @Test
    fun `updates pile up until the notice is dismissed, however old`() {
        val window = AwayWindow(after = 0, until = 60 * day)
        val installed = listOf(app("old", 20 * day, first = day), app("recent", 55 * day, first = day))

        assertEquals(listOf("old", "recent"), awayUpdates(installed, us, window).map { it.label })
    }

    @Test
    fun `an update made while the app was on screen is not listed after coming back`() {
        var now = 100 * hour
        val away = AwayUpdates(SeenMark.InMemory(millis = 50 * hour)) { now }
        away.enteredForeground()
        now = 102 * hour
        away.leftForeground()
        now = 110 * hour
        away.enteredForeground()
        val installed = listOf(app("watched", 101 * hour, first = hour), app("away", 105 * hour, first = hour))

        assertEquals(listOf("away"), awayUpdates(installed, us, away.window.value).map { it.label })
    }

    @Test
    fun `dismissing moves the window start to now and keeps it`() {
        var now = 100 * hour
        val mark = SeenMark.InMemory(millis = 50 * hour)
        val away = AwayUpdates(mark) { now }
        assertEquals(AwayWindow(after = 50 * hour, until = 100 * hour), away.window.value)

        now = 120 * hour
        away.enteredForeground()
        assertEquals(AwayWindow(after = 50 * hour, until = 120 * hour), away.window.value)

        now = 121 * hour
        away.dismiss()
        assertEquals(121 * hour, mark.millis)
        assertEquals(AwayWindow(after = 121 * hour, until = 120 * hour), away.window.value)
    }
}
