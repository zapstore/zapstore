package dev.zapstore.app.catalog

import dev.zapstore.app.AppLog
import android.os.SystemClock
import dev.zapstore.app.AppConfig
import dev.zapstore.app.BuildConfig
import dev.zapstore.iolite.Iolite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
    /** App ids whose updates the user ignores; they stay installed but never count as updates. */
    private val ignored: Flow<List<String>> = flowOf(emptyList()),
    private val readInstalledApps: () -> List<InstalledApp>,
    private val readBundledSnapshot: () -> ByteArray,
    private val useOnion: () -> Boolean = { false },
    /** Apps another store installed are never updated from here, by hand or in the background. */
    private val ourPackage: String = "",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val bundledSnapshot: ByteArray by lazy(readBundledSnapshot)
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())

    /** User-installed packages. System packages are left out, matching the Flutter list. */
    val installedApps: StateFlow<List<InstalledApp>> = installed.asStateFlow()

    /** Newer catalog listings Zapstore may install: not ignored, and not kept current by another store. */
    val updates: Flow<List<AvailableUpdate>> =
        combine(iolite.observeApps(), installed, ignored) { apps, packages, skip ->
            val ignoredIds = skip.toSet()
            availableUpdates(packages, apps).filter { it.app.appId !in ignoredIds && it.otherStore(ourPackage) == null }
        }
            .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val updateCount: Flow<Int> = updates.map { it.size }

    private val installedChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch { refreshInstalledApps() }
        scope.launch {
            installedChanges.collectLatest {
                delay(INSTALLED_SETTLE_MS)
                refreshInstalledApps()
            }
        }
    }

    /**
     * Starts a sync on the process scope, so leaving a screen never cancels it. Without [force], a sync
     * that succeeded within [AUTO_SYNC_COOLDOWN_MS] is enough.
     */
    fun request(force: Boolean = false) {
        val last = _status.value.lastSyncedAtMillis
        if (!force && last != null && now() - last < AUTO_SYNC_COOLDOWN_MS) return
        scope.launch { sync() }
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
                        client = BuildConfig.VERSION_NAME,
                    )
                    elapsedMs = SystemClock.elapsedRealtime() - started
                    AppLog.i(
                        TAG,
                        "catalog sync ${elapsedMs}ms imported=${pulled.imported?.let { "${it.from}->${it.to}" } ?: "304"} bytes=${pulled.bytes} apps=${iolite.apps().size}",
                    )
                    pulled.imported?.skipped?.takeIf { it.isNotEmpty() }?.let { skipped ->
                        AppLog.w(TAG, "catalog sync skipped ${skipped.size}: ${skipped.take(MAX_LOGGED_SKIPS).joinToString("; ")}")
                    }
                    pulled
                }
            }
            result.exceptionOrNull()?.let { AppLog.w(TAG, "catalog sync failed", it) }
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

    /** One package change arrives as removed, added and replaced; a burst reads the device once. */
    fun refreshInstalled() {
        installedChanges.tryEmit(Unit)
    }

    private suspend fun refreshInstalledApps() {
        installed.value = withContext(Dispatchers.IO) { runCatching(readInstalledApps).getOrDefault(emptyList()) }
    }

    private companion object {
        const val TAG = "CatalogSync"
        const val MAX_LOGGED_SKIPS = 20
        const val AUTO_SYNC_COOLDOWN_MS = 15 * 60 * 1000L
        const val INSTALLED_SETTLE_MS = 250L
    }
}
