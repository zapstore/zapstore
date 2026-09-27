package dev.zapstore.app.transport

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.util.concurrent.CopyOnWriteArrayList

class NetworkRuntime(
    context: Context,
    private val scope: CoroutineScope,
    private val engineFactory: (Context) -> ArtiEngine? = { ctx ->
        runCatching { EmbeddedArtiEngine(ctx) }.getOrNull()
    },
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val resetListeners = CopyOnWriteArrayList<() -> Unit>()
    private val direct = AppTransports.directTransport()
    private val directImages = AppTransports.directImageTransport()
    private var arti: ArtiEngine? = null
    private var torTransport: AppTransport? = null
    private var torImages: AppTransport? = null

    private val _mode = MutableStateFlow(loadMode())
    val mode: StateFlow<NetworkMode> = _mode.asStateFlow()

    private val _torState = MutableStateFlow(
        if (_mode.value.usesTor) TorRuntimeState.Bootstrapping else TorRuntimeState.Stopped,
    )
    val torState: StateFlow<TorRuntimeState> = _torState.asStateFlow()

    val remoteReady: StateFlow<Boolean> = combine(mode, torState, ::remoteImagesReady)
        .stateIn(scope, SharingStarted.Eagerly, remoteImagesReady(_mode.value, _torState.value))

    val transport: AppTransport = AppTransports.split(
        mode = { _mode.value },
        torState = { _torState.value },
        direct = direct,
        tor = { torTransport },
    )

    val images: AppTransport = AppTransports.split(
        mode = { _mode.value },
        torState = { _torState.value },
        direct = directImages,
        tor = { torImages },
    )

    val http get() = transport.http
    val webSocket get() = transport.webSocket
    val callFactory: Call.Factory get() = images.callFactory

    init {
        if (_mode.value.usesTor) startArti()
    }

    fun onConnectionsReset(listener: () -> Unit) {
        resetListeners += listener
    }

    fun setMode(mode: NetworkMode) {
        if (mode == _mode.value) return
        val wasUsingTor = _mode.value.usesTor
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
        if (!mode.usesTor) {
            _torState.value = TorRuntimeState.Stopped
            scope.launch(Dispatchers.IO) {
                lock.withLock {
                    closeConnections()
                    stopArtiLocked(clearCache = true)
                }
                notifyReset()
            }
        } else if (!wasUsingTor) {
            startArti()
        } else {
            notifyReset()
        }
    }

    fun setForeground(foreground: Boolean) {
        if (!_mode.value.usesTor) return
        val state = _torState.value
        if (foreground) {
            if (state == TorRuntimeState.Dormant) _torState.value = TorRuntimeState.Ready
            if (state == TorRuntimeState.Failed || state == TorRuntimeState.Stopped) startArti()
        } else if (state == TorRuntimeState.Ready) {
            _torState.value = TorRuntimeState.Dormant
            scope.launch(Dispatchers.IO) {
                lock.withLock { closeConnections() }
            }
        }
    }

    private fun startArti() {
        _torState.value = TorRuntimeState.Bootstrapping
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                if (!_mode.value.usesTor) return@withLock
                try {
                    val engine = arti ?: engineFactory(appContext) ?: throw TorUnavailableException(
                        "Arti is not available; refusing Direct fallback",
                    )
                    try {
                        engine.start()
                    } catch (failure: Throwable) {
                        runCatching { engine.destroy() }
                        throw failure
                    }
                    arti = engine
                    closeTorTransport()
                    torTransport = AppTransports.torTransport(engine.socksPort)
                    torImages = AppTransports.torImageTransport(engine.socksPort)
                    if (_mode.value.usesTor) {
                        _torState.value = TorRuntimeState.Ready
                        notifyReset()
                    }
                } catch (_: Throwable) {
                    stopArtiLocked()
                    if (_mode.value.usesTor) _torState.value = TorRuntimeState.Failed
                }
            }
        }
    }

    /** Drops the Tor client and deletes `files/arti/cache`. Restarts Arti if the mode still uses Tor. */
    suspend fun clearArtiCache() {
        withContext(Dispatchers.IO) {
            lock.withLock {
                closeTorTransport()
                stopArtiLocked(clearCache = true)
            }
        }
        if (_mode.value.usesTor) startArti()
    }

    private fun stopArtiLocked(clearCache: Boolean = false) {
        closeTorTransport()
        runCatching { arti?.stop() }
        runCatching { arti?.destroy() }
        arti = null
        if (clearCache) deleteArtiCache(EmbeddedArtiEngine.cacheDir(appContext))
    }

    private fun closeTorTransport() {
        val current = torTransport
        val currentImages = torImages
        torTransport = null
        torImages = null
        (current?.http as? OkHttpAppTransport)?.closeConnections()
        (current?.webSocket as? OkHttpAppTransport)?.closeConnections()
        (currentImages?.http as? OkHttpAppTransport)?.closeConnections()
    }

    private fun closeConnections() {
        (direct.http as? OkHttpAppTransport)?.closeConnections()
        (torTransport?.http as? OkHttpAppTransport)?.closeConnections()
        (torTransport?.webSocket as? OkHttpAppTransport)?.closeConnections()
    }

    private fun notifyReset() {
        resetListeners.forEach { runCatching(it) }
    }

	private fun loadMode(): NetworkMode {
		val debug = appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
		val fallback = if (debug) NetworkMode.DirectOnly else NetworkMode.TorOnly
		return NetworkMode.fromStored(prefs.getString(KEY_MODE, fallback.name))
	}

    private companion object {
        const val PREFS = "network"
        const val KEY_MODE = "mode"
    }
}
