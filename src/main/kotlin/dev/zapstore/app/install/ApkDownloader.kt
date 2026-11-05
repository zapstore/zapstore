package dev.zapstore.app.install

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.zapstore.app.AppLog
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class InstallException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { Space, Http, Hash, Redirect, Permission, Other }
}

object ApkDownloader {
    private const val TAG = "ApkDownloader"
    const val MAX_BYTES = 300L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val UNKNOWN_SPACE = 64L * 1024 * 1024

    fun urls(primary: String?, hash: String): List<String> {
        val cdn = "https://cdn.zapstore.dev/${hash.lowercase()}"
        val first = primary?.trim()?.takeIf { it.isNotEmpty() }
        if (first == null || first.equals(cdn, ignoreCase = true)) return listOf(cdn)
        return listOf(first, cdn)
    }

    /**
     * Tries [urls] in order. Each call follows redirects itself. The body is kept only when
     * its SHA-256 matches [expectedHash]. A partial file is resumed with `Range` when the
     * server returns 206. Every URL serves the same bytes, so a partial from one resumes on the
     * next. A network failure or cancellation keeps the partial; any other failure deletes it.
     */
    suspend fun download(
        calls: Call.Factory,
        urls: List<String>,
        dest: File,
        expectedHash: String,
        freeBytes: (File) -> Long = { it.usableSpace },
        onProgress: (Long, Long?) -> Unit = { _, _ -> },
    ) {
        if (urls.isEmpty()) throw InstallException(InstallException.Kind.Http, "no download url")
        var last: Exception? = null
        for (url in urls) {
            try {
                fetch(calls, url, dest, expectedHash, freeBytes, onProgress)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!e.isTransient()) dest.delete()
                if (!currentCoroutineContext().isActive) throw e
                AppLog.w(TAG, "download from ${url.toHttpUrlOrNull()?.host ?: "invalid url"} failed: ${(e as? InstallException)?.kind ?: e::class.simpleName} ${e.message}")
                last = e
            }
        }
        throw last ?: InstallException(InstallException.Kind.Http, "no download url")
    }

    private suspend fun fetch(
        calls: Call.Factory,
        startUrl: String,
        dest: File,
        expectedHash: String,
        freeBytes: (File) -> Long,
        onProgress: (Long, Long?) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            var url = startUrl
            var hops = 0
            var allowRange = dest.exists() && dest.length() > 0
            var rejectedRange = false
            while (true) {
                val existing = if (allowRange && dest.exists()) dest.length() else 0L
                val builder = Request.Builder().url(url)
                if (existing > 0) builder.header("Range", "bytes=$existing-")
                val call = calls.newCall(builder.build())
                val done = call.executeCancellable { response ->
                    if (response.isRedirect) {
                        hops++
                        if (hops > MAX_REDIRECTS) {
                            throw InstallException(InstallException.Kind.Redirect, "too many redirects")
                        }
                        val location = response.header("Location")
                            ?: throw InstallException(InstallException.Kind.Redirect, "redirect without location")
                        url = response.request.url.resolve(location)?.toString()
                            ?: throw InstallException(InstallException.Kind.Redirect, "bad redirect")
                        allowRange = false
                        dest.delete()
                        return@executeCancellable false
                    }
                    if (response.code == 416) {
                        if (rejectedRange) throw InstallException(InstallException.Kind.Http, "HTTP 416")
                        rejectedRange = true
                        allowRange = false
                        dest.delete()
                        return@executeCancellable false
                    }
                    if (response.code != 200 && response.code != 206) {
                        throw InstallException(InstallException.Kind.Http, "HTTP ${response.code}")
                    }
                    val append = response.code == 206 && existing > 0
                    if (!append) dest.delete()
                    val body = response.body
                    val incoming = body.contentLength()
                    if (incoming > MAX_BYTES) {
                        throw InstallException(InstallException.Kind.Http, "APK is too large")
                    }
                    val needed = if (incoming >= 0) incoming * 2 else UNKNOWN_SPACE
                    val parent = dest.parentFile ?: dest
                    if (freeBytes(parent) < needed) {
                        throw InstallException(InstallException.Kind.Space, "not enough free space")
                    }
                    val total = if (incoming >= 0) (if (append) existing else 0L) + incoming else null
                    val hash = write(body.byteStream(), dest, append, total, onProgress)
                    if (!hash.equals(expectedHash, ignoreCase = true)) {
                        throw InstallException(InstallException.Kind.Hash, "hash mismatch")
                    }
                    true
                }
                if (done) return@withContext
            }
        }
    }

    /**
     * Runs [block] on the response and cancels the call as soon as the coroutine is cancelled, so a
     * blocking body read ends on Pause or Cancel instead of when the transfer finishes.
     */
    private suspend fun <T> Call.executeCancellable(block: (Response) -> T): T = coroutineScope {
        val call = this@executeCancellable
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use(block)
        } finally {
            watcher.cancel()
        }
    }

    /** Streams [input] onto [dest] and returns the SHA-256 of the whole file, resumed bytes included. */
    private fun write(
        input: java.io.InputStream,
        dest: File,
        append: Boolean,
        total: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = if (append && dest.exists()) dest.digestInto(digest) else 0L
        input.use { source ->
            FileOutputStream(dest, append).use { file ->
                val out = BufferedOutputStream(file, WRITE_BUFFER)
                val buffer = ByteArray(READ_BUFFER)
                try {
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > MAX_BYTES) {
                            throw InstallException(InstallException.Kind.Http, "APK is too large")
                        }
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        onProgress(written, total)
                    }
                } finally {
                    // A paused or dropped download keeps every byte that arrived, for the Range resume.
                    out.flush()
                }
                file.fd.sync()
            }
        }
        return digest.hex()
    }

    private const val READ_BUFFER = 64 * 1024
    private const val WRITE_BUFFER = 512 * 1024
}

/** A dropped or timed-out connection, worth retrying later. */
fun Throwable.isTransient(): Boolean = this is IOException

/** Feeds this file to [digest] and returns its length. */
private fun File.digestInto(digest: MessageDigest): Long {
    var length = 0L
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            length += read
        }
    }
    return length
}

private fun MessageDigest.hex(): String = digest().joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
