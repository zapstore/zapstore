package dev.zapstore.app

import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver

/**
 * Refuses to draw the window's first frame until [ready] is true, so the system splash stays up and the app opens
 * on finished content. It lets go after [limitMillis] whatever [ready] says, so a stalled read never traps anyone.
 */
internal class LaunchHold(
    private val view: View,
    private val limitMillis: Long,
    private val ready: () -> Boolean,
) : ViewTreeObserver.OnPreDrawListener {
    private val started = SystemClock.uptimeMillis()

    fun attach() {
        view.viewTreeObserver.addOnPreDrawListener(this)
    }

    override fun onPreDraw(): Boolean {
        if (!ready() && SystemClock.uptimeMillis() - started < limitMillis) return false
        view.viewTreeObserver.removeOnPreDrawListener(this)
        return true
    }
}
