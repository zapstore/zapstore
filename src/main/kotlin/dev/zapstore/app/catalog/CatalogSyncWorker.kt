package dev.zapstore.app.catalog

import dev.zapstore.app.SyncInterval
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zapstore.app.ZapstoreApplication
import java.util.concurrent.TimeUnit

/** Catalog refresh outside the foreground (`zapstore-periodic-sync`). It never installs. */
class CatalogSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ZapstoreApplication
        if (!app.network.awaitRemoteReady(TOR_TIMEOUT_MS)) return Result.retry()
        app.catalogSync.sync()
        app.releaseNetwork()
        return if (app.catalogSync.status.value.error == null) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "catalogSync"
        private const val TOR_TIMEOUT_MS = 90_000L

        /** WorkManager's floor is 15 minutes; shorter intervals are kept by the foreground loop. */
        fun schedule(context: Context, interval: SyncInterval) {
            val minutes = interval.minutes.coerceAtLeast(MIN_PERIODIC_MINUTES).toLong()
            val request = PeriodicWorkRequestBuilder<CatalogSyncWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        const val MIN_PERIODIC_MINUTES = 15
    }
}
