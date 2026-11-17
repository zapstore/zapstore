package dev.zapstore.app.catalog

import dev.zapstore.app.AppLog
import android.os.SystemClock
import dev.zapstore.app.AppConfig
import dev.zapstore.app.BuildConfig
import dev.zapstore.iolite.AppFilter
import dev.zapstore.iolite.CatalogBundle
import dev.zapstore.iolite.CatalogProgress
import dev.zapstore.iolite.CatalogRecord
import dev.zapstore.iolite.CatalogReference
import dev.zapstore.iolite.CatalogSyncResult
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.toNpub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where a running sync is. Idle outside a sync, and while it works on something with no progress to show. */
enum class SyncPhase {
    Idle,

    /** Waiting for the network to be usable: Tor is still starting. */
    Connecting,
    Downloading,

    /** The bundle is complete and is being verified and written to the database. */
    Importing,
}

data class SyncStatus(
    val syncing: Boolean = false,
    val phase: SyncPhase = SyncPhase.Idle,
    /** Bytes of the bundle on disk so far, while [phase] is [SyncPhase.Downloading]. */
    val receivedBytes: Long = 0,
    /** The bundle's length, or -1 when the server did not say. */
    val totalBytes: Long = -1,
    val lastSyncedAtMillis: Long? = null,
    val lastSyncDurationMillis: Long? = null,
    val lastSyncBytes: Long? = null,
    val lastSyncNotModified: Boolean = false,
    val error: String? = null,
    /**
     * The phone holds only the catalog the APK came with, and no sync has succeeded since. It ends with the
     * first clean sync, or when the user chooses to go on without one.
     */
    val catchingUp: Boolean = false,
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
    /** The snapshot shipped in the APK, opened again for each read so it is never held in memory. Null when the build has none. */
    private val bundledSnapshot: CatalogBundle?,
    private val useOnion: () -> Boolean = { false },
    /** Waits until relays can be reached, so a sync started while Tor bootstraps does not fail at once. */
    private val awaitRemote: suspend () -> Unit = {},
    /** The key the default catalog must be signed by; null trusts the first signer seen. */
    private val pinnedKey: String? = null,
    /**
     * False while something must happen before Zapstore may use the network: the user has not yet chosen
     * how to connect. A sync then imports the APK's snapshot, which needs no network, and stops.
     */
    private val remoteAllowed: () -> Boolean = { true },
    /** Ends a download in flight. A blocked read does not see a coroutine cancel, so [wipe] cuts the connection too. */
    private val abortTransfers: () -> Unit = {},
    /** Whether the phone is still on the APK's snapshot, kept across launches. See [SyncStatus.catchingUp]. */
    private val catchUpMark: CatchUpMark = CatchUpMark.InMemory(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    /** A sync stopped at [remoteAllowed], so [resumeIfHeld] has something to resume. */
    @Volatile private var heldBack = false
    private val _status = MutableStateFlow(SyncStatus(catchingUp = catchUpMark.pending))
    private var requested: Job? = null
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Hands a download's and an import's progress to [status]. */
    private val progress = object : CatalogProgress {
        override fun downloading(received: Long, total: Long) =
            _status.update { it.copy(phase = SyncPhase.Downloading, receivedBytes = received, totalBytes = total) }

        override fun importing() = _status.update { it.copy(phase = SyncPhase.Importing) }
    }

    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())

    /** User-installed packages. System packages are left out, matching the Flutter list. */
    val installedApps: StateFlow<List<InstalledApp>> = installed.asStateFlow()

    /**
     * Newer catalog listings Zapstore may install: signed by a listed key and not ignored. An app another
     * store installed counts too; its update hands it to Zapstore.
     * Only the installed packages' listings are read; the whole catalog would be tens of MB per emission.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val updates: Flow<List<AvailableUpdate>> =
        installed.flatMapLatest { packages ->
            combine(iolite.observeApps(AppFilter(appIds = packages.map(InstalledApp::packageId))), ignored) { apps, skip ->
                val ignoredIds = skip.toSet()
                availableUpdates(packages, apps).filter { it.app.appId !in ignoredIds }
            }
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
     * that succeeded within [AUTO_SYNC_COOLDOWN_MS] is enough. A request while one is running joins it.
     */
    @Synchronized
    fun request(force: Boolean = false) {
        if (requested?.isActive == true) return
        val last = _status.value.lastSyncedAtMillis
        if (!force && last != null && now() - last < AUTO_SYNC_COOLDOWN_MS) return
        requested = scope.launch { sync() }
    }

    /**
     * Cuts the running sync short and starts a new one. For a change the running sync cannot see: a
     * download that began on Direct must not finish there after the user chose Tor. The caller has already
     * dropped the old connections, which is what ends a blocked download.
     */
    @Synchronized
    fun restart() {
        val old = requested
        old?.cancel()
        requested = scope.launch {
            old?.join()
            sync()
        }
    }

    /** [restart], only if a sync is running; with none, the next one reads the new setting on its own. */
    @Synchronized
    fun restartIfRunning() {
        if (requested?.isActive == true) restart()
    }

    /**
     * The user goes on with the catalog the APK came with. A sync in flight keeps running; the next
     * launch asks the relay again like any other.
     */
    fun finishCatchUp() {
        catchUpMark.pending = false
        _status.update { it.copy(catchingUp = false) }
    }

    /** Whatever [remoteAllowed] waited for is done: a sync that stopped there starts again. */
    fun resumeIfHeld() {
        if (heldBack) restart()
    }

    /**
     * The key that signed the bundled snapshot. A catalog with this key is the only one the snapshot is
     * imported into. A snapshot signed by anything but [pinnedKey] is not used.
     */
    private val snapshotKey: String? by lazy {
        val signer = bundledSnapshot?.let(iolite::bundleSigner) ?: return@lazy null
        if (pinnedKey != null && signer != pinnedKey) {
            AppLog.e(TAG, "bundled catalog is signed by $signer, not $pinnedKey; ignoring it")
            return@lazy null
        }
        signer
    }

    /**
     * The Zapstore catalog's key: the pinned key, else the bundled snapshot's signer, or, in a build
     * without either, the key of the catalog fetched from the build-time relay. Null while none is
     * known. It does not change with the user's list, so it names Zapstore even after the user removes it.
     */
    fun defaultCatalogKey(): String? =
        pinnedKey ?: snapshotKey ?: iolite.allCatalogs().firstOrNull { AppConfig.catalogRelay in it.relays }?.manifestPubkey

    /** What one pass over the user's catalogs pulled. */
    private class Pass(var bytes: Long = 0, var changed: Boolean = false, val errors: MutableList<String> = mutableListOf())

    /**
     * Syncs every catalog on the user's list, in order. One that fails does not stop the rest; the status
     * carries the first failure. With no catalogs there is nothing to fetch, and that is not an error.
     */
    suspend fun sync() {
        mutex.withLock {
            _status.update { it.copy(syncing = true, phase = SyncPhase.Idle, error = null) }
            try {
                runPass()
            } catch (cancelled: CancellationException) {
                // A restart or a network change cut this pass short. The pass that replaces it reports.
                _status.update { it.copy(syncing = false, phase = SyncPhase.Idle) }
                throw cancelled
            } catch (failure: Throwable) {
                // Runs on the process scope: rethrowing would crash the app, and the next launch would sync into it again.
                AppLog.e(TAG, "catalog sync stopped", failure)
                _status.update {
                    it.copy(syncing = false, phase = SyncPhase.Idle, error = failure.message ?: failure::class.simpleName)
                }
            }
        }
    }

    private suspend fun runPass() {
        // The snapshot in the APK needs no network, so it comes first and does not wait for a connection choice.
        withContext(Dispatchers.IO) { importSnapshot() }
        if (!remoteAllowed()) {
            heldBack = true
            // The choice may have been made between the two reads, after which nobody would resume this.
            if (!remoteAllowed()) {
                refreshInstalledApps()
                _status.update { it.copy(syncing = false, phase = SyncPhase.Idle) }
                return
            }
        }
        heldBack = false
        _status.update { it.copy(phase = SyncPhase.Connecting) }
        awaitRemote()
        _status.update { it.copy(phase = SyncPhase.Idle) }
        var elapsedMs: Long? = null
        val pass = Pass()
        val failure = runCatching {
            withContext(Dispatchers.IO) {
                val started = SystemClock.elapsedRealtime()
                syncAll(pass)
                elapsedMs = SystemClock.elapsedRealtime() - started
                AppLog.i(
                    TAG,
                    "catalog sync ${elapsedMs}ms changed=${pass.changed} bytes=${pass.bytes} catalogs=${iolite.catalogs().size} apps=${iolite.appCount()}",
                )
            }
        }.exceptionOrNull()
        if (failure is CancellationException) throw failure
        failure?.let { AppLog.w(TAG, "catalog sync failed", it) }
        refreshInstalledApps()
        val error = failure?.let { it.message ?: it::class.simpleName } ?: pass.errors.firstOrNull()
        val succeeded = error == null
        if (succeeded) {
            catchUpMark.skipBundled = false
            catchUpMark.pending = false
        }
        _status.update {
            it.copy(
                catchingUp = it.catchingUp && !succeeded,
                syncing = false,
                phase = SyncPhase.Idle,
                receivedBytes = 0,
                totalBytes = -1,
                lastSyncedAtMillis = if (succeeded) now() else it.lastSyncedAtMillis,
                lastSyncDurationMillis = if (succeeded) elapsedMs else it.lastSyncDurationMillis,
                lastSyncBytes = if (failure == null) pass.bytes else it.lastSyncBytes,
                lastSyncNotModified = if (failure == null) !pass.changed else it.lastSyncNotModified,
                error = error,
            )
        }
    }

    /**
     * Makes the snapshot in the APK the first catalog's data, with no network. A build without a snapshot,
     * or a [wipe] that asked for the relay's bundle instead, skips it. One that does not import is logged
     * and left to the relay: the user is not stopped by a bad asset.
     */
    private fun importSnapshot() {
        val snapshot = bundledSnapshot ?: return
        if (catchUpMark.skipBundled) return
        val key = snapshotKey ?: return
        try {
            if (iolite.needsDefaultCatalog()) iolite.seedDefaultCatalog(key, AppConfig.catalogRelay)
            val catalog = iolite.catalogs().firstOrNull { it.manifestPubkey == key } ?: return
            // Imported only into a catalog with no data. From then until a sync succeeds, the phone is a build date behind.
            if (iolite.importBundledSnapshot(catalog.id, snapshot, AppConfig.catalogRelay, progress) != null) {
                catchUpMark.pending = true
                _status.update { it.copy(catchingUp = true) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.w(TAG, "bundled catalog was not imported", failure)
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
                // A restart cancels the call under the pass, and OkHttp reports that as an IOException.
                // It is the restart, not a catalog that failed.
                currentCoroutineContext().ensureActive()
                AppLog.w(TAG, "catalog ${label(catalog)} failed", failure)
                pass.errors += "${label(catalog)}: ${failure.message ?: failure::class.simpleName}"
            }
        }
    }

    /**
     * First run: the bundled catalog becomes the list. Without a usable snapshot, a pinned key is listed
     * and synced from the build-time relay like any catalog. With neither, the build-time relay's first
     * bundle names the key.
     */
    private fun seedDefault(pass: Pass) {
        snapshotKey?.let { key -> if (iolite.seedDefaultCatalog(key, AppConfig.catalogRelay) != null) return }
        pinnedKey?.let { key ->
            iolite.seedDefaultCatalog(key, AppConfig.catalogRelay)
            return
        }
        try {
            record(
                pass,
                iolite.syncCatalog(
                    catalogId = 1L,
                    useOnion = useOnion(),
                    endpoint = AppConfig.catalogRelay,
                    client = BuildConfig.VERSION_NAME,
                    progress = progress,
                ),
            )
        } catch (failure: Exception) {
            AppLog.w(TAG, "default catalog failed", failure)
            pass.errors += "${AppConfig.catalogRelay.url}: ${failure.message ?: failure::class.simpleName}"
        }
    }

    private suspend fun syncOne(listed: CatalogRecord): CatalogSyncResult {
        val catalog = iolite.ensureCatalogRelays(listed)
        val key = catalog.manifestPubkey
        val isDefault = key != null && key == (pinnedKey ?: snapshotKey)
        // The snapshot was imported by importSnapshot, before the network was allowed.
        return iolite.syncCatalog(
            catalogId = catalog.id,
            useOnion = useOnion(),
            endpoint = if (isDefault) AppConfig.catalogRelay else null,
            client = BuildConfig.VERSION_NAME,
            progress = progress,
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
     * rather than listed. [AppConfig.catalogRelay] is always asked: a bare npub names no relay, and the
     * Zapstore catalog's kind 10067 is served there.
     */
    suspend fun addCatalog(reference: CatalogReference): CatalogRecord {
        val catalog = iolite.addCatalog(
            reference.pubkey,
            reference.hints + AppConfig.catalogRelay,
            client = BuildConfig.VERSION_NAME,
            useOnion = useOnion(),
        )
        request(force = true)
        return catalog
    }

    /** Removes the catalog and what was downloaded from it. The last one stays: an app with no catalog has nothing to show. */
    suspend fun removeCatalog(id: Long) {
        mutex.withLock {
            if (iolite.catalogs().size <= 1) return
            withContext(Dispatchers.IO) { iolite.removeCatalog(id) }
        }
        refreshInstalledApps()
    }

    /**
     * Clears what was downloaded and syncs again from the relay's latest full bundle. The snapshot in the
     * APK is skipped until that sync succeeds, since it may be what went wrong.
     *
     * Runs on the process scope. With nothing left to show, the catalog screen replaces the Settings
     * screen that asked for this, and a clear that was cancelled halfway would leave the app empty.
     */
    suspend fun wipe() {
        // A stalled download holds the sync lock until its read times out. Start over means now.
        synchronized(this) { requested?.cancel() }
        abortTransfers()
        scope.launch { wipeNow() }.join()
    }

    private suspend fun wipeNow() {
        val cleared = mutex.withLock {
            _status.update { it.copy(syncing = true, error = null) }
            val result = runCatching { withContext(Dispatchers.IO) { iolite.wipeLocalData() } }
            result.exceptionOrNull()?.let { AppLog.e(TAG, "clear local storage failed", it) }
                ?: AppLog.i(TAG, "local storage cleared; resyncing without the bundled snapshot")
            if (result.isSuccess) {
                catchUpMark.skipBundled = true
                // The snapshot was cleared with the rest; the screen for an empty catalog takes over from here.
                catchUpMark.pending = false
            }
            _status.update {
                it.copy(
                    catchingUp = it.catchingUp && !result.isSuccess,
                    syncing = false,
                    lastSyncedAtMillis = null,
                    lastSyncDurationMillis = null,
                    lastSyncBytes = null,
                    lastSyncNotModified = false,
                    error = result.exceptionOrNull()?.let { failure -> failure.message ?: failure::class.simpleName },
                )
            }
            result.isSuccess
        }
        if (cleared) request(force = true)
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
