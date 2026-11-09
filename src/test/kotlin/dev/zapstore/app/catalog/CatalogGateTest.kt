package dev.zapstore.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogGateTest {
    private val idle = SyncStatus()

    private fun gate(
        appCount: Int?,
        status: SyncStatus = idle,
        noCatalog: Boolean = false,
        emptyConfirmed: Boolean = true,
    ) = catalogGate(appCount, noCatalog, status) { emptyConfirmed }

    @Test
    fun nothingIsDecidedBeforeTheFirstRead() {
        assertEquals(CatalogGate.Unknown, gate(appCount = null))
        // Not even a failing sync: with no read, an existing user must not see an error screen flash by.
        assertEquals(CatalogGate.Unknown, gate(appCount = null, status = SyncStatus(error = "boom")))
    }

    @Test
    fun anyAppDataOpensTheApp() {
        assertEquals(CatalogGate.Ready, gate(appCount = 1))
        // A failing sync over data already on the phone is not a reason to close the app.
        assertEquals(CatalogGate.Ready, gate(appCount = 1200, status = SyncStatus(error = "HTTP 502")))
        // Nor is a second catalog downloading, or a first one being refreshed.
        assertEquals(CatalogGate.Ready, gate(appCount = 1200, status = SyncStatus(syncing = true, phase = SyncPhase.Downloading)))
    }

    @Test
    fun anEmptyDatabaseWaitsForTheFirstSync() {
        val loading = gate(appCount = 0)
        assertEquals(CatalogGate.Loading(SyncPhase.Idle, 0, -1), loading)

        val connecting = gate(0, SyncStatus(syncing = true, phase = SyncPhase.Connecting))
        assertEquals(SyncPhase.Connecting, (connecting as CatalogGate.Loading).phase)

        val downloading = gate(0, SyncStatus(syncing = true, phase = SyncPhase.Downloading, receivedBytes = 5, totalBytes = 20))
        assertEquals(CatalogGate.Loading(SyncPhase.Downloading, 5, 20), downloading)
        assertEquals(0.25f, (downloading as CatalogGate.Loading).fraction!!, 0.0001f)

        val importing = gate(0, SyncStatus(syncing = true, phase = SyncPhase.Importing))
        assertEquals(SyncPhase.Importing, (importing as CatalogGate.Loading).phase)
        assertNull(importing.fraction)
    }

    @Test
    fun theBarMeasuresOnlyWhenTheLengthIsKnown() {
        // No Content-Length: bytes arrive, but there is nothing to divide by.
        assertNull(CatalogGate.Loading(SyncPhase.Downloading, 5_000, -1).fraction)
        assertNull(CatalogGate.Loading(SyncPhase.Downloading, 0, 0).fraction)
        // Connecting reports no bytes, so no bar even if a stale length is around.
        assertNull(CatalogGate.Loading(SyncPhase.Connecting, 0, 20).fraction)
        // A server that sends more than it promised does not push the bar past the end.
        assertEquals(1f, CatalogGate.Loading(SyncPhase.Downloading, 30, 20).fraction!!, 0f)
    }

    @Test
    fun aFailedFirstSyncSaysSoOnlyOnceItHasStopped() {
        assertEquals(CatalogGate.Failed("timeout"), gate(0, SyncStatus(error = "timeout")))
        // Retry clears the error and sets syncing, which is loading again, not a stale failure.
        assertTrue(gate(0, SyncStatus(syncing = true, error = null)) is CatalogGate.Loading)
        // While the failed pass is still unwinding, the screen does not flash its error early.
        assertTrue(gate(0, SyncStatus(syncing = true, error = "timeout")) is CatalogGate.Loading)
    }

    @Test
    fun aListThatWasDecidedEmptyIsTheNoCatalogScreen() {
        assertEquals(CatalogGate.NoCatalog, gate(0, noCatalog = true))
        // A failing sync does not turn "no catalog" into "failed": there is nothing to retry.
        assertEquals(CatalogGate.NoCatalog, gate(0, SyncStatus(error = "boom"), noCatalog = true))
    }

    @Test
    fun aCatalogThatSyncedCleanlyAndHasNoAppsIsEmptyNotLate() {
        val synced = SyncStatus(lastSyncedAtMillis = 1_000)
        assertEquals(CatalogGate.Ready, gate(0, synced))
        // The count this reader holds can trail the commit; the re-read says the apps are there.
        assertTrue(gate(0, synced, emptyConfirmed = false) is CatalogGate.Loading)
        // After Clear local storage the last sync is forgotten, so an empty database is loading again.
        assertTrue(gate(0, SyncStatus(lastSyncedAtMillis = null)) is CatalogGate.Loading)
    }

    @Test
    fun aCatalogFromTheApkWaitsForItsFirstSync() {
        val waiting = SyncStatus(catchingUp = true)
        // Data is there, yet the app does not open on the build date's apps.
        assertEquals(CatalogGate.Loading(SyncPhase.Idle, 0, -1, catchUp = true), gate(1200, waiting))
        val downloading = gate(1200, SyncStatus(syncing = true, phase = SyncPhase.Downloading, receivedBytes = 5, totalBytes = 20, catchingUp = true))
        assertEquals(CatalogGate.Loading(SyncPhase.Downloading, 5, 20, catchUp = true), downloading)
        assertEquals(0.25f, (downloading as CatalogGate.Loading).fraction!!, 0.0001f)
    }

    @Test
    fun theFirstCleanSyncOpensTheAppOnTheLatestApps() {
        assertEquals(CatalogGate.Ready, gate(1200, SyncStatus(catchingUp = false, lastSyncedAtMillis = 1_000)))
    }

    @Test
    fun aFailedCatchUpOffersTheWayOnOnlyOnceItHasStopped() {
        assertEquals(CatalogGate.Failed("timeout", catchUp = true), gate(1200, SyncStatus(error = "timeout", catchingUp = true)))
        assertTrue(gate(1200, SyncStatus(syncing = true, error = "timeout", catchingUp = true)) is CatalogGate.Loading)
    }

    @Test
    fun anEmptyDatabaseIsNeverACatchUp() {
        // No data: the ordinary first-download screen, which may clear and start over.
        assertEquals(CatalogGate.Loading(SyncPhase.Idle, 0, -1), gate(0, SyncStatus(catchingUp = true)))
    }
}
