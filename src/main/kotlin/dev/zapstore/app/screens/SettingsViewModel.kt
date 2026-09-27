package dev.zapstore.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.catalog.CatalogSync
import dev.zapstore.app.catalog.SyncStatus
import dev.zapstore.app.transport.NetworkMode
import dev.zapstore.app.transport.NetworkRuntime
import dev.zapstore.app.transport.TorRuntimeState
import dev.zapstore.iolite.CatalogRecord
import dev.zapstore.iolite.Iolite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val status: SyncStatus = SyncStatus(),
    val catalogUrl: String = "",
    val catalogEpoch: Long = 0,
    val appCount: Int = 0,
    val localStorageBytes: Long = 0,
    val networkMode: NetworkMode = NetworkMode.TorOnly,
    val torState: TorRuntimeState = TorRuntimeState.Stopped,
)

class SettingsViewModel(
    iolite: Iolite,
    private val catalogSync: CatalogSync,
    private val network: NetworkRuntime,
    private val measureLocalStorage: () -> Long,
) : ViewModel() {
    private val localStorageBytes = MutableStateFlow(0L)

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            catalogSync.status,
            iolite.observeCatalogs(),
            iolite.observeApps().map { it.size },
            localStorageBytes,
        ) { status, catalogs, appCount, storageBytes ->
            SettingsSnapshot(status, catalogs.firstOrNull(), appCount, storageBytes)
        },
        network.mode,
        network.torState,
    ) { snapshot, mode, torState ->
        SettingsUiState(
            status = snapshot.status,
            catalogUrl = snapshot.catalog?.relays?.joinToString("\n") { it.url }.orEmpty(),
            catalogEpoch = snapshot.catalog?.epoch ?: 0,
            appCount = snapshot.appCount,
            localStorageBytes = snapshot.localStorageBytes,
            networkMode = mode,
            torState = torState,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState())

    init {
        viewModelScope.launch {
            combine(iolite.observeApps(), iolite.observeCatalogs(), catalogSync.status) { _, _, _ -> }
                .collect { refreshLocalStorage() }
        }
    }

    fun setNetworkMode(mode: NetworkMode) = network.setMode(mode)

    fun sync() {
        viewModelScope.launch { catalogSync.sync() }
    }

    fun wipe() {
        viewModelScope.launch {
            catalogSync.wipe()
            network.clearArtiCache()
            refreshLocalStorage()
        }
    }

    private suspend fun refreshLocalStorage() {
        localStorageBytes.value = withContext(Dispatchers.IO) { measureLocalStorage() }
    }

    companion object {
        fun factory(
            iolite: Iolite,
            catalogSync: CatalogSync,
            network: NetworkRuntime,
            measureLocalStorage: () -> Long,
        ): ViewModelProvider.Factory =
            viewModelFactory { initializer { SettingsViewModel(iolite, catalogSync, network, measureLocalStorage) } }
    }
}

private data class SettingsSnapshot(
    val status: SyncStatus,
    val catalog: CatalogRecord?,
    val appCount: Int,
    val localStorageBytes: Long,
)
