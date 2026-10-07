package dev.zapstore.app.catalog

import android.content.Context
import android.content.pm.PackageManager
import dev.zapstore.iolite.AppRecord
import java.security.MessageDigest

data class InstalledApp(
    val packageId: String,
    val versionCode: Long,
    val versionName: String,
    /** SHA-256 of every current and historical signing certificate, lowercase hex. */
    val certificateHashes: Set<String>,
)

data class AvailableUpdate(
    val app: AppRecord,
    val installedVersion: String,
    val installedVersionCode: Long,
)

/** Reads the device's installed packages and their signing certificates. */
fun Context.installedApps(): List<InstalledApp> =
    packageManager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES).map { info ->
        val signing = info.signingInfo
        val certificates = buildSet {
            signing?.apkContentsSigners?.forEach { add(it.toByteArray().sha256Hex()) }
            signing?.signingCertificateHistory?.forEach { add(it.toByteArray().sha256Hex()) }
        }
        InstalledApp(
            packageId = info.packageName,
            versionCode = info.longVersionCode,
            versionName = info.versionName ?: info.longVersionCode.toString(),
            certificateHashes = certificates,
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
        AvailableUpdate(app, package_.versionName, package_.versionCode)
    }.sortedBy { it.app.name.lowercase() }
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it.toInt() and 0xff) }
