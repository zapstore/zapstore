package dev.zapstore.app.catalog

import dev.zapstore.app.AppLog
import android.os.SystemClock
import dev.zapstore.app.AppConfig
import dev.zapstore.app.BuildConfig
import dev.zapstore.iolite.CatalogRecord
import dev.zapstore.iolite.CatalogReference
import dev.zapstore.iolite.CatalogSyncResult
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.toNpub
import kotlinx.coroutines.CancellationException
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

    /** The key that signed the bundled snapshot. A catalog with this key is the only one the snapshot is imported into. */
    private val snapshotKey: String? by lazy { iolite.bundleSigner(bundledSnapshot) }

    /**
     * The Zapstore catalog's key: the bundled snapshot's signer, or, in a build without a snapshot, the key
     * of the catalog fetched from the build-time relay. Null while neither is known. It does not change
     * with the user's list, so it names Zapstore even after the user removes it.
     */
    fun defaultCatalogKey(): String? =
        snapshotKey ?: iolite.allCatalogs().firstOrNull { AppConfig.catalogRelay in it.relays }?.manifestPubkey

    /** What one pass over the user's catalogs pulled. */
    private class Pass(var bytes: Long = 0, var changed: Boolean = false, val errors: MutableList<String> = mutableListOf())

    /**
     * Syncs every catalog on the user's list, in order. One that fails does not stop the rest; the status
     * carries the first failure. With no catalogs there is nothing to fetch, and that is not an error.
     */
    suspend fun sync() {
        mutex.withLock {
            _status.update { it.copy(syncing = true, error = null) }
            var elapsedMs: Long? = null
            val pass = Pass()
            val failure = runCatching {
                withContext(Dispatchers.IO) {
                    val started = SystemClock.elapsedRealtime()
                    syncAll(pass)
                    elapsedMs = SystemClock.elapsedRealtime() - started
                    AppLog.i(
                        TAG,
                        "catalog sync ${elapsedMs}ms changed=${pass.changed} bytes=${pass.bytes} catalogs=${iolite.catalogs().size} apps=${iolite.apps().size}",
                    )
                }
            }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            failure?.let { AppLog.w(TAG, "catalog sync failed", it) }
            refreshInstalledApps()
            val error = failure?.let { it.message ?: it::class.simpleName } ?: pass.errors.firstOrNull()
            val succeeded = error == null
            _status.update {
                it.copy(
                    syncing = false,
                    lastSyncedAtMillis = if (succeeded) now() else it.lastSyncedAtMillis,
                    lastSyncDurationMillis = if (succeeded) elapsedMs else it.lastSyncDurationMillis,
                    lastSyncBytes = if (failure == null) pass.bytes else it.lastSyncBytes,
                    lastSyncNotModified = if (failure == null) !pass.changed else it.lastSyncNotModified,
                    error = error,
                )
            }
        }
    }

    private suspend fun syncAll(pass: Pass) {
        if (iolite.needsDefaultCatalog()) seedDefault(pass)
        for (catalog in iolite.catalogs()) {
            try {
                record(pass, syncOne(catalog))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.w(TAG, "catalog ${label(catalog)} failed", failure)
                pass.errors += "${label(catalog)}: ${failure.message ?: failure::class.simpleName}"
            }
        }
    }

    /**
     * First run: the bundled catalog becomes the list. A build without the snapshot asks the build-time
     * relay for the whole catalog instead, and the first bundle names its key.
     */
    private fun seedDefault(pass: Pass) {
        if (iolite.seedDefaultCatalog(bundledSnapshot, AppConfig.catalogRelay) != null) return
        try {
            record(pass, iolite.syncCatalog(catalogId = 1L, useOnion = useOnion(), endpoint = AppConfig.catalogRelay, client = BuildConfig.VERSION_NAME))
        } catch (failure: Exception) {
            AppLog.w(TAG, "default catalog failed", failure)
            pass.errors += "${AppConfig.catalogRelay.url}: ${failure.message ?: failure::class.simpleName}"
        }
    }

    private suspend fun syncOne(listed: CatalogRecord): CatalogSyncResult {
        val catalog = iolite.ensureCatalogRelays(listed)
        val isDefault = catalog.manifestPubkey != null && catalog.manifestPubkey == snapshotKey
        return iolite.syncCatalog(
            catalogId = catalog.id,
            useOnion = useOnion(),
            bundled = if (isDefault) bundledSnapshot else ByteArray(0),
            endpoint = if (isDefault) AppConfig.catalogRelay else null,
            client = BuildConfig.VERSION_NAME,
        )
    }

    private fun record(pass: Pass, pulled: CatalogSyncResult) {
        pass.bytes += pulled.bytes
        if (!pulled.notModified) pass.changed = true
        pulled.imported?.skipped?.takeIf { it.isNotEmpty() }?.let { skipped ->
            AppLog.w(TAG, "catalog sync skipped ${skipped.size}: ${skipped.take(MAX_LOGGED_SKIPS).joinToString("; ")}")
        }
    }

    private fun label(catalog: CatalogRecord): String =
        catalog.relayUrl?.url ?: catalog.manifestPubkey?.toNpub()?.let { "${it.take(12)}…" } ?: "catalog ${catalog.id}"

    /**
     * Adds the catalog [reference] names to the end of the list and syncs it. Throws when no relay serves
     * a kind 10067 for its key, or none gives a bundle that key signed, so a mistyped key is refused
     * rather than listed.
     */
    suspend fun addCatalog(reference: CatalogReference): CatalogRecord {
        val catalog = iolite.addCatalog(reference.pubkey, reference.hints, client = BuildConfig.VERSION_NAME, useOnion = useOnion())
        request(force = true)
        return catalog
    }

    /** Removes the catalog and what was downloaded from it. The last one can go too, which empties the app. */
    suspend fun removeCatalog(id: Long) {
        mutex.withLock { withContext(Dispatchers.IO) { iolite.removeCatalog(id) } }
        refreshInstalledApps()
    }

    /** Moves the catalog [delta] places along the list; where two list one app, the earlier one supplies it. */
    suspend fun moveCatalog(id: Long, delta: Int) {
        mutex.withLock { withContext(Dispatchers.IO) { iolite.moveCatalog(id, delta) } }
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
