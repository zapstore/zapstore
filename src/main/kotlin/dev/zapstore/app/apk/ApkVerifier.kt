package dev.zapstore.app.apk

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

data class ApkIdentity(
    val packageId: String,
    val sha256: String,
    val currentCertificates: Set<String>,
    val lineage: List<String>,
    /** 0 when the archive did not report one. The install floor treats 0 as unknown. */
    val targetSdk: Int = 0,
)

data class ListedApk(
    val packageId: String,
    val sha256: String,
    val certificateHash: String,
)

class ApkVerificationException(message: String) : Exception(message)

object ApkVerifier {
    fun inspect(context: Context, apk: File): ApkIdentity {
        val info = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: throw ApkVerificationException("file is not an installable APK")
        val signing = info.signingInfo ?: throw ApkVerificationException("APK is unsigned")
        if (signing.hasMultipleSigners()) {
            throw ApkVerificationException("APK has multiple current signers")
        }
        val current = signing.apkContentsSigners.map { it.toByteArray().sha256Hex() }.toSet()
        val lineage = signing.signingCertificateHistory.map { it.toByteArray().sha256Hex() }
        val appInfo = info.applicationInfo
        if (appInfo != null) {
            appInfo.sourceDir = apk.absolutePath
            appInfo.publicSourceDir = apk.absolutePath
        }
        return ApkIdentity(
            packageId = info.packageName,
            sha256 = apk.sha256Hex(),
            currentCertificates = current,
            lineage = lineage,
            targetSdk = appInfo?.targetSdkVersion ?: 0,
        )
    }

    fun isInstallable(
        apk: ApkIdentity,
        listed: ListedApk,
        installedCertificates: Set<String> = emptySet(),
        isUpdate: Boolean = false,
    ): Boolean {
        if (!apk.sha256.equals(listed.sha256, ignoreCase = true)) return false
        if (apk.packageId != listed.packageId) return false
        if (listed.certificateHash.lowercase() !in apk.currentCertificates) return false
        if (!isUpdate) return true
        if (installedCertificates.isEmpty()) return false
        val installed = installedCertificates.map { it.lowercase() }
        val current = apk.currentCertificates.map { it.lowercase() }
        val lineage = apk.lineage.map { it.lowercase() }
        if (installed.any { it in current }) return true
        return installed.any { it in lineage }
    }
}

private fun File.sha256Hex(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().toHex()
}

internal fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
