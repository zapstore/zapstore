package dev.zapstore.app.apk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier as ApkSignatureVerifier
import java.io.File
import java.security.MessageDigest

data class ApkIdentity(
    val packageId: String,
    val sha256: String,
    val currentCertificates: Set<String>,
    val lineage: List<String>,
    /** 0 when the archive did not report one. The install floor treats 0 as unknown. */
    val targetSdk: Int = 0,
    /** DER of each current signer, keyed by its SHA-256. */
    val certificateDer: Map<String, ByteArray> = emptyMap(),
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
        val (signers, history) = info.signingInfo?.let { signing ->
            if (signing.hasMultipleSigners()) {
                throw ApkVerificationException("APK has multiple current signers")
            }
            signing.apkContentsSigners.map { it.toByteArray() } to signing.signingCertificateHistory.map { it.toByteArray() }
        } ?: readSigners(apk)
        val der = signers.associateBy { it.sha256Hex() }
        val current = der.keys
        val lineage = history.map { it.sha256Hex() }
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
            certificateDer = der,
        )
    }

    /**
     * An Android 13 device was seen returning no signingInfo for every archive, so the signers are read
     * from the APK itself, checked as [sdk] would check them. Returns the current signers and the
     * lineage, oldest first, as DER.
     */
    internal fun readSigners(apk: File, sdk: Int = Build.VERSION.SDK_INT): Pair<List<ByteArray>, List<ByteArray>> {
        val result = try {
            ApkSignatureVerifier.Builder(apk).setMinCheckedPlatformVersion(sdk).setMaxCheckedPlatformVersion(sdk).build().verify()
        } catch (e: Exception) {
            throw ApkVerificationException("could not read the APK signature: ${e.message}")
        } catch (e: LinkageError) {
            // Installer only catches Exception; a class missing from the shrunk library must fail the install, not the app.
            throw ApkVerificationException("could not read the APK signature: ${e.javaClass.simpleName} ${e.message}")
        }
        if (!result.isVerified) {
            val reason = result.allErrors.firstOrNull()?.toString() ?: "no valid signature"
            throw ApkVerificationException("APK signature is invalid: $reason")
        }
        val signers = result.signerCertificates.map { it.encoded }
        if (signers.isEmpty()) throw ApkVerificationException("APK is unsigned")
        if (signers.size > 1) throw ApkVerificationException("APK has multiple current signers")
        val lineage = result.signingCertificateLineage?.certificatesInLineage?.map { it.encoded } ?: signers
        return signers to lineage
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
