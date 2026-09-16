package dev.zapstore.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.zapstore.app.catalogsync.CatalogBootstrapper
import dev.zapstore.app.catalogsync.CatalogSyncClient
import dev.zapstore.app.catalogsync.CatalogSyncRepository
import dev.zapstore.app.catalogsync.CompactEventStore
import dev.zapstore.app.catalogsync.InstalledAppsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ZapstoreApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val catalogDatabase by lazy { CatalogBootstrapper(this).open() }
    val compactStore by lazy { CompactEventStore(catalogDatabase) }
    val catalogRepository: CatalogRepository by lazy {
        IoliteCatalogRepository(this) { compactStore }
    }
    val catalogSyncRepository by lazy {
        CatalogSyncRepository(
            database = catalogDatabase,
            store = compactStore,
            client = CatalogSyncClient(Catalog.updatesUrl),
            installedApps = { InstalledAppsProvider(this).installedApps() },
            trustedPubkey = Catalog.catalogRelayPubkey,
            purpleQuartz = { catalogRepository.purpleQuartz() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!Catalog.catalogLocalOnly) {
                    catalogRepository.setRelayTrafficEnabled(true)
                }
                applicationScope.launch { catalogSyncRepository.sync() }
            }
            override fun onStop(owner: LifecycleOwner) = catalogRepository.setRelayTrafficEnabled(false)
        })
    }
}
