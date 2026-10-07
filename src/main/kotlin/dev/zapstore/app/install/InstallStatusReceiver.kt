package dev.zapstore.app.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import dev.zapstore.app.R
import dev.zapstore.app.ZapstoreApplication
import java.io.File

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val packageId = intent.getStringExtra(EXTRA_PACKAGE)
            ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: return
        val apk = intent.getStringExtra(EXTRA_APK)?.let(::File)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmationIntent(intent) ?: return
                val opened = UserActionPresenter.present(context, confirm, packageId)
                InstallEvents.publish(
                    InstallResult(
                        packageId = packageId,
                        success = false,
                        awaitingUser = true,
                        message = if (opened) null else context.getString(R.string.install_failed),
                    ),
                )
            }
            PackageInstaller.STATUS_SUCCESS -> {
                apk?.delete()
                (context.applicationContext as? ZapstoreApplication)?.catalogSync?.refreshInstalled()
                InstallEvents.publish(InstallResult(packageId, success = true))
            }
            else -> {
                apk?.delete()
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                InstallEvents.publish(
                    InstallResult(
                        packageId = packageId,
                        success = false,
                        message = installFailureText(status, Build.MANUFACTURER, detail),
                    ),
                )
            }
        }
    }

    companion object {
        const val EXTRA_APK = "dev.zapstore.app.install.APK"
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

object UserActionPresenter {
    fun present(context: Context, confirm: Intent, packageId: String): Boolean {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(confirm) }.isSuccess) return true
        notify(context, confirm, packageId)
        return false
    }

    private fun notify(context: Context, confirm: Intent, packageId: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = "install"
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, context.getString(R.string.install_notification_channel), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val content = PendingIntent.getActivity(
            context,
            packageId.hashCode(),
            confirm,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.install_notification))
            .setContentText(packageId)
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        manager.notify(packageId.hashCode(), notification)
    }
}

@Suppress("DEPRECATION")
private fun confirmationIntent(intent: Intent): Intent? =
    intent.getParcelableExtra(Intent.EXTRA_INTENT)
        ?: intent.getParcelableExtra(LEGACY_CONFIRM_INTENT)

private const val LEGACY_CONFIRM_INTENT = "android.content.pm.extra.INTENT"
