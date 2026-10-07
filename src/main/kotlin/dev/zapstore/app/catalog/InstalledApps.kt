package dev.zapstore.app.catalog

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.zapstore.iolite.AppRecord
import java.security.MessageDigest

data class InstalledApp(
    val packageId: String,
    val versionCode: Long,
    val versionName: String,
    /** SHA-256 of every current and historical signing certificate, lowercase hex. */
    val certificateHashes: Set<String>,
    val installingPackage: String? = null,
    val updateOwner: String? = null,
    val label: String = packageId,
)

data class AvailableUpdate(
    val app: AppRecord,
    val installedVersion: String,
    val installedVersionCode: Long,
    val installingPackage: String? = null,
    val updateOwner: String? = null,
    val certificateHashes: Set<String> = emptySet(),
)

/** Reads the device's installed packages and their signing certificates. */
fun Context.installedApps(): List<InstalledApp> =
    packageManager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES).mapNotNull { info ->
        val appInfo = info.applicationInfo ?: return@mapNotNull null
        if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
        val signing = info.signingInfo
        val certificates = buildSet {
            signing?.apkContentsSigners?.forEach { add(it.toByteArray().sha256Hex()) }
            signing?.signingCertificateHistory?.forEach { add(it.toByteArray().sha256Hex()) }
        }
        val source = runCatching { packageManager.getInstallSourceInfo(info.packageName) }.getOrNull()
        val owner = if (Build.VERSION.SDK_INT >= 34) source?.updateOwnerPackageName else null
        val label = appInfo.loadLabel(packageManager).toString().ifBlank { info.packageName }
        InstalledApp(
            packageId = info.packageName,
            versionCode = info.longVersionCode,
            versionName = info.versionName ?: info.longVersionCode.toString(),
            certificateHashes = certificates,
            installingPackage = source?.installingPackageName,
            updateOwner = owner,
            label = label,
        )
    }

/**
 * Catalog listings that are newer than an installed package signed by the same certificate.
 * [apps] are already the device-compatible main-channel listings, so no platform filtering happens here.
 */
fun availableUpdates(installed: List<InstalledApp>, apps: List<AppRecord>): List<AvailableUpdate> {
    val byAppId = apps.associateBy(AppRecord::appId)
    return installed.mapNotNull { package_ ->
        val app = byAppId[package_.packageId] ?: return@mapNotNull null
        val certificate = app.certificateHash ?: return@mapNotNull null
        if (app.versionCode <= package_.versionCode) return@mapNotNull null
        if (certificate !in package_.certificateHashes) return@mapNotNull null
        AvailableUpdate(
            app,
            package_.versionName,
            package_.versionCode,
            package_.installingPackage,
            package_.updateOwner,
            package_.certificateHashes,
        )
    }.sortedBy { it.app.name.lowercase() }
}

data class UpdateGroups(
    val updates: List<AvailableUpdate>,
    val manualUpdates: List<AvailableUpdate>,
    val installedApps: List<AppRecord>,
    val otherInstalled: List<InstalledApp>,
)

/**
 * Same buckets as the Flutter updates screen: silent updates, updates that need the
 * Android dialog, catalog apps that are already current, and installed packages
 * that are not in the catalog.
 */
fun updateGroups(
    available: List<AvailableUpdate>,
    installed: List<InstalledApp>,
    apps: List<AppRecord>,
    silent: (AvailableUpdate) -> Boolean,
): UpdateGroups {
    val (updates, manualUpdates) = available.partition(silent)
    val updating = available.map { it.app.appId }.toSet()
    val catalog = apps.associateBy { it.appId }
    val installedApps = installed.mapNotNull { pkg ->
        if (pkg.packageId in updating) null else catalog[pkg.packageId]
    }.sortedBy { it.name.lowercase() }
    val otherInstalled = installed
        .filter { it.packageId !in catalog }
        .sortedBy { it.label.lowercase() }
    return UpdateGroups(updates, manualUpdates, installedApps, otherInstalled)
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it.toInt() and 0xff) }
