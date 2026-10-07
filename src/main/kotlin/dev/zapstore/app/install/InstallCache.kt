package dev.zapstore.app.install

import android.content.Context
import java.io.File

object InstallCache {
    fun file(context: Context, packageId: String): File {
        val dir = File(context.cacheDir, "install").apply { mkdirs() }
        val safe = packageId.replace(Regex("[^A-Za-z0-9._]"), "_")
        return File(dir, "$safe.apk")
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
    }
}
