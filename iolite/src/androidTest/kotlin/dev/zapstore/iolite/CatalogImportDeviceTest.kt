package dev.zapstore.iolite

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A full catalog import under ART's real heap limit and the bundled SQLite. Copy an epoch 0 bundle into
 * the test app first:
 * `adb shell 'run-as dev.zapstore.iolite.test sh -c "cat > files/catalog-bundle.tar.zst"' < tests/fixtures/data/bundles/0-15.tar.zst`.
 * Skipped when it is not there.
 */
@RunWith(AndroidJUnit4::class)
class CatalogImportDeviceTest {
    @Test
    fun fullCatalogImportStaysWithinHeapBudget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.cacheDir, "catalog-import-${System.nanoTime()}").apply { mkdirs() }
        val bundle = File(instrumentation.targetContext.filesDir, BUNDLE)
        assumeTrue("copy a bundle to files/$BUNDLE", bundle.length() > 0)

        val store = IoliteStore(File(dir, "iolite.db").absolutePath)
        val importer = CatalogImporter(store, File(dir, "icons"), IoliteConfig(), DeviceProfile(abis = listOf("arm64-v8a"), sdk = 34))
        try {
            val runtime = Runtime.getRuntime()
            val baseline = liveHeap()
            val peak = AtomicLong(baseline)
            val running = AtomicBoolean(true)
            val sampler = Thread {
                while (running.get()) {
                    peak.accumulateAndGet(liveHeap(), ::maxOf)
                    Thread.sleep(25)
                }
            }.apply { start() }
            val started = System.nanoTime()
            val result = try {
                importer.importBundle(1, CatalogBundle.of(bundle), "wss://relay.zapstore.dev".normalizeRelayUrl())
            } finally {
                running.set(false)
                sampler.join()
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            val used = (peak.get() - baseline) / MIB
            val apps = store.appCount()
            Log.i(TAG, "imported $apps apps to epoch ${result.to} in ${millis}ms; ${bundle.length() / MIB} MiB compressed; " +
                "peak live heap +$used MiB of ${runtime.maxMemory() / MIB} MiB; skipped ${result.skipped.size}")
            assertTrue("expected a full catalog, got $apps apps", apps > 4_000)
            assertTrue("peak live heap grew by $used MiB", used <= BUDGET_MIB)
        } finally {
            store.close()
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val TAG = "CatalogImportDeviceTest"
        const val BUNDLE = "catalog-bundle.tar.zst"
        const val MIB = 1024L * 1024
        const val BUDGET_MIB = 32L

        fun liveHeap(): Long {
            val runtime = Runtime.getRuntime()
            runtime.gc()
            return runtime.totalMemory() - runtime.freeMemory()
        }
    }
}
