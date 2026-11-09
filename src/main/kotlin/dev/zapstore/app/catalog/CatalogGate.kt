package dev.zapstore.app.catalog

import dev.zapstore.iolite.Iolite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Whether the app has anything to show. Updates, search, stacks and every app page read the catalog, so
 * with no app data the app shows one screen about getting it instead of screens that look broken.
 * Only an empty database closes the app: a second catalog loading, or a sync that fails over data
 * already on the phone, never does. The one exception is a first install that came with a catalog: it
 * waits for its first sync, so it opens on the latest apps and not on those of the day the APK was built.
 */
sealed interface CatalogGate {
    /** The first read of the database has not landed. Nothing is drawn. */
    data object Unknown : CatalogGate

    /** There is app data. The app opens. */
    data object Ready : CatalogGate

    /**
     * A first sync is on its way: connecting, downloading, or importing. [catchUp] is set when the phone
     * already holds the catalog the APK came with and the sync brings it up to date.
     */
    data class Loading(
        val phase: SyncPhase,
        val receivedBytes: Long,
        val totalBytes: Long,
        val catchUp: Boolean = false,
    ) : CatalogGate {
        /** 0 to 1 while the bundle's length is known; null when it is not, or there is nothing to measure yet. */
        val fraction: Float?
            get() = if (phase == SyncPhase.Downloading && totalBytes > 0) {
                (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            } else {
                null
            }
    }

    /** The first sync failed. [error] is the cause, for the diagnostics, not for a sentence. */
    data class Failed(val error: String, val catchUp: Boolean = false) : CatalogGate

    /** The list holds no catalog and was decided that way. Nothing syncs until one is added. */
    data object NoCatalog : CatalogGate
}

/**
 * The gate for one reading of the app's state. [appCount] is null until the first read lands.
 * [emptyConfirmed] is asked only when a sync finished without error and found no apps: it re-reads the
 * count, because the commit that wrote the apps can be a moment ahead of the count this reader holds.
 */
internal fun catalogGate(
    appCount: Int?,
    noCatalog: Boolean,
    status: SyncStatus,
    emptyConfirmed: () -> Boolean,
): CatalogGate = when {
    appCount == null -> CatalogGate.Unknown
    // The APK's catalog is on the phone but no sync has brought it up to date: a first install waits for the latest apps.
    appCount > 0 && status.catchingUp -> catchingUpGate(status)
    appCount > 0 -> CatalogGate.Ready
    noCatalog -> CatalogGate.NoCatalog
    status.syncing -> CatalogGate.Loading(status.phase, status.receivedBytes, status.totalBytes)
    status.error != null -> CatalogGate.Failed(status.error)
    // A catalog that synced cleanly and has no apps is empty, not late. Holding the user here would be a trap.
    status.lastSyncedAtMillis != null && emptyConfirmed() -> CatalogGate.Ready
    else -> CatalogGate.Loading(status.phase, status.receivedBytes, status.totalBytes)
}

/**
 * The wait for a first sync over the APK's catalog. Data is there, so nothing here is a trap: a failure
 * offers the way on, and an error shows only once the pass that failed has stopped.
 */
private fun catchingUpGate(status: SyncStatus): CatalogGate = when {
    !status.syncing && status.error != null -> CatalogGate.Failed(status.error, catchUp = true)
    else -> CatalogGate.Loading(status.phase, status.receivedBytes, status.totalBytes, catchUp = true)
}

/** [catalogGate], kept current from the database and the sync. Eagerly started, so the first frame has an answer soon. */
fun catalogGateFlow(iolite: Iolite, sync: CatalogSync, scope: CoroutineScope): StateFlow<CatalogGate> {
    val count: Flow<Int?> = iolite.observeAppCount()
    val noCatalog: Flow<Boolean> = iolite.observeCatalogs()
        .map { catalogs -> catalogs.isEmpty() && !iolite.needsDefaultCatalog() }
    return combine(count, noCatalog, sync.status) { apps, none, status ->
        catalogGate(apps, none, status) { iolite.appCount() == 0 }
    }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, CatalogGate.Unknown)
}
