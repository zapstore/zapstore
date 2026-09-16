package dev.zapstore.app.catalogsync

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

class InstalledAppsProvider(
    private val context: Context,
) {
    fun installedApps(): List<InstalledApp> {
        val manager = context.packageManager
        return manager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES).map { info ->
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
    }

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).toHex()
}
