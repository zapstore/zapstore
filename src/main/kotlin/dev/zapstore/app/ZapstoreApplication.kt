package dev.zapstore.app

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import dev.zapstore.app.components.ZapMotion
import dev.zapstore.app.components.animationsEnabled
import coil3.annotation.ExperimentalCoilApi
import coil3.network.ConnectivityChecker
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.zapstore.app.account.AccountStore
import dev.zapstore.app.account.Nip55Bridge
import dev.zapstore.app.catalog.CatalogSync
import dev.zapstore.app.catalog.CatalogSyncWorker
import dev.zapstore.app.install.BackgroundUpdateWorker
import dev.zapstore.app.install.Installer
import dev.zapstore.app.catalog.installedApps
import dev.zapstore.app.search.QueryEncoder
import dev.zapstore.app.transport.NetworkMode
import dev.zapstore.app.transport.NetworkRuntime
import dev.zapstore.iolite.DeviceProfile
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.IoliteConfig
import dev.zapstore.iolite.Kinds
import dev.zapstore.iolite.LocalSigner
import dev.zapstore.iolite.Query
import dev.zapstore.iolite.QueryOptions
import dev.zapstore.iolite.RemoteMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide singletons: network, the Iolite client, and catalog sync. Screens get these from here. */
class ZapstoreApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var secrets: SecretStore
        private set
    lateinit var deviceSigner: LocalSigner
        private set
    lateinit var settings: DeviceSettings
        private set
    lateinit var account: AccountStore
        private set
    lateinit var network: NetworkRuntime
        private set
    lateinit var iolite: Iolite
        private set
    lateinit var queryEncoder: QueryEncoder
        private set
    lateinit var catalogSync: CatalogSync
        private set
    lateinit var lists: DeviceLists
        private set
    lateinit var keyBackup: DeviceKeyBackup
        private set
    lateinit var wallet: Wallet
        private set
    lateinit var onboarding: Onboarding
        private set
    lateinit var bugReports: BugReports
        private set
    lateinit var installer: Installer
        private set
    private var reportsSent = false
    private var foregroundSync: Job? = null

    /** This install carried a device key over from the Flutter app. */
    var migratedFromFlutter = false
        private set

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        secrets = SecretStore(this)
        val carriedFlutterKey = FlutterMigration.run(this, secrets)
        migratedFromFlutter = carriedFlutterKey
        onboarding = Onboarding(this)
        deviceSigner = DeviceKeys.loadOrCreate(this, secrets)
        account = AccountStore(this, secrets, Nip55Bridge(this))
        wallet = Wallet(secrets)
        scope.launch(Dispatchers.IO) {
            FlutterMigration.cleanUp(this@ZapstoreApplication)
        }
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        settings = DeviceSettings(this, scope, if (debuggable) NetworkMode.DirectOnly else NetworkMode.TorOnly)
        network = NetworkRuntime(this, scope, settings.state.value.networkMode)
        queryEncoder = QueryEncoder(this)
        registerComponentCallbacks(queryEncoder)
        scope.launch(Dispatchers.IO) { runCatching { queryEncoder.prepare() } }
        scope.launch { Emoji.replace(":zap:") }
        iolite = Iolite.create(
            context = this,
            parentScope = scope,
            config = IoliteConfig(
                databaseName = "zapstore.db",
                pruneRules = mapOf(Kinds.Zap to AppConfig.zapRetention),
                indexerRelays = AppConfig.indexerRelays,
                relayMonitors = AppConfig.relayMonitors,
                monitorRelays = AppConfig.monitorRelays,
            ),
            http = network.http,
            webSocket = network.webSocket,
            signer = deviceSigner,
            deviceSigner = deviceSigner,
            writeRelays = AppConfig.writeRelays,
            device = DeviceProfile(abis = AppConfig.supportedAbis, sdk = Build.VERSION.SDK_INT),
        )
        lists = DeviceLists(iolite, scope)
        bugReports = BugReports(
            this,
            iolite,
            recipient = { if (::catalogSync.isInitialized) catalogSync.defaultCatalogKey() else null },
            enabled = { settings.state.value.bugReports },
        )
        AppLog.onError = bugReports::record
        keyBackup = DeviceKeyBackup(this, secrets, iolite, account)
        catalogSync = CatalogSync(
            iolite,
            scope,
            ignored = lists.ignoredLoaded,
            readInstalledApps = ::installedApps,
            readBundledSnapshot = {
                runCatching { assets.open(BUNDLED_CATALOG_DELTA).use { it.readBytes() } }.getOrDefault(ByteArray(0))
            },
            useOnion = { network.mode.value.usesTor },
            ourPackage = packageName,
        )
        installer = Installer(this, scope, { network.transport.callFactory }, iolite::proof)
        scope.launch {
            combine(iolite.observeApps(), catalogSync.installedApps, ::Pair)
                .debounce(1_000)
                .collect { (apps, installed) -> installer.prune(apps, installed) }
        }
        watchPackages()
        settings.attach(iolite)
        scope.launch { settings.state.map { it.networkMode }.distinctUntilChanged().collect(network::setMode) }
        network.onConnectionsReset {
            iolite.refreshConnections()
            SingletonImageLoader.get(this).memoryCache?.clear()
        }

        SingletonImageLoader.setSafe { context ->
            @OptIn(ExperimentalCoilApi::class)
            ImageLoader.Builder(context)
                .crossfade(if (context.animationsEnabled()) ZapMotion.BASE else 0)
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
                catalogSync.request()
                foregroundSync?.cancel()
                foregroundSync = scope.launch { syncWhileOpen() }
                if (!reportsSent) {
                    reportsSent = true
                    scope.launch(Dispatchers.IO) { bugReports.sendPending() }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                foregroundSync?.cancel()
                iolite.setRelayTrafficEnabled(false)
                network.setForeground(false)
            }
        })
        scope.launch {
            settings.state.map { it.syncInterval }.distinctUntilChanged().collect { interval ->
                CatalogSyncWorker.schedule(this@ZapstoreApplication, interval)
            }
        }
        scope.launch {
            settings.state
                .distinctUntilChanged { a, b ->
                    a.backgroundUpdateInterval == b.backgroundUpdateInterval &&
                        a.backgroundAutoUpdatesAnyNetwork == b.backgroundAutoUpdatesAnyNetwork
                }
                .collect { BackgroundUpdateWorker.apply(this@ZapstoreApplication, it) }
        }
        if (DeviceKeys.takeRestorePending(this) || carriedFlutterKey) restoreDevicePrivate()
    }

    /** Installs, updates and removals from any source reach My apps and the app page without polling. */
    private fun watchPackages() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = catalogSync.refreshInstalled()
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    /** Intervals under WorkManager's floor sync on a timer while Zapstore is in the foreground. */
    private suspend fun syncWhileOpen() {
        settings.state.map { it.syncInterval }.distinctUntilChanged().collectLatest { interval ->
            if (interval.minutes >= CatalogSyncWorker.MIN_PERIODIC_MINUTES) return@collectLatest
            while (true) {
                delay(interval.minutes * 60_000L)
                catalogSync.request(force = true)
            }
        }
    }

    /**
     * Fetches the carried key's device state and lists once, per `iolite/SPEC.md#device-private-events`.
     * Collected only for the one-shot window; the rows land in SQLite and the settings flows react.
     */
    private fun restoreDevicePrivate() {
        scope.launch {
            runCatching {
                iolite.query(
                    Query.devicePrivate(deviceSigner.publicKey),
                    QueryOptions.remote(relays = AppConfig.writeRelays + AppConfig.catalogRelay, remoteMode = RemoteMode.OneShot()),
                ).first { it.settled }
            }
        }
    }

    private companion object {
        const val BUNDLED_CATALOG_DELTA = "bundle-0-1.tar.zst"
    }
}
