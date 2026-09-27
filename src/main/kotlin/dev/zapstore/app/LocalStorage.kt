package dev.zapstore.app

import android.content.Context
import java.io.File

/** Bytes used by this app's private data, caches, and external app directories. */
fun Context.localStorageBytes(): Long {
    val existing = buildList {
        add(dataDir)
        add(cacheDir)
        add(codeCacheDir)
        add(noBackupFilesDir)
        add(filesDir)
        externalCacheDir?.let(::add)
        getExternalFilesDir(null)?.let(::add)
        externalCacheDirs.filterNotNull().forEach(::add)
        getExternalFilesDirs(null).filterNotNull().forEach(::add)
    }.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .filter(File::exists)
        .distinct()
    val roots = existing.filter { dir ->
        existing.none { other -> other != dir && dir.path.startsWith(other.path + File.separator) }
    }
    return roots.sumOf(File::treeSize)
}

private fun File.treeSize(): Long {
    if (isFile) return length()
    return listFiles()?.sumOf(File::treeSize) ?: 0L
}
