package dev.zapstore.app.catalog

import android.os.SystemClock
import android.util.Log
import dev.zapstore.app.AppConfig
import dev.zapstore.iolite.Iolite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncStatus(
    val syncing: Boolean = false,
    val lastSyncedAtMillis: Long? = null,
    val lastSyncDurationMillis: Long? = null,
    val lastSyncBytes: Long? = null,
    val lastSyncNotModified: Boolean = false,
    val error: String? = null,
)

/**
 * Pulls catalog epochs into Iolite and derives the update list from what is stored.
 * Updates are not a snapshot: they recompute whenever listings or the installed-package list change.
 */
class CatalogSync(
    private val iolite: Iolite,
    private val scope: CoroutineScope,
    private val readInstalledApps: () -> List<InstalledApp>,
    private val readBundledSnapshot: () -> ByteArray,
    private val useOnion: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val bundledSnapshot: ByteArray by lazy(readBundledSnapshot)
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())

    /** User-installed packages. System packages are left out, matching the Flutter list. */
    val installedApps: StateFlow<List<InstalledApp>> = installed.asStateFlow()

    /** Newer catalog listings for packages installed on this device. */
    val updates: Flow<List<AvailableUpdate>> =
        combine(iolite.observeApps(), installed) { apps, packages -> availableUpdates(packages, apps) }
            .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val updateCount: Flow<Int> = updates.map { it.size }

    init {
        scope.launch { refreshInstalledApps() }
    }

    suspend fun sync() {
        mutex.withLock {
            _status.update { it.copy(syncing = true, error = null) }
            var elapsedMs: Long? = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val started = SystemClock.elapsedRealtime()
                    val pulled = iolite.syncCatalog(
                        catalogId = iolite.selectedCatalog()?.id ?: 1L,
                        useOnion = useOnion(),
                        bundled = bundledSnapshot,
                        endpoint = AppConfig.catalogRelay,
                    )
                    elapsedMs = SystemClock.elapsedRealtime() - started
                    Log.i(
                        TAG,
                        "catalog sync ${elapsedMs}ms imported=${pulled.imported?.let { "${it.from}->${it.to}" } ?: "304"} bytes=${pulled.bytes} apps=${iolite.apps().size}",
                    )
                    pulled
                }
            }
            refreshInstalledApps()
            _status.update {
                val pulled = result.getOrNull()
                it.copy(
                    syncing = false,
                    lastSyncedAtMillis = if (result.isSuccess) now() else it.lastSyncedAtMillis,
                    lastSyncDurationMillis = if (result.isSuccess) elapsedMs else it.lastSyncDurationMillis,
                    lastSyncBytes = pulled?.bytes ?: it.lastSyncBytes,
                    lastSyncNotModified = pulled?.notModified ?: it.lastSyncNotModified,
                    error = result.exceptionOrNull()?.let { failure -> failure.message ?: failure::class.simpleName },
                )
            }
        }
    }

    suspend fun wipe() {
        mutex.withLock {
            _status.update { it.copy(syncing = true, error = null) }
            val result = runCatching { withContext(Dispatchers.IO) { iolite.wipeLocalData() } }
            _status.update {
                it.copy(
                    syncing = false,
                    lastSyncedAtMillis = null,
                    lastSyncDurationMillis = null,
                    lastSyncBytes = null,
                    lastSyncNotModified = false,
                    error = result.exceptionOrNull()?.let { failure -> failure.message ?: failure::class.simpleName },
                )
            }
        }
    }

    fun refreshInstalled() {
        scope.launch { refreshInstalledApps() }
    }

    private suspend fun refreshInstalledApps() {
        installed.value = withContext(Dispatchers.IO) { runCatching(readInstalledApps).getOrDefault(emptyList()) }
    }

    private companion object {
        const val TAG = "CatalogSync"
    }
}
