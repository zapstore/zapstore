package dev.zapstore.app.transport

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class EmbeddedArtiEngine(
    context: Context,
) : ArtiEngine {
    private val dataDir = File(context.applicationContext.filesDir, "arti")
    private val port = AtomicInteger(0)

    override val socksPort: Int get() = port.get()

    @Synchronized
    override fun start() {
        if (socksPort > 0) return
        dataDir.mkdirs()
        val initialized = ArtiNative.initialize(dataDir.absolutePath)
        if (initialized != 0) {
            throw TorUnavailableException("Arti initialize failed ($initialized)")
        }
        var candidate = DEFAULT_SOCKS_PORT
        repeat(MAX_PORT_RETRIES) {
            if (ArtiNative.startSocksProxy(candidate) == 0) {
                port.set(candidate)
                return
            }
            candidate++
        }
        throw TorUnavailableException("Arti did not bind a SOCKS port")
    }

    @Synchronized
    override fun stop() {
        runCatching { ArtiNative.stopSocksProxy() }
        port.set(0)
    }

    @Synchronized
    override fun destroy() {
        stop()
        runCatching { ArtiNative.destroy() }
    }

    override fun close() = destroy()

    companion object {
        private const val DEFAULT_SOCKS_PORT = 17392
        private const val MAX_PORT_RETRIES = 10

        fun cacheDir(context: Context): File = File(context.applicationContext.filesDir, "arti/cache")
    }
}

/** Deletes Arti's disposable directory cache. Safe only after [ArtiEngine.destroy]. */
internal fun deleteArtiCache(cacheDir: File) {
    cacheDir.deleteRecursively()
    cacheDir.mkdirs()
}
