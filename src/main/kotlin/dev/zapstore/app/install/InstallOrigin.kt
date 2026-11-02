package dev.zapstore.app.install

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.zapstore.app.apk.sha256Hex

fun Context.installOrigin(packageId: String): InstallOrigin {
    val info = try {
        packageManager.getPackageInfo(packageId, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (_: PackageManager.NameNotFoundException) {
        return InstallOrigin(installed = false)
    }
    val source = packageManager.installSource(packageId)
    return InstallOrigin(
        installed = true,
        versionCode = info.longVersionCode,
        installingPackage = source.installer,
        updateOwner = source.updateOwner,
        certificates = info.certificateHashes(),
    )
}

/** SHA-256 of every current and historical signing certificate, lowercase hex. */
fun PackageInfo.certificateHashes(): Set<String> {
    val signing = signingInfo ?: return emptySet()
    return buildSet {
        signing.apkContentsSigners?.forEach { add(it.toByteArray().sha256Hex()) }
        signing.signingCertificateHistory?.forEach { add(it.toByteArray().sha256Hex()) }
    }
}

/** [updateOwner] exists from Android 14. */
data class InstallSource(val installer: String?, val updateOwner: String?)

fun PackageManager.installSource(packageId: String): InstallSource = runCatching {
    if (Build.VERSION.SDK_INT >= 30) {
        val info = getInstallSourceInfo(packageId)
        InstallSource(info.installingPackageName, if (Build.VERSION.SDK_INT >= 34) info.updateOwnerPackageName else null)
    } else {
        @Suppress("DEPRECATION")
        InstallSource(getInstallerPackageName(packageId), null)
    }
}.getOrDefault(InstallSource(null, null))
