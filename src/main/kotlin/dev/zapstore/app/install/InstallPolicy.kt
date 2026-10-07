package dev.zapstore.app.install

import dev.zapstore.app.apk.ApkIdentity
import dev.zapstore.app.apk.ApkVerifier
import dev.zapstore.app.apk.ListedApk

/** What the device already has for one package. [installed] is false when the package is absent. */
data class InstallOrigin(
    val installed: Boolean,
    val versionCode: Long = 0,
    val installingPackage: String? = null,
    val updateOwner: String? = null,
    val certificates: Set<String> = emptySet(),
)

enum class BlockReason {
    Platform,
    Signer,
    Downgrade,
    MissingFile,
    Banned,
    NotListed,
}

sealed class InstallPlan {
    data object FirstInstall : InstallPlan()
    data object Silent : InstallPlan()
    data object Takeover : InstallPlan()
    data class Blocked(val reason: BlockReason) : InstallPlan()
}

/**
 * Android's install floor. API 31–33 have none. API 34 rejects `targetSdk` below 23.
 * API 35 and later reject below 24.
 */
fun platformFloor(deviceSdk: Int): Int? = when {
    deviceSdk >= 35 -> 24
    deviceSdk >= 34 -> 23
    else -> null
}

/**
 * Classify one install before a session is opened.
 * [apk] is null before download. `targetSdk` is not in the listing, so the platform
 * block is decided only after the file is on disk.
 */
fun installPlan(
    deviceSdk: Int,
    ourPackage: String,
    packageId: String,
    origin: InstallOrigin,
    listedVersionCode: Long,
    listedHash: String?,
    listedCertificate: String?,
    banned: Boolean = false,
    apk: ApkIdentity? = null,
): InstallPlan {
    if (banned) return InstallPlan.Blocked(BlockReason.Banned)
    if (listedHash.isNullOrBlank() || listedCertificate.isNullOrBlank()) {
        return InstallPlan.Blocked(BlockReason.MissingFile)
    }
    if (origin.installed && listedVersionCode < origin.versionCode) {
        return InstallPlan.Blocked(BlockReason.Downgrade)
    }
    if (apk != null) {
        val floor = platformFloor(deviceSdk)
        if (floor != null && apk.targetSdk in 1 until floor) {
            return InstallPlan.Blocked(BlockReason.Platform)
        }
        val listed = ListedApk(packageId, listedHash, listedCertificate)
        val sameFile = apk.packageId == packageId && apk.sha256.equals(listedHash, ignoreCase = true)
        val sameCert = listedCertificate.lowercase() in apk.currentCertificates.map { it.lowercase() }
        if (!sameFile || !sameCert) {
            return InstallPlan.Blocked(if (sameFile) BlockReason.Signer else BlockReason.NotListed)
        }
        if (!ApkVerifier.isInstallable(apk, listed, origin.certificates, origin.installed)) {
            return InstallPlan.Blocked(BlockReason.Signer)
        }
    }
    if (!origin.installed) return InstallPlan.FirstInstall
    val ours = origin.installingPackage == ourPackage
    val foreignOwner = !origin.updateOwner.isNullOrBlank() && origin.updateOwner != ourPackage
    return if (ours && !foreignOwner) InstallPlan.Silent else InstallPlan.Takeover
}

/** An update Zapstore can commit without the Android installer dialog. */
fun isSilentUpdate(
    ourPackage: String,
    packageId: String,
    installingPackage: String?,
    updateOwner: String?,
    listedVersionCode: Long,
    installedVersionCode: Long,
    listedHash: String?,
    listedCertificate: String?,
): Boolean = installPlan(
    deviceSdk = 34,
    ourPackage = ourPackage,
    packageId = packageId,
    origin = InstallOrigin(
        installed = true,
        versionCode = installedVersionCode,
        installingPackage = installingPackage,
        updateOwner = updateOwner,
    ),
    listedVersionCode = listedVersionCode,
    listedHash = listedHash,
    listedCertificate = listedCertificate,
) is InstallPlan.Silent
