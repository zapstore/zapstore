package dev.zapstore.app.install

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zapstore.app.AppLog
import dev.zapstore.app.DeviceState
import dev.zapstore.app.ZapstoreApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * On the chosen interval: refresh the catalog, then download and verify each update. Updates that
 * install without a prompt are committed silently; the rest wait in [InstallCache] for a tap,
 * and [UpdateNotifier] says so once. A failed sync or a dropped download retries with backoff,
 * resuming partial files.
 */
class BackgroundUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ZapstoreApplication
        if (!app.settings.state.value.backgroundAutoUpdates) return Result.success()
        if (!app.packageManager.canRequestPackageInstalls()) return Result.success()
        if (!app.network.awaitRemoteReady(TOR_TIMEOUT_MS)) return retryOrGiveUp()
        var transient = 0
        try {
            app.catalogSync.sync()
            if (app.catalogSync.status.value.error != null) transient++
            val updates = app.catalogSync.updates.first()
            if (updates.isNotEmpty()) holdForeground()
            var committed = 0
            val ready = LinkedHashMap<String, String>()
            for (update in updates) {
                if (isStopped) break
                val result = app.installer.runInBackground(update.app) ?: continue
                result.onSuccess { phase ->
                    if (phase == TaskPhase.Ready) ready["${update.app.appId}@${update.app.apkHash}"] = update.app.name else committed++
                }.onFailure {
                    if (it.isTransient()) transient++
                    AppLog.w(TAG, "background update ${update.app.appId} failed", it)
                }
            }
            AppLog.i(TAG, "background updates: ${updates.size} available, $committed committed, ${ready.size} ready, $transient to retry")
            if (!isStopped && !inForeground()) UpdateNotifier.post(app, ready.keys, ready.values.toList())
        } finally {
            app.releaseNetwork()
        }
        return if (transient > 0) retryOrGiveUp() else Result.success()
    }

    /**
     * Without this Android stops the run after about ten minutes, which is less than a few large
     * downloads on a slow connection. Android may refuse from some states; the run then goes on unprotected.
     */
    private suspend fun holdForeground() {
        runCatching {
            setForeground(
                ForegroundInfo(
                    DownloadService.ID,
                    DownloadService.backgroundNotification(applicationContext),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                ),
            )
        }.onFailure { AppLog.w(TAG, "foreground", it) }
    }

    /** A periodic run retries a few times with backoff; after that it waits for the next period. */
    private fun retryOrGiveUp(): Result = if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()

    private suspend fun inForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    companion object {
        private const val TAG = "BackgroundUpdates"
        private const val NAME = "backgroundUpdates"
        private const val TOR_TIMEOUT_MS = 90_000L
        private const val MAX_RETRIES = 3
        private const val BACKOFF_MINUTES = 15L

        /** Schedules or cancels the periodic run to match [state]. */
        fun apply(context: Context, state: DeviceState) {
            val work = WorkManager.getInstance(context)
            if (!state.backgroundAutoUpdates) {
                work.cancelUniqueWork(NAME)
                return
            }
            val network = if (state.backgroundAutoUpdatesAnyNetwork) NetworkType.CONNECTED else NetworkType.UNMETERED
            val hours = state.backgroundUpdateInterval.hours.toLong()
            val request = PeriodicWorkRequestBuilder<BackgroundUpdateWorker>(hours, TimeUnit.HOURS, hours * 60 / 6, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(network)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
