package dev.zapstore.iolite

import androidx.sqlite.execSQL
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaVersionTest {
    @Test
    fun unknownVersionIsMovedAsideAndAFreshDatabaseOpens() {
        val dir = File("build/tmp/iolite-version-${System.nanoTime()}").apply { mkdirs() }
        val path = File(dir, "iolite.db").absolutePath
        JdbcSqliteConnection(path).apply {
            execSQL("CREATE TABLE future (x INTEGER)")
            execSQL("PRAGMA user_version = 99")
            close()
        }
        val icon = File(dir, "icons/1/com.example.webp").apply { parentFile.mkdirs(); writeText("old") }

        jdbcStore(path).use { store -> assertEquals(emptyList<CatalogRecord>(), store.allCatalogs()) }

        assertTrue(File("$path.v99").isFile)
        assertFalse(icon.exists())
        assertEquals(Schema.USER_VERSION.toLong(), userVersion(path))
    }

    @Test
    fun currentVersionMissingAColumnIsMovedAside() {
        val path = currentDatabase("iolite-mismatch") {
            execSQL("DROP INDEX comments_root_idx")
            execSQL("ALTER TABLE comments DROP COLUMN root")
        }

        jdbcStore(path).close()

        assertTrue(File("$path.mismatch").isFile)
        assertEquals(Schema.USER_VERSION.toLong(), userVersion(path))
        assertEquals(0L, count(path, "SELECT count(*) FROM catalogs"))
        assertEquals(1L, count(path, "SELECT count(*) FROM pragma_table_info('comments') WHERE name = 'root'"))
    }

    @Test
    fun matchingDatabaseKeepsItsRows() {
        val path = currentDatabase("iolite-match") {}

        jdbcStore(path).close()

        assertFalse(File("$path.mismatch").exists())
        assertEquals(1L, count(path, "SELECT count(*) FROM catalogs"))
    }

    @Test
    fun failingReferenceSchemaKeepsTheDatabase() {
        val path = currentDatabase("iolite-no-reference") {}
        val noMemory: (String) -> androidx.sqlite.SQLiteConnection = { name ->
            check(name != ":memory:") { "no in-memory database" }
            JdbcSqliteConnection(name)
        }

        repeat(2) { IoliteStore(path, openConnection = noMemory).close() }

        assertFalse(File("$path.mismatch").exists())
        assertEquals(1L, count(path, "SELECT count(*) FROM catalogs"))
    }

    private fun currentDatabase(name: String, change: JdbcSqliteConnection.() -> Unit): String {
        val dir = File("build/tmp/$name-${System.nanoTime()}").apply { mkdirs() }
        val path = File(dir, "iolite.db").absolutePath
        JdbcSqliteConnection(path).apply {
            Schema.statements.forEach(::execSQL)
            execSQL("INSERT INTO catalogs (relay_url, position) VALUES ('wss://relay.example', 0)")
            change()
            execSQL("PRAGMA user_version = ${Schema.USER_VERSION}")
            close()
        }
        return path
    }

    private fun userVersion(path: String): Long = count(path, "PRAGMA user_version")

    private fun count(path: String, sql: String): Long = JdbcSqliteConnection(path).run {
        try {
            prepare(sql).use { statement ->
                statement.step()
                statement.getLong(0)
            }
        } finally {
            close()
        }
    }

    @Test
    fun corruptFileIsMovedAsideAndAFreshDatabaseOpens() {
        val dir = File("build/tmp/iolite-corrupt-${System.nanoTime()}").apply { mkdirs() }
        val path = File(dir, "iolite.db").absolutePath
        File(path).writeBytes(ByteArray(4096) { 0x5a })

        jdbcStore(path).use { store -> assertEquals(emptyList<CatalogRecord>(), store.allCatalogs()) }

        assertTrue(File("$path.corrupt").isFile)
    }
}
