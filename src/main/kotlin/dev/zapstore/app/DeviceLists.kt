package dev.zapstore.app

import dev.zapstore.iolite.DeviceEvents
import dev.zapstore.iolite.Iolite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Saved apps (`zapstore-bookmarks`) and ignored updates (`zapstore-update-ignore`): private app
 * stacks the device key signs, so neither needs an account. Toggles run one at a time, so two
 * quick taps never publish from the same stale list.
 */
class DeviceLists(private val iolite: Iolite, private val scope: CoroutineScope) {
    private val writes = Mutex()

    val saved: StateFlow<List<String>> = list(DeviceEvents.BOOKMARKS)
    val ignored: StateFlow<List<String>> = list(DeviceEvents.UPDATE_IGNORE)

    /** The ignore list from the store; unlike [ignored] it never emits a placeholder first. */
    val ignoredLoaded: Flow<List<String>> = iolite.observeDeviceList(DeviceEvents.UPDATE_IGNORE)

    fun setSaved(appId: String, on: Boolean) = set(DeviceEvents.BOOKMARKS, saved, appId, on)

    fun setIgnored(appId: String, on: Boolean) = set(DeviceEvents.UPDATE_IGNORE, ignored, appId, on)

    private fun list(identifier: String): StateFlow<List<String>> =
        iolite.observeDeviceList(identifier).stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun set(identifier: String, current: StateFlow<List<String>>, appId: String, on: Boolean) {
        scope.launch {
            writes.withLock {
                val ids = current.value
                val next = if (on) (listOf(appId) + ids).distinct() else ids - appId
                if (next == ids) return@withLock
                runCatching { iolite.setDeviceList(identifier, next) }
                    .onSuccess { withTimeoutOrNull(SETTLE_MS) { current.first { it == next } } }
                    .onFailure { AppLog.w(TAG, "$identifier publish failed", it) }
            }
        }
    }

    private companion object {
        const val TAG = "DeviceLists"
        const val SETTLE_MS = 2_000L
    }
}
