package dev.zapstore.app.screens

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.AppLog
import dev.zapstore.app.BuildConfig
import dev.zapstore.app.ZapstoreApplication
import dev.zapstore.app.catalog.CatalogGate
import dev.zapstore.app.transport.NetworkMode
import dev.zapstore.iolite.CatalogReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CatalogGateUi(
    val gate: CatalogGate,
    val networkMode: NetworkMode,
    /** Restoring Zapstore's catalog from the No catalog screen is running. */
    val restoring: Boolean = false,
    val restoreFailed: Boolean = false,
)

/**
 * The screen the app shows while there is no app data: how to connect, a way to start over, and the
 * diagnostics to send when it will not load. Only the choices that can fix a first load are here.
 */
class CatalogGateViewModel(private val zapstore: ZapstoreApplication) : ViewModel() {
    private val restore = MutableStateFlow(Restore())

    private data class Restore(val running: Boolean = false, val failed: Boolean = false)

    val uiState: StateFlow<CatalogGateUi> = combine(
        zapstore.catalogGate,
        zapstore.settings.state.map { it.networkMode },
        restore,
    ) { gate, mode, restore -> CatalogGateUi(gate, mode, restore.running, restore.failed) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            CatalogGateUi(zapstore.catalogGate.value, zapstore.settings.state.value.networkMode),
        )

    /** The same switch as in Settings and onboarding. A sync already running restarts on the new mode. */
    fun setNetworkMode(mode: NetworkMode) = zapstore.settings.update { it.copy(networkMode = mode) }

    fun retry() = zapstore.catalogSync.request(force = true)

    /** Goes on with the catalog the APK came with, after its first update failed. */
    fun continueWithBundled() = zapstore.catalogSync.finishCatchUp()

    /** Drops what was downloaded and Tor's cache, then fetches the catalog again from the relay. */
    fun clearAndRetry() {
        viewModelScope.launch {
            zapstore.network.clearArtiCache()
            zapstore.catalogSync.wipe()
        }
    }

    /** Lists Zapstore's catalog again. The key is the one this build pins, so nothing is typed. */
    fun restoreDefault() {
        if (restore.value.running) return
        restore.value = Restore(running = true)
        viewModelScope.launch {
            val failure = runCatching {
                val key = zapstore.catalogSync.defaultCatalogKey() ?: error("this build has no default catalog key")
                zapstore.catalogSync.addCatalog(CatalogReference(key))
            }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            failure?.let { AppLog.w(TAG, "restoring the default catalog failed", it) }
            restore.update { Restore(running = false, failed = failure != null) }
        }
    }

    /** What to paste into an issue: where the first load stands, then the log. Never holds a key; the log is written under that rule. */
    suspend fun diagnostics(): String = withContext(Dispatchers.IO) {
        val ui = uiState.value
        val status = zapstore.catalogSync.status.value
        val log = AppLog.files().joinToString("") { it.readText() }.takeLast(MAX_LOG_CHARS)
        buildString {
            appendLine("Zapstore ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}) ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
            appendLine("Network: ${ui.networkMode}")
            appendLine("Catalog screen: ${ui.gate}")
            appendLine("Sync: syncing=${status.syncing} phase=${status.phase} received=${status.receivedBytes} total=${status.totalBytes}")
            appendLine("Catching up on the APK's catalog: ${status.catchingUp}")
            status.error?.let { appendLine("Error: $it") }
            appendLine("Apps: ${zapstore.iolite.appCount()}, catalogs: ${zapstore.iolite.catalogs().size}")
            appendLine()
            append(log)
        }
    }

    companion object {
        private const val TAG = "CatalogGate"

        /** A paste, not a file: well under the clipboard's practical size. */
        private const val MAX_LOG_CHARS = 60_000

        fun factory(app: ZapstoreApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { CatalogGateViewModel(app) }
        }
    }
}
