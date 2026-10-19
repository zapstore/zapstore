package dev.zapstore.app.catalog

import dev.zapstore.iolite.AppRecord
import org.json.JSONArray
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
    fun `an installed ancestor certificate is an update`() {
        val installed = listOf(InstalledApp("dev.one", 10, "1.0", setOf("aa".repeat(32))))
        val catalog = listOf(
            app("dev.one", "One", "1.1", 11, certificateHash = "bb".repeat(32), ancestors = listOf("aa".repeat(32))),
        )

        assertEquals(listOf("dev.one"), availableUpdates(installed, catalog).map { it.app.appId })
    }

    @Test
    fun `a certificate outside the chain is not an update`() {
        val installed = listOf(InstalledApp("dev.one", 10, "1.0", setOf("zz".repeat(32))))
        val catalog = listOf(
            app("dev.one", "One", "1.1", 11, certificateHash = "bb".repeat(32), ancestors = listOf("aa".repeat(32))),
        )

        assertEquals(emptyList<AvailableUpdate>(), availableUpdates(installed, catalog))
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
    fun `each installed app lands in one group`() {
        val ours = "dev.zapstore.app"
        val installed = listOf(
            InstalledApp("dev.ready", 1, "1", setOf("aa"), installingPackage = ours, label = "Ready"),
            InstalledApp("dev.sideloaded", 1, "1", setOf("aa"), installingPackage = "com.google.android.packageinstaller", label = "Side"),
            InstalledApp("dev.play", 1, "1", setOf("aa"), installingPackage = "com.android.vending", label = "Play"),
            InstalledApp("dev.resigned", 1, "1", setOf("pp"), installingPackage = "com.android.vending", label = "Resigned"),
            InstalledApp("dev.owned", 1, "1", setOf("aa"), installingPackage = ours, updateOwner = "org.fdroid.fdroid", label = "Owned"),
            InstalledApp("dev.current", 2, "2", setOf("aa"), label = "Current"),
            InstalledApp("dev.skip", 1, "1", setOf("aa"), installingPackage = ours, label = "Skip"),
            InstalledApp("com.other", 1, "1.4", setOf("zz"), label = "Other"),
        )
        val catalog = listOf("dev.ready", "dev.sideloaded", "dev.play", "dev.resigned", "dev.owned", "dev.current", "dev.skip")
            .map { app(it, it.removePrefix("dev."), "2", 2, certificateHash = "aa") }
        val groups = updateGroups(installed, catalog, ignored = setOf("dev.skip"), ourPackage = ours) { it.installingPackage == ours }

        assertEquals(listOf("dev.ready"), groups.updates.map { it.app.appId })
        assertEquals(listOf("dev.sideloaded"), groups.manualUpdates.map { it.app.appId })
        assertEquals(listOf("dev.current"), groups.installedApps.map { it.appId })
        assertEquals(listOf("dev.owned", "dev.play", "dev.resigned"), groups.otherStores.map { it.app.appId })
        val play = groups.otherStores.first { it.app.appId == "dev.play" }
        assertEquals("com.android.vending", play.store)
        assertEquals("dev.play", play.update?.app?.appId)
        val resigned = groups.otherStores.first { it.app.appId == "dev.resigned" }
        assertEquals(null, resigned.update)
        assertEquals(true, resigned.signedDifferently)
        assertEquals("org.fdroid.fdroid", groups.otherStores.first { it.app.appId == "dev.owned" }.store)
        assertEquals(listOf("com.other"), groups.otherInstalled.map { it.packageId })
    }
}

internal fun app(
    appId: String,
    name: String,
    version: String,
    versionCode: Long,
    certificateHash: String?,
    ancestors: List<String> = emptyList(),
): AppRecord = AppRecord(
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
    metadata = JSONObject().apply {
        if (ancestors.isNotEmpty()) put("apk_certificate_ancestor", JSONArray(ancestors))
    },
)
