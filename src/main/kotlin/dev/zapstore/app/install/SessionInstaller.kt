package dev.zapstore.app.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

object SessionInstaller {
    /** Android 11 and older always show the installer dialog, even for an update Zapstore owns. */
    val silentSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

    /**
     * Writes [apk] into a new session and commits it. The result arrives at [InstallStatusReceiver],
     * which survives process death. A silent update on Android 14+ waits until the app is not on screen.
     */
    fun commit(
        context: Context,
        apk: File,
        packageId: String,
        label: String,
        plan: InstallPlan,
        bulk: Boolean = false,
    ) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            throw InstallException(InstallException.Kind.Permission, "install permission missing")
        }
        abandon(context, packageId)
        val silent = plan is InstallPlan.Silent && silentSupported
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageId)
        params.setAppLabel(label)
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        if (Build.VERSION.SDK_INT >= 31) {
            if (bulk) params.setInstallScenario(PackageManager.INSTALL_SCENARIO_BULK)
            params.setRequireUserAction(
                if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                else PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        if (Build.VERSION.SDK_INT >= 34 && !silent) {
            params.setRequestUpdateOwnership(true)
        }
        val sessionId = installer.createSession(params)
        val pending = statusIntent(context, sessionId, packageId)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            if (Build.VERSION.SDK_INT < 34 || !silent) session.commit(pending)
        }
        if (Build.VERSION.SDK_INT >= 34 && silent) {
            // Not "not interacting": an app with a foreground service (messengers without Play Services, VPNs, players) never satisfies it.
            val constraints = PackageInstaller.InstallConstraints.Builder()
                .setAppNotForegroundRequired()
                .build()
            installer.commitSessionAfterInstallConstraintsAreMet(sessionId, pending, constraints, CONSTRAINT_TIMEOUT_MILLIS)
        }
    }

    /** Drops this app's earlier sessions; a new install of the same package replaces them. */
    fun abandon(context: Context, packageId: String) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions
            .filter { it.appPackageName == packageId }
            .forEach { session -> runCatching { installer.abandonSession(session.sessionId) } }
    }

    private const val CONSTRAINT_TIMEOUT_MILLIS = 60L * 60 * 1000

    private fun statusIntent(context: Context, sessionId: Int, packageId: String): android.content.IntentSender {
        val intent = Intent(context, InstallStatusReceiver::class.java)
            .putExtra(InstallStatusReceiver.EXTRA_PACKAGE, packageId)
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val flags = mutable or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }
}
