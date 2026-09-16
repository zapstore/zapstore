package dev.zapstore.app.catalogsync

data class InstalledApp(
    val packageId: String,
    val versionCode: Long,
    val versionName: String,
    val certificateHashes: Set<String>,
)

data class CatalogAssetRow(
    val appId: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val channel: String?,
    val platform: String?,
    val variant: String?,
    val certificateHash: String?,
    val mime: String?,
)

data class AvailableUpdate(
    val appId: String,
    val name: String,
    val installedVersion: String,
    val installedVersionCode: Long,
    val availableVersion: String,
    val availableVersionCode: Long,
    val channel: String?,
)

object LocalUpdateMatcher {
    fun match(
        installed: List<InstalledApp>,
        catalog: List<CatalogAssetRow>,
        channel: String = "stable",
        androidPlatforms: Set<String> = ANDROID_PLATFORMS,
    ): List<AvailableUpdate> {
        val latestByApp = catalog
            .filter { row ->
                (row.channel == null || row.channel == channel) &&
                    platformMatches(row.platform, androidPlatforms) &&
                    (row.mime == null || row.mime == ANDROID_MIME)
            }
            .groupBy { it.appId }
            .mapValues { (_, rows) -> rows.maxBy { it.versionCode } }
        return installed.mapNotNull { app ->
            val row = latestByApp[app.packageId] ?: return@mapNotNull null
            if (row.versionCode <= app.versionCode) return@mapNotNull null
            if (row.certificateHash != null &&
                row.certificateHash !in app.certificateHashes
            ) {
                return@mapNotNull null
            }
            AvailableUpdate(
                appId = app.packageId,
                name = row.name,
                installedVersion = app.versionName,
                installedVersionCode = app.versionCode,
                availableVersion = row.version,
                availableVersionCode = row.versionCode,
                channel = row.channel,
            )
        }.sortedBy { it.name.lowercase() }
    }

    private fun platformMatches(platform: String?, androidPlatforms: Set<String>): Boolean {
        if (platform.isNullOrBlank()) return true
        return platform.split(',').any { it in androidPlatforms || it.startsWith("android") }
    }

    const val ANDROID_MIME = "application/vnd.android.package-archive"
    val ANDROID_PLATFORMS = setOf(
        "android-arm64-v8a",
        "android-armeabi-v7a",
        "android-x86_64",
        "android-x86",
        "android",
    )
}
