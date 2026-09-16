package dev.zapstore.app.catalogsync

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CatalogState(
    val catalog: String,
    val epoch: Long,
    val schemaVersion: Int,
    val searchModel: String,
    val generation: Long,
)

class ZapstoreDatabase private constructor(
    private val path: String,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private var connection: SQLiteConnection = openConnection(path)

    val file: File get() = File(path)

    fun <T> withConnection(block: (SQLiteConnection) -> T): T = lock.withLock {
        block(connection)
    }

    fun transaction(block: (SQLiteConnection) -> Unit) = lock.withLock {
        connection.execSQL("BEGIN IMMEDIATE")
        try {
            block(connection)
            connection.execSQL("COMMIT")
        } catch (failure: Throwable) {
            runCatching { connection.execSQL("ROLLBACK") }
            throw failure
        }
    }

    fun catalogState(catalog: String = CatalogSchema.CATALOG): CatalogState? = withConnection { db ->
        db.prepare("SELECT catalog, epoch, schema_version, search_model, generation FROM catalog_state WHERE catalog = ?").use { statement ->
            statement.bindText(1, catalog)
            if (!statement.step()) return@withConnection null
            CatalogState(
                catalog = statement.getText(0),
                epoch = statement.getLong(1),
                schemaVersion = statement.getInt(2),
                searchModel = statement.getText(3),
                generation = statement.getLong(4),
            )
        }
    }

    fun writeCatalogState(state: CatalogState) = withConnection { db ->
        upsertCatalogState(db, state)
    }

    fun eventRowCount(): Long = withConnection { db ->
        db.prepare("SELECT COUNT(*) FROM events").use { statement ->
            check(statement.step()) { "event count query returned no row" }
            statement.getLong(0)
        }
    }

    fun pauseAndReplace(snapshot: File) = lock.withLock {
        connection.close()
        val target = File(path)
        val backup = File("$path.bak")
        if (target.exists()) {
            backup.delete()
            if (!target.renameTo(backup)) {
                connection = openConnection(path)
                error("could not move the active catalog aside")
            }
        }
        try {
            if (!snapshot.renameTo(target) && !snapshot.copyTo(target, overwrite = true).exists()) {
                error("could not install the snapshot")
            }
            snapshot.delete()
            connection = openConnection(path)
            backup.delete()
        } catch (failure: Throwable) {
            if (backup.exists()) {
                target.delete()
                backup.renameTo(target)
            }
            connection = openConnection(path)
            throw failure
        }
    }

    override fun close() = lock.withLock {
        connection.close()
    }

    companion object {
        fun open(path: String): ZapstoreDatabase {
            File(path).parentFile?.mkdirs()
            return ZapstoreDatabase(path).also { it.withConnection(::applySchema) }
        }

        fun createEmpty(path: String): ZapstoreDatabase {
            File(path).parentFile?.mkdirs()
            File(path).delete()
            val database = open(path)
            database.writeCatalogState(
                CatalogState(
                    catalog = CatalogSchema.CATALOG,
                    epoch = 0,
                    schemaVersion = CatalogSchema.VERSION,
                    searchModel = CatalogSchema.SEARCH_MODEL,
                    generation = 0,
                ),
            )
            return database
        }

        internal fun applySchema(connection: SQLiteConnection) {
            connection.execSQL("PRAGMA foreign_keys = ON")
            CatalogSchema.CREATE_STATEMENTS.forEach(connection::execSQL)
        }

        internal fun upsertCatalogState(connection: SQLiteConnection, state: CatalogState) {
            connection.prepare(
                """
                INSERT INTO catalog_state(catalog, epoch, schema_version, search_model, generation)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(catalog) DO UPDATE SET
                    epoch = excluded.epoch,
                    schema_version = excluded.schema_version,
                    search_model = excluded.search_model,
                    generation = excluded.generation
                """.trimIndent(),
            ).use { statement ->
                statement.bindText(1, state.catalog)
                statement.bindLong(2, state.epoch)
                statement.bindLong(3, state.schemaVersion.toLong())
                statement.bindText(4, state.searchModel)
                statement.bindLong(5, state.generation)
                statement.step()
            }
        }

        private fun openConnection(path: String): SQLiteConnection =
            BundledSQLiteDriver().open(path)
    }
}
