package dev.zapstore.iolite

import com.sun.management.GarbageCollectionNotificationInfo
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.concurrent.atomic.AtomicLong
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.openmbean.CompositeData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Peak live heap of a full production import: the newest epoch 0 bundle in `tests/fixtures/data/bundles`
 * (`make pull-data` in `infra`). Skipped when that archive is not unpacked. `-Piolite.testMaxHeap=64m`
 * also caps the whole test JVM, so a regression fails with `OutOfMemoryError` as the phone would.
 */
class CatalogImportMemoryTest {
    /** The APK's bundled snapshot: streamed from the asset on each pass. */
    @Test
    fun streamedBundleStaysWithinHeapBudget() {
        val file = productionBundle()
        assumeTrue("production bundle is not unpacked", file != null)
        measureImport(file!!, BUDGET_MIB) { CatalogBundle { file.inputStream().buffered() } }
    }

    /** A fresh install without a snapshot: `GET /v1/bundle?from=0`, streamed to disk as OkHttp does. */
    @Test
    fun downloadedBundleStaysWithinHeapBudget() {
        val file = productionBundle()
        assumeTrue("production bundle is not unpacked", file != null)
        val http = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse = error("bundles are downloaded")

            override fun download(url: String, headers: Map<String, String>, destination: File, maxBytes: Long): HttpResponse {
                file!!.inputStream().use { input -> destination.outputStream().use(input::copyTo) }
                return HttpResponse(200, emptyMap(), null)
            }
        }
        val dir = File("build/tmp/iolite-memory-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            http = http,
            openConnection = ::JdbcSqliteConnection,
        )
        try {
            measure(file!!, { iolite.apps().size to iolite.defaultCatalog().epoch }) {
                iolite.syncCatalog(catalogId = 1, endpoint = "wss://relay.zapstore.dev".normalizeRelayUrl())
            }
            assertTrue("downloads are deleted", File(dir, "downloads").listFiles().orEmpty().isEmpty())
        } finally {
            iolite.close()
            scope.coroutineContext.job.cancel()
            dir.deleteRecursively()
        }
    }

    private fun measureImport(file: File, budgetMib: Long, bundle: () -> CatalogBundle) {
        val dir = File("build/tmp/iolite-memory-${System.nanoTime()}").apply { mkdirs() }
        val store = jdbcStore(File(dir, "iolite.db").absolutePath)
        val importer = CatalogImporter(
            store = store,
            iconsDir = File(dir, "icons"),
            config = IoliteConfig(),
            device = DeviceProfile(abis = listOf("arm64-v8a"), sdk = 34),
        )
        try {
            measure(file, { store.apps().size to store.catalog(1)!!.epoch }, budgetMib) {
                val source = bundle()
                assertTrue(importer.manifestSigner(source) != null)
                importer.importBundle(1, source, "wss://relay.zapstore.dev".normalizeRelayUrl())
            }
        } finally {
            store.close()
            dir.deleteRecursively()
        }
    }

    /** Runs [import] and checks the live heap it added; [stored] is the app count and epoch afterwards. */
    private fun measure(file: File, stored: () -> Pair<Int, Long>, budgetMib: Long = BUDGET_MIB, import: () -> Unit) {
        val baseline = liveHeap()
        val peak = PeakLiveHeap().use { peak ->
            import()
            peak.max()
        }
        val (apps, epoch) = stored()
        val used = (peak - baseline).coerceAtLeast(0) / MIB
        println("catalog import ${file.name}: $apps apps, ${file.length() / MIB} MiB compressed, peak live heap +$used MiB")
        assertTrue("expected a full catalog, got $apps apps", apps > 4_000)
        assertTrue(epoch > 0)
        assertTrue("peak live heap grew by $used MiB, budget is $budgetMib MiB", used <= budgetMib)
    }

    /**
     * Largest live heap seen while open. A thread forces a full collection every few milliseconds and
     * only full collections are read: after a young one, dead objects in the old generation still count.
     */
    private class PeakLiveHeap : AutoCloseable {
        private val peak = AtomicLong(0)
        private val emitters = ManagementFactory.getGarbageCollectorMXBeans().filterIsInstance<NotificationEmitter>()
        private val listener = NotificationListener { notification, _ ->
            if (notification.type != GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) return@NotificationListener
            val info = GarbageCollectionNotificationInfo.from(notification.userData as CompositeData)
            if (info.gcAction != "end of major GC") return@NotificationListener
            val after = info.gcInfo.memoryUsageAfterGc
            peak.accumulateAndGet(heapPools.sumOf { after[it]?.used ?: 0L }, ::maxOf)
        }
        @Volatile private var running = true
        private val sampler = Thread {
            while (running) {
                System.gc()
                Thread.sleep(SAMPLE_MS)
            }
        }.apply { isDaemon = true }

        init {
            emitters.forEach { it.addNotificationListener(listener, null, null) }
            sampler.start()
        }

        fun max(): Long {
            peak.accumulateAndGet(liveHeap(), ::maxOf)
            return peak.get()
        }

        override fun close() {
            running = false
            sampler.join()
            emitters.forEach { runCatching { it.removeNotificationListener(listener) } }
        }
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val SAMPLE_MS = 20L

        /** Live heap the import itself may add. The emulator gives the whole app 256 MB. */
        const val BUDGET_MIB = 24L

        val heapPools: List<String> = ManagementFactory.getMemoryPoolMXBeans()
            .filter { it.type == MemoryType.HEAP }
            .map { it.name }

        fun liveHeap(): Long {
            repeat(2) { System.gc() }
            val runtime = Runtime.getRuntime()
            return runtime.totalMemory() - runtime.freeMemory()
        }

        fun productionBundle(): File? {
            var dir: File? = File(System.getProperty("user.dir") ?: return null).canonicalFile
            repeat(6) {
                val bundles = dir?.let { File(it, "tests/fixtures/data/bundles") }
                if (bundles?.isDirectory == true) {
                    return bundles.listFiles { file -> file.name.matches(Regex("0-\\d+\\.tar\\.zst")) }
                        ?.maxByOrNull { it.name.removePrefix("0-").substringBefore('.').toInt() }
                }
                dir = dir?.parentFile
            }
            return null
        }
    }
}
