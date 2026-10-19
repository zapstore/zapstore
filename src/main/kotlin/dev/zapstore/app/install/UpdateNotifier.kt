package dev.zapstore.app.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.zapstore.app.MainActivity
import dev.zapstore.app.R

/**
 * One quiet notification for updates the background run downloaded but Android must confirm.
 * It is posted only when a version appears that was not in the last set, and cleared when the set empties.
 */
object UpdateNotifier {
    const val ACTION_SHOW_UPDATES = "dev.zapstore.app.SHOW_UPDATES"
    private const val CHANNEL = "updates"
    private const val ID = 0x5550
    private const val PREFS = "update_notifier"
    private const val KEY_SHOWN = "shown"

    /** [ready] is `appId@hash` for each staged update. */
    fun post(context: Context, ready: Set<String>, names: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (ready.isEmpty()) {
            manager.cancel(ID)
            prefs.edit().remove(KEY_SHOWN).apply()
            return
        }
        val shown = prefs.getStringSet(KEY_SHOWN, emptySet()).orEmpty()
        prefs.edit().putStringSet(KEY_SHOWN, ready).apply()
        if (shown.containsAll(ready)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.updates_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            ID,
            Intent(context, MainActivity::class.java).setAction(ACTION_SHOW_UPDATES).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.resources.getQuantityString(R.plurals.updates_ready, ready.size, ready.size))
            .setContentText(names.joinToString(", "))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(ID, notification)
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
