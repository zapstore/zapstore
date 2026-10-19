package dev.zapstore.app.install

import android.content.Context
import java.io.File

object InstallCache {
    fun file(context: Context, packageId: String): File {
        val dir = File(context.cacheDir, "install").apply { mkdirs() }
        val safe = packageId.replace(Regex("[^A-Za-z0-9._]"), "_")
        return File(dir, "$safe.apk")
    }

    /** A verified APK the background worker downloaded but could not install without a prompt. Survives [sweep]. */
    fun ready(context: Context, packageId: String): File {
        val dir = File(context.noBackupFilesDir, "ready").apply { mkdirs() }
        return File(dir, file(context, packageId).name)
    }

    /**
     * The download in progress for the APK with SHA-256 [hash]. A network failure keeps it so the
     * next attempt resumes with `Range`; it survives [sweep] and is pruned after [PARTIAL_MAX_AGE_MS].
     */
    fun partial(context: Context, hash: String): File {
        val dir = File(context.noBackupFilesDir, "partial").apply { mkdirs() }
        return File(dir, "${hash.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }}.part")
    }

    /**
     * A pending session's confirmation intent dies with the process. Drop those sessions
     * and the cached APKs so the next tap starts clean.
     */
    fun sweep(context: Context) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { session ->
            runCatching { installer.abandonSession(session.sessionId) }
        }
        File(context.cacheDir, "install").deleteRecursively()
        val stale = System.currentTimeMillis() - PARTIAL_MAX_AGE_MS
        File(context.noBackupFilesDir, "partial").listFiles()?.forEach { if (it.lastModified() < stale) it.delete() }
    }

    private const val PARTIAL_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
}
