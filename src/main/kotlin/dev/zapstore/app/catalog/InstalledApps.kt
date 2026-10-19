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
 * Catalog listings newer than an installed package whose certificate is the signer
 * or an ancestor. [apps] are already the device-compatible main-channel listings,
 * so no platform filtering happens here.
 */
fun availableUpdates(installed: List<InstalledApp>, apps: List<AppRecord>): List<AvailableUpdate> {
    val byAppId = apps.associateBy(AppRecord::appId)
    return installed.mapNotNull { package_ ->
        val app = byAppId[package_.packageId] ?: return@mapNotNull null
        val chain = app.certificateChain()
        if (chain.isEmpty()) return@mapNotNull null
        if (app.versionCode <= package_.versionCode) return@mapNotNull null
        if (chain.none { it in package_.certificateHashes }) return@mapNotNull null
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

/** Installers that only put a file on the device; they never update it. */
private val sideloaders = setOf("com.google.android.packageinstaller", "com.android.packageinstaller", "com.android.shell")

/**
 * The store that keeps [installingPackage] or [updateOwner] up to date when it is not
 * Zapstore, or null. A sideload or an adb install has no store, so Zapstore may update it.
 */
fun otherStore(installingPackage: String?, updateOwner: String?, ourPackage: String): String? =
    updateOwner?.takeIf { it.isNotBlank() && it != ourPackage }
        ?: installingPackage?.takeIf { it.isNotBlank() && it != ourPackage && it !in sideloaders }

fun InstalledApp.otherStore(ourPackage: String): String? = otherStore(installingPackage, updateOwner, ourPackage)

fun AvailableUpdate.otherStore(ourPackage: String): String? = otherStore(installingPackage, updateOwner, ourPackage)

/** A catalog app another store installed. [update] is set when the catalog build can replace it. */
data class StoreApp(
    val app: AppRecord,
    val store: String,
    val installedVersion: String,
    val update: AvailableUpdate?,
    /** The catalog has a newer build signed by a different key; switching needs a reinstall. */
    val signedDifferently: Boolean,
)

data class UpdateGroups(
    val updates: List<AvailableUpdate>,
    val manualUpdates: List<AvailableUpdate>,
    val installedApps: List<AppRecord>,
    val otherStores: List<StoreApp>,
    val otherInstalled: List<InstalledApp>,
)

/**
 * My apps, one bucket per app: updates Zapstore commits alone, updates that need the
 * Android dialog, current apps Zapstore updates, catalog apps another store updates,
 * and installed packages that are not in the catalog. [ignored] apps are in none.
 */
fun updateGroups(
    installed: List<InstalledApp>,
    apps: List<AppRecord>,
    ignored: Set<String>,
    ourPackage: String,
    silent: (AvailableUpdate) -> Boolean,
): UpdateGroups {
    val catalog = apps.associateBy { it.appId }
    val available = availableUpdates(installed, apps).associateBy { it.app.appId }
    val updates = mutableListOf<AvailableUpdate>()
    val manual = mutableListOf<AvailableUpdate>()
    val current = mutableListOf<AppRecord>()
    val stores = mutableListOf<StoreApp>()
    installed.forEach { pkg ->
        val app = catalog[pkg.packageId] ?: return@forEach
        if (pkg.packageId in ignored) return@forEach
        val update = available[pkg.packageId]
        val store = pkg.otherStore(ourPackage)
        when {
            store != null -> stores += StoreApp(
                app = app,
                store = store,
                installedVersion = pkg.versionName,
                update = update,
                signedDifferently = update == null && app.versionCode > pkg.versionCode,
            )
            update == null -> current += app
            silent(update) -> updates += update
            else -> manual += update
        }
    }
    val byName = compareBy<AvailableUpdate> { it.app.name.lowercase() }
    return UpdateGroups(
        updates = updates.sortedWith(byName),
        manualUpdates = manual.sortedWith(byName),
        installedApps = current.sortedBy { it.name.lowercase() },
        otherStores = stores.sortedBy { it.app.name.lowercase() },
        otherInstalled = installed.filter { it.packageId !in catalog }.sortedBy { it.label.lowercase() },
    )
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it.toInt() and 0xff) }
