package dev.zapstore.app.catalog

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * When the user last dismissed the notice of updates made while they were away. Android already
 * records when each package was updated, so this single time is all that is kept.
 */
interface SeenMark {
    var millis: Long

    /** Kept for the life of the process. Tests, and builds that have no place to write. */
    class InMemory(override var millis: Long = 0) : SeenMark
}

/**
 * The first read sets the mark to now: updates made before this feature ran were never "while away",
 * and an app that has never been updated must not announce its history.
 */
class PrefsSeenMark(context: Context, private val clock: () -> Long = System::currentTimeMillis) : SeenMark {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var millis: Long
        get() {
            if (!prefs.contains(MILLIS)) prefs.edit().putLong(MILLIS, clock()).apply()
            return prefs.getLong(MILLIS, 0)
        }
        set(value) {
            prefs.edit().putLong(MILLIS, value).apply()
        }

    private companion object {
        const val PREFS = "away_updates"
        const val MILLIS = "seen"
    }
}

/**
 * Updates count as made while the user was away when they landed after [after] and before the app came
 * to the screen at [until], outside every span in [onScreen]. One made while the app was on screen is
 * one the user started, and watched.
 */
data class AwayWindow(val after: Long, val until: Long, val onScreen: List<LongRange> = emptyList())

/**
 * The window of the notice that says which apps Zapstore updated in the background. It lives in memory
 * and starts from the persisted [SeenMark]; dismissing moves that mark to now.
 */
class AwayUpdates(private val mark: SeenMark, private val clock: () -> Long = System::currentTimeMillis) {
    private val _window = MutableStateFlow(AwayWindow(after = mark.millis, until = clock()))
    val window: StateFlow<AwayWindow> = _window
    private var onScreenSince: Long? = null

    /** The app came back to the screen: everything up to now happened while it was away. */
    @Synchronized
    fun enteredForeground() {
        val now = clock()
        onScreenSince = now
        _window.update { it.copy(until = now) }
    }

    /** The app left the screen. What it updated while on screen the user saw happen. */
    @Synchronized
    fun leftForeground() {
        val since = onScreenSince ?: return
        onScreenSince = null
        val now = clock()
        _window.update { window -> window.copy(onScreen = (window.onScreen + listOf(since..now)).filter { it.last > window.after }) }
    }

    /** The user closed the notice. The next one starts from here. */
    @Synchronized
    fun dismiss() {
        val now = clock()
        mark.millis = now
        _window.update { AwayWindow(after = now, until = it.until) }
    }
}

/**
 * Apps Zapstore installed that were updated, not first installed, inside [window]. Android tells who
 * installed a package but not whether an update was automatic; the window is what separates the two.
 * Updates pile up here until the user dismisses the notice.
 */
fun awayUpdates(installed: List<InstalledApp>, ourPackage: String, window: AwayWindow): List<InstalledApp> =
    installed
        .filter {
            it.installingPackage == ourPackage &&
                it.updatedMillis > it.firstInstalledMillis &&
                it.updatedMillis > window.after &&
                it.updatedMillis <= window.until &&
                window.onScreen.none { span -> it.updatedMillis in span }
        }
        .sortedBy { it.label.lowercase() }
