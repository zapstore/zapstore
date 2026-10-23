package dev.zapstore.app.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.net.toUri

/** Screens that let the user allow Zapstore to install apps, best first. */
fun unknownSourcesIntents(packageName: String): List<Intent> = listOf(
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()),
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
)

/**
 * True when Zapstore must send the user to grant install permission first: it has none.
 * Some Android TV builds have no screen to grant it; on a TV without one the session is
 * committed anyway and Android's installer asks the user itself.
 */
fun Context.mustAskInstallPermission(): Boolean {
    if (packageManager.canRequestPackageInstalls()) return false
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
    return unknownSourcesIntents(packageName).any { packageManager.resolveActivity(it, 0) != null }
}
