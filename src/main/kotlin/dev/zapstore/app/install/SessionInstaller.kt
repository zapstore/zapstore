package dev.zapstore.app.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

object SessionInstaller {
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
        val installer = context.packageManager.packageInstaller
        installer.mySessions
            .filter { it.appPackageName == packageId }
            .forEach { session -> runCatching { installer.abandonSession(session.sessionId) } }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageId)
        params.setAppLabel(label)
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        if (bulk) params.setInstallScenario(PackageManager.INSTALL_SCENARIO_BULK)
        params.setRequireUserAction(
            if (plan is InstallPlan.Silent) {
                PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
            } else {
                PackageInstaller.SessionParams.USER_ACTION_REQUIRED
            },
        )
        if (Build.VERSION.SDK_INT >= 33) {
            params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        if (Build.VERSION.SDK_INT >= 34 && plan !is InstallPlan.Silent) {
            params.setRequestUpdateOwnership(true)
        }
        val sessionId = installer.createSession(params)
        val pending = statusIntent(context, sessionId, apk, packageId)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            if (Build.VERSION.SDK_INT < 34 || plan !is InstallPlan.Silent) {
                session.commit(pending)
            }
        }
        if (Build.VERSION.SDK_INT >= 34 && plan is InstallPlan.Silent) {
            val constraints = PackageInstaller.InstallConstraints.Builder()
                .setAppNotForegroundRequired()
                .setAppNotInteractingRequired()
                .build()
            installer.commitSessionAfterInstallConstraintsAreMet(
                sessionId,
                pending,
                constraints,
                CONSTRAINT_TIMEOUT_MILLIS,
            )
        }
    }

    private const val CONSTRAINT_TIMEOUT_MILLIS = 60L * 60 * 1000

    private fun statusIntent(context: Context, sessionId: Int, apk: File, packageId: String): android.content.IntentSender {
        val intent = Intent(context, InstallStatusReceiver::class.java).apply {
            putExtra(InstallStatusReceiver.EXTRA_APK, apk.absolutePath)
            putExtra(InstallStatusReceiver.EXTRA_PACKAGE, packageId)
        }
        val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }
}
