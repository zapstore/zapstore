package dev.zapstore.iolite

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The snapshot that ships in the APK, and what a screen waiting on a sync is told. */
class CatalogSnapshotTest {
    private val relay = "wss://relay.example".normalizeRelayUrl()

    @Test
    fun theSnapshotImportsWithNoNetwork() = withIolite(http = null) { iolite ->
        val bundle = CatalogBundle.of(CatalogImporterTest.goldenBundle("bundle-0-1.tar.zst"))
        val catalog = iolite.seedDefaultCatalog(iolite.bundleSigner(bundle)!!, relay)!!
        assertEquals(0, iolite.appCount())

        val progress = Recorder()
        val imported = iolite.importBundledSnapshot(catalog.id, bundle, relay, progress)

        assertNotNull(imported)
        assertTrue(iolite.appCount() > 0)
        assertTrue(iolite.allCatalogs().single { it.id == catalog.id }.epoch > 0)
        assertEquals(listOf("importing"), progress.events)
    }

    @Test
    fun aCatalogThatAlreadyHasAnEpochIsNotImportedAgain() = withIolite(http = null) { iolite ->
        val bundle = CatalogBundle.of(CatalogImporterTest.goldenBundle("bundle-0-1.tar.zst"))
        val catalog = iolite.seedDefaultCatalog(iolite.bundleSigner(bundle)!!, relay)!!
        assertNotNull(iolite.importBundledSnapshot(catalog.id, bundle, relay))

        val progress = Recorder()
        assertNull(iolite.importBundledSnapshot(catalog.id, bundle, relay, progress))
        // Nothing was done, so nothing is reported: a screen must not flash "setting up" on every launch.
        assertTrue(progress.events.isEmpty())
    }

    @Test
    fun aDownloadReportsItsBytesAndThenTheImport() {
        val bytes = CatalogImporterTest.goldenBundle("bundle-0-1.tar.zst")
        val http = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("bundles are downloaded")

            override fun download(
                url: String,
                headers: Map<String, String>,
                destination: File,
                maxBytes: Long,
                onProgress: (received: Long, total: Long) -> Unit,
            ): HttpResponse {
                onProgress(0, bytes.size.toLong())
                destination.writeBytes(bytes)
                onProgress(bytes.size.toLong(), bytes.size.toLong())
                return HttpResponse(200, emptyMap(), null)
            }
        }
        withIolite(http) { iolite ->
            val progress = Recorder()
            val result = iolite.syncCatalog(catalogId = 1, endpoint = relay, progress = progress)

            assertNotNull(result.imported)
            assertTrue(iolite.appCount() > 0)
            // Bytes first, the import after: the screen moves from a bar to "setting up", never back.
            assertEquals(listOf("downloading 0/${bytes.size}", "downloading ${bytes.size}/${bytes.size}", "importing"), progress.events)
        }
    }

    @Test
    fun aNotModifiedAnswerReportsNoImport() {
        val http = HttpTransport { _, _ -> HttpResponse(304, emptyMap(), null) }
        withIolite(http) { iolite ->
            val bundle = CatalogBundle.of(CatalogImporterTest.goldenBundle("bundle-0-1.tar.zst"))
            val catalog = iolite.seedDefaultCatalog(iolite.bundleSigner(bundle)!!, relay)!!
            iolite.importBundledSnapshot(catalog.id, bundle, relay)

            val progress = Recorder()
            val result = iolite.syncCatalog(catalogId = catalog.id, progress = progress)

            assertTrue(result.notModified)
            assertTrue("a 304 has nothing to import", "importing" !in progress.events)
        }
    }

    private class Recorder : CatalogProgress {
        val events = mutableListOf<String>()

        override fun downloading(received: Long, total: Long) {
            events += "downloading $received/$total"
        }

        override fun importing() {
            events += "importing"
        }
    }

    private fun <T> withIolite(http: HttpTransport?, block: (Iolite) -> T): T {
        val dir = File("build/tmp/iolite-snapshot-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            http = http,
            openConnection = ::JdbcSqliteConnection,
        )
        try {
            return block(iolite)
        } finally {
            iolite.close()
            scope.coroutineContext.job.cancel()
            dir.deleteRecursively()
        }
    }
}
