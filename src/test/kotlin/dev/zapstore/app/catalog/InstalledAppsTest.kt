package dev.zapstore.app.catalog

import dev.zapstore.iolite.AppRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppsTest {
    @Test
    fun `an update needs a newer version code and a matching signing certificate`() {
        val installed = listOf(
            InstalledApp("dev.one", 10, "1.0", setOf("aa")),
            InstalledApp("dev.two", 20, "2.0", setOf("bb")),
            InstalledApp("dev.three", 5, "0.5", setOf("cc")),
            InstalledApp("dev.missing", 1, "0.1", setOf("dd")),
        )
        val catalog = listOf(
            app("dev.one", "One", "1.1", 11, certificateHash = "aa"),
            app("dev.two", "Two", "2.1", 21, certificateHash = "zz"),
            app("dev.three", "Three", "0.4", 4, certificateHash = "cc"),
        )

        val updates = availableUpdates(installed, catalog)

        assertEquals(listOf("dev.one"), updates.map { it.app.appId })
        assertEquals("1.0", updates.single().installedVersion)
        assertEquals("1.1", updates.single().app.version)
    }

    @Test
    fun `a listing without a certificate is not an update`() {
        val installed = listOf(InstalledApp("dev.none", 1, "1", setOf("aa")))
        val catalog = listOf(app("dev.none", "None", "9", 9, certificateHash = null))

        assertEquals(emptyList<AvailableUpdate>(), availableUpdates(installed, catalog))
    }

    @Test
    fun `updates are sorted by app name`() {
        val installed = listOf(
            InstalledApp("dev.b", 1, "1", setOf("aa")),
            InstalledApp("dev.a", 1, "1", setOf("aa")),
        )
        val catalog = listOf(
            app("dev.b", "Zed", "2", 2, certificateHash = "aa"),
            app("dev.a", "alpha", "2", 2, certificateHash = "aa"),
        )

        assertEquals(listOf("alpha", "Zed"), availableUpdates(installed, catalog).map { it.app.name })
    }

    @Test
    fun `groups match the flutter updates screen`() {
        val installed = listOf(
            InstalledApp("dev.ready", 1, "1", setOf("aa"), installingPackage = "dev.zapstore.app", label = "Ready"),
            InstalledApp("dev.manual", 1, "1", setOf("aa"), installingPackage = "com.android.vending", label = "Manual"),
            InstalledApp("dev.current", 2, "2", setOf("aa"), label = "Current"),
            InstalledApp("com.other", 1, "1.4", setOf("zz"), label = "Other"),
        )
        val catalog = listOf(
            app("dev.ready", "Ready", "2", 2, certificateHash = "aa"),
            app("dev.manual", "Manual", "2", 2, certificateHash = "aa"),
            app("dev.current", "Current", "2", 2, certificateHash = "aa"),
        )
        val updates = availableUpdates(installed, catalog)
        val groups = updateGroups(updates, installed, catalog) { it.installingPackage == "dev.zapstore.app" }

        assertEquals(listOf("dev.ready"), groups.updates.map { it.app.appId })
        assertEquals(listOf("dev.manual"), groups.manualUpdates.map { it.app.appId })
        assertEquals(listOf("dev.current"), groups.installedApps.map { it.appId })
        assertEquals(listOf("com.other"), groups.otherInstalled.map { it.packageId })
    }
}

internal fun app(appId: String, name: String, version: String, versionCode: Long, certificateHash: String?): AppRecord = AppRecord(
    id = appId.toByteArray(),
    catalogId = 1,
    appId = appId,
    certificateHash = certificateHash,
    createdAt = 1_750_000_000,
    proofPubkey = null,
    eventPubkey = "1".repeat(64),
    catalogManifestPubkey = null,
    name = name,
    summary = "",
    repository = null,
    version = version,
    versionCode = versionCode,
    channel = null,
    metadata = JSONObject(),
)
