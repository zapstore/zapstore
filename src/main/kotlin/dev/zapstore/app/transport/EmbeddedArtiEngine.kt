package dev.zapstore.app.transport

import android.content.Context
import java.io.File

class EmbeddedArtiEngine(
    context: Context,
) : ArtiEngine {
    private val dataDir = File(context.applicationContext.filesDir, "arti")
    @Volatile private var started = false

    override val streams: TorStreams? get() = if (started) ArtiStreams else null

    @Synchronized
    override fun start() {
        if (started) return
        dataDir.mkdirs()
        val initialized = ArtiNative.initialize(dataDir.absolutePath)
        if (initialized != 0) {
            throw TorUnavailableException("Arti initialize failed ($initialized)")
        }
        started = true
    }

    @Synchronized
    override fun destroy() {
        started = false
        runCatching { ArtiNative.destroy() }
    }

    override fun close() = destroy()

    companion object {
        fun cacheDir(context: Context): File = File(context.applicationContext.filesDir, "arti/cache")
    }
}

/** Deletes Arti's disposable directory cache. Safe only after [ArtiEngine.destroy]. */
internal fun deleteArtiCache(cacheDir: File) {
    cacheDir.deleteRecursively()
    cacheDir.mkdirs()
}
