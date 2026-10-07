package dev.zapstore.app.install

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class InstallException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { Space, Http, Hash, Redirect, Permission, Other }
}

object ApkDownloader {
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
     * server returns 206.
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
                dest.delete()
                throw e
            } catch (e: Exception) {
                dest.delete()
                if (!currentCoroutineContext().isActive) throw e
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
                call.timeout().timeout(10, java.util.concurrent.TimeUnit.MINUTES)
                currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) call.cancel()
                }
                call.execute().use { response ->
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
                        return@use
                    }
                    if (response.code == 416) {
                        if (rejectedRange) throw InstallException(InstallException.Kind.Http, "HTTP 416")
                        rejectedRange = true
                        allowRange = false
                        dest.delete()
                        return@use
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
                    write(body.byteStream(), dest, append, total, onProgress)
                    val hash = dest.sha256()
                    if (!hash.equals(expectedHash, ignoreCase = true)) {
                        throw InstallException(InstallException.Kind.Hash, "hash mismatch")
                    }
                    return@withContext
                }
            }
        }
    }

    private fun write(
        input: java.io.InputStream,
        dest: File,
        append: Boolean,
        total: Long?,
        onProgress: (Long, Long?) -> Unit,
    ) {
        var written = if (append && dest.exists()) dest.length() else 0L
        input.use { source ->
            FileOutputStream(dest, append).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    if (written > MAX_BYTES) {
                        throw InstallException(InstallException.Kind.Http, "APK is too large")
                    }
                    onProgress(written, total)
                }
                out.fd.sync()
            }
        }
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
