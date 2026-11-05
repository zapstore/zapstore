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
        JdbcSqliteConnection(path).apply {
            prepare("PRAGMA user_version").use { statement ->
                statement.step()
                assertEquals(1L, statement.getLong(0))
            }
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
