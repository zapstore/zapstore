package dev.zapstore.app.catalogsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSyncTest {
    @Test
    fun `updates request json has only public fields`() {
        val json = UpdatesRequest(
            protocol = 1,
            catalog = "default",
            schemaVersion = 1,
            epoch = 0,
            searchModel = CatalogSchema.SEARCH_MODEL,
        ).toJson()
        assertTrue(json.contains("\"protocol\":1"))
        assertTrue(json.contains("\"catalog\":\"default\""))
        assertTrue(json.contains("\"schema_version\":1"))
        assertTrue(json.contains("\"epoch\":0"))
        assertTrue(json.contains("\"search_model\":\"${CatalogSchema.SEARCH_MODEL}\""))
        assertTrue(!json.contains("installed"))
        assertTrue(!json.contains("search\""))
    }

    @Test
    fun `local matcher keeps lineage and newer version codes`() {
        val installed = listOf(
            InstalledApp("dev.one", 10, "1.0", setOf("aa")),
            InstalledApp("dev.two", 20, "2.0", setOf("bb")),
            InstalledApp("dev.three", 5, "0.5", setOf("cc")),
        )
        val catalog = listOf(
            CatalogAssetRow("dev.one", "One", "1.1", 11, "stable", "android-arm64-v8a", null, "aa", LocalUpdateMatcher.ANDROID_MIME),
            CatalogAssetRow("dev.two", "Two", "2.1", 21, "stable", "android", null, "zz", LocalUpdateMatcher.ANDROID_MIME),
            CatalogAssetRow("dev.three", "Three", "0.4", 4, "stable", "android", null, "cc", LocalUpdateMatcher.ANDROID_MIME),
        )
        val updates = LocalUpdateMatcher.match(installed, catalog)
        assertEquals(listOf("dev.one"), updates.map { it.appId })
        assertEquals("1.1", updates.single().availableVersion)
    }

    @Test
    fun `hex helpers round-trip`() {
        val hex = "0a1b2c3d"
        assertEquals(hex, hex.hexToBytes().toHex())
    }
}
