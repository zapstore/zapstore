package dev.zapstore.app

import android.app.Application
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ConnectivityChecker
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.zapstore.app.catalog.CatalogSync
import dev.zapstore.app.catalog.installedApps
import dev.zapstore.app.search.QueryEncoder
import dev.zapstore.app.transport.NetworkRuntime
import dev.zapstore.iolite.DeviceProfile
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.IoliteConfig
import dev.zapstore.iolite.Kinds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide singletons: network, the Iolite client, and catalog sync. Screens get these from here. */
class ZapstoreApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var network: NetworkRuntime
        private set
    lateinit var iolite: Iolite
        private set
    lateinit var queryEncoder: QueryEncoder
        private set
    lateinit var catalogSync: CatalogSync
        private set

    override fun onCreate() {
        super.onCreate()
        network = NetworkRuntime(this, scope)
        queryEncoder = QueryEncoder(this)
        registerComponentCallbacks(queryEncoder)
        val deviceSigner = DeviceKeys.loadOrCreate(this)
        iolite = Iolite.create(
            context = this,
            parentScope = scope,
            config = IoliteConfig(
                databaseName = "zapstore.db",
                pruneRules = mapOf(Kinds.Zap to AppConfig.zapRetention),
            ),
            http = network.http,
            webSocket = network.webSocket,
            signer = deviceSigner,
            deviceSigner = deviceSigner,
            writeRelays = AppConfig.writeRelays,
            device = DeviceProfile(abis = AppConfig.supportedAbis, sdk = Build.VERSION.SDK_INT),
        )
        catalogSync = CatalogSync(
            iolite,
            scope,
            readInstalledApps = ::installedApps,
            readBundledSnapshot = {
                runCatching { assets.open(BUNDLED_CATALOG_DELTA).use { it.readBytes() } }.getOrDefault(ByteArray(0))
            },
            useOnion = { network.mode.value.usesTor },
        )
        network.onConnectionsReset {
            iolite.refreshConnections()
            SingletonImageLoader.get(this).memoryCache?.clear()
        }

        SingletonImageLoader.setSafe { context ->
            @OptIn(ExperimentalCoilApi::class)
            ImageLoader.Builder(context)
                .components {
                    add(
                        OkHttpNetworkFetcherFactory(
                            callFactory = { network.callFactory },
                            connectivityChecker = { ConnectivityChecker.ONLINE },
                        ),
                    )
                }
                .build()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                network.setForeground(true)
                iolite.setRelayTrafficEnabled(true)
                scope.launch { catalogSync.sync() }
            }

            override fun onStop(owner: LifecycleOwner) {
                iolite.setRelayTrafficEnabled(false)
                network.setForeground(false)
            }
        })
    }

    private companion object {
        const val BUNDLED_CATALOG_DELTA = "bundle-0-1.tar.zst"
    }
}
