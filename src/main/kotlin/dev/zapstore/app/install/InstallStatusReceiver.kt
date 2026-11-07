package dev.zapstore.app.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.zapstore.app.ZapstoreApplication

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val packageId = intent.getStringExtra(EXTRA_PACKAGE)
            ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: return
        val message = if (status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            null
        } else {
            installFailureText(status, Build.MANUFACTURER, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
        val installer = (context.applicationContext as? ZapstoreApplication)?.installer ?: return
        installer.onStatus(packageId, status, confirmationIntent(intent), message)
    }

    companion object {
        const val EXTRA_PACKAGE = "dev.zapstore.app.install.PACKAGE"
    }
}

internal fun installFailureText(status: Int, manufacturer: String, detail: String?): String {
    val hint = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> when {
            manufacturer.equals("samsung", ignoreCase = true) ->
                "Samsung Auto Blocker may have stopped this install"
            manufacturer.equals("xiaomi", ignoreCase = true) ||
                manufacturer.equals("redmi", ignoreCase = true) ||
                manufacturer.equals("poco", ignoreCase = true) ->
                "MIUI or HyperOS may have stopped this install"
            else -> null
        }
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free space"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This APK is not compatible with this device"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "This package conflicts with an installed app"
        PackageInstaller.STATUS_FAILURE_TIMEOUT -> "The installer timed out"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled"
        PackageInstaller.STATUS_FAILURE_INVALID -> "The APK is not valid"
        else -> null
    }
    val extra = detail?.takeIf { it.isNotBlank() && !it.equals(hint, ignoreCase = true) }
    return listOfNotNull(hint, extra).joinToString(". ").ifBlank { "Install failed" }
}

/**
 * Android refused the update because the installed copy has another signing key. It reports that as
 * a conflict whose detail names `INSTALL_FAILED_UPDATE_INCOMPATIBLE` or says the signatures differ.
 */
internal fun isSignatureConflict(status: Int, detail: String?): Boolean {
    if (status != PackageInstaller.STATUS_FAILURE_CONFLICT || detail == null) return false
    return detail.contains("UPDATE_INCOMPATIBLE") || detail.contains("signatures do not match", ignoreCase = true)
}

@Suppress("DEPRECATION")
private fun confirmationIntent(intent: Intent): Intent? =
    intent.getParcelableExtra(Intent.EXTRA_INTENT)
        ?: intent.getParcelableExtra(LEGACY_CONFIRM_INTENT)

private const val LEGACY_CONFIRM_INTENT = "android.content.pm.extra.INTENT"
