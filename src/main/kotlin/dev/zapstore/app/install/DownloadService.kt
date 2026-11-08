package dev.zapstore.app.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.zapstore.app.AppLog
import dev.zapstore.app.R
import dev.zapstore.app.ZapstoreApplication
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while an install the user started is downloading or verifying, so
 * leaving Zapstore does not stop it. Stops itself once nothing is transferring.
 */
class DownloadService : Service() {
    private val scope = MainScope()
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val installer = (application as ZapstoreApplication).installer
        val active = installer.tasks.value.values.filter { it.transferring }
        try {
            startForeground(ID, notification(active), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Refused, for one when the day's dataSync time is used up. Downloads go on unprotected.
            AppLog.w(TAG, "foreground refused", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (watch == null) {
            watch = scope.launch {
                installer.tasks
                    .map { tasks -> tasks.values.filter { it.transferring } }
                    .sample(UPDATE_MS)
                    .collect { transferring ->
                        if (transferring.isEmpty()) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            getSystemService(NotificationManager::class.java)?.notify(ID, notification(transferring))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+ caps dataSync services at six hours a day, shared with background update runs,
     * and kills the app unless the service stops within seconds. Downloads go on unprotected.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.w(TAG, "dataSync time limit reached; stopping")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(tasks: List<InstallTask>): Notification {
        ensureChannel(this)
        val received = tasks.sumOf { it.received }
        val total = tasks.sumOf { it.total ?: 0 }
        val sized = tasks.isNotEmpty() && tasks.all { (it.total ?: 0) > 0 }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(resources.getQuantityString(R.plurals.downloading_apps, tasks.size.coerceAtLeast(1), tasks.size.coerceAtLeast(1)))
            .setContentText(tasks.joinToString(", ") { it.name })
            .setProgress(if (sized) PROGRESS_MAX else 0, if (sized) (received * PROGRESS_MAX / total).toInt() else 0, !sized)
            .setContentIntent(openUpdates(this, ID))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL = "downloads"
        const val ID = 0x5552
        private const val UPDATE_MS = 1_000L
        private const val PROGRESS_MAX = 1_000

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL) == null) {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.downloads_notification_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        /** The notification a background update run holds while it downloads; it has no progress of its own. */
        fun backgroundNotification(context: Context): Notification {
            ensureChannel(context)
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.updating_apps))
                .setProgress(0, 0, true)
                .setContentIntent(openUpdates(context, ID))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        /** Only from the foreground: Android 12+ refuses to start it from the background. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        }
    }
}
