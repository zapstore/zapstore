package dev.zapstore.app.install

import android.content.Context
import java.io.File

/** One app's file on disk: a download in progress, or a verified APK waiting for Android. */
data class Artifact(val appId: String, val hash: String, val verified: Boolean, val bytes: Long)

/**
 * Downloads live in `partial/<appId>@<hash>.part` until the hash matches, then move to
 * `apk/<appId>@<hash>.apk`. Every file belongs to one [InstallTask]; dropping the task deletes
 * them. [sweep] removes what an older layout or a crash left behind.
 */
object InstallCache {
    fun partial(context: Context, appId: String, hash: String): File =
        File(dir(context, PARTIAL), "${name(appId, hash)}.part")

    fun apk(context: Context, appId: String, hash: String): File =
        File(dir(context, APK), "${name(appId, hash)}.apk")

    /** What is on disk, one entry per app; a verified APK wins over a partial of the same build. */
    fun artifacts(context: Context): List<Artifact> {
        val found = listOf(APK to true, PARTIAL to false).flatMap { (folder, verified) ->
            dir(context, folder).listFiles().orEmpty().mapNotNull { file ->
                val (appId, hash) = file.nameWithoutExtension.split('@', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                Artifact(appId, hash, verified, file.length())
            }
        }
        return found.groupBy { it.appId }.map { (_, files) -> files.firstOrNull { it.verified } ?: files.first() }
    }

    /** Deletes every file of [appId], or all but the build [keepHash]. */
    fun delete(context: Context, appId: String, keepHash: String? = null) {
        val keep = keepHash?.let { name(appId, it) }
        listOf(APK, PARTIAL).forEach { folder ->
            dir(context, folder).listFiles().orEmpty()
                .filter { it.name.startsWith("${appId.safeId()}@") && it.nameWithoutExtension != keep }
                .forEach(File::delete)
        }
    }

    /**
     * Drops earlier layouts, files that name no app, more than one build per app, and anything
     * untouched for [MAX_AGE_MS].
     */
    fun sweep(context: Context) {
        File(context.cacheDir, "install").deleteRecursively()
        File(context.noBackupFilesDir, "ready").deleteRecursively()
        val stale = System.currentTimeMillis() - MAX_AGE_MS
        listOf(APK, PARTIAL).forEach { folder ->
            dir(context, folder).listFiles()?.forEach { file ->
                if ('@' !in file.name || file.lastModified() < stale) file.delete()
            }
        }
        artifacts(context).forEach { delete(context, it.appId, keepHash = it.hash) }
    }

    private fun name(appId: String, hash: String) = "${appId.safeId()}@${hash.safeHash()}"

    private fun dir(context: Context, name: String) = File(context.noBackupFilesDir, name).apply { mkdirs() }

    private fun String.safeId() = replace(Regex("[^A-Za-z0-9._]"), "_")

    private fun String.safeHash() = lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

    private const val APK = "apk"
    private const val PARTIAL = "partial"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
}
