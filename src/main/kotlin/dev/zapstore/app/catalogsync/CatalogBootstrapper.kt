package dev.zapstore.app.catalogsync

import android.content.Context
import java.io.File

class CatalogBootstrapper(
    private val context: Context,
    private val databaseName: String = CatalogSchema.DATABASE_NAME,
    private val bundledAsset: String = CatalogSchema.BUNDLED_ASSET,
) {
    fun open(): ZapstoreDatabase {
        val target = context.getDatabasePath(databaseName)
        if (!target.exists()) {
            copyBundled(target)
        }
        return if (target.exists()) {
            val database = ZapstoreDatabase.open(target.absolutePath)
            if (database.catalogState() == null) {
                database.writeCatalogState(emptyState())
            }
            database
        } else {
            ZapstoreDatabase.createEmpty(target.absolutePath)
        }
    }

    private fun copyBundled(target: File) {
        val copied = runCatching {
            context.assets.open(bundledAsset).use { input ->
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "${target.name}.copy")
                temp.outputStream().use { input.copyTo(it) }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
        }
        copied.exceptionOrNull()
    }

    private fun emptyState() = CatalogState(
        catalog = CatalogSchema.CATALOG,
        epoch = 0,
        schemaVersion = CatalogSchema.VERSION,
        searchModel = CatalogSchema.SEARCH_MODEL,
        generation = 0,
    )
}
