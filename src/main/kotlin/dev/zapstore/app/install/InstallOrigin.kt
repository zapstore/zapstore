package dev.zapstore.app.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.zapstore.app.apk.sha256Hex

fun Context.installOrigin(packageId: String): InstallOrigin {
    val info = try {
        packageManager.getPackageInfo(packageId, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (_: PackageManager.NameNotFoundException) {
        return InstallOrigin(installed = false)
    }
    val signing = info.signingInfo
    val certificates = buildSet {
        signing?.apkContentsSigners?.forEach { add(it.toByteArray().sha256Hex()) }
        signing?.signingCertificateHistory?.forEach { add(it.toByteArray().sha256Hex()) }
    }
    val source = packageManager.getInstallSourceInfo(packageId)
    val owner = if (Build.VERSION.SDK_INT >= 34) source.updateOwnerPackageName else null
    return InstallOrigin(
        installed = true,
        versionCode = info.longVersionCode,
        installingPackage = source.installingPackageName,
        updateOwner = owner,
        certificates = certificates,
    )
}
