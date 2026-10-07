package dev.zapstore.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.R
import dev.zapstore.app.ZapstoreApplication
import dev.zapstore.app.catalog.AvailableUpdate
import dev.zapstore.app.catalog.CatalogSync
import dev.zapstore.app.catalog.InstalledApp
import dev.zapstore.app.catalog.SyncStatus
import dev.zapstore.app.catalog.updateGroups
import dev.zapstore.iolite.AppRecord
import dev.zapstore.app.install.InstallOrigin
import dev.zapstore.app.install.InstallTurn
import dev.zapstore.app.install.awaitTerminal
import dev.zapstore.app.install.installFailure
import dev.zapstore.app.install.isSilentUpdate
import dev.zapstore.app.install.stageAndCommit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class UpdateAllUi(
    val ready: Int = 0,
    val skipped: Int = 0,
    val confirm: Boolean = false,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val currentId: String? = null,
    val currentName: String? = null,
    val received: Long = 0,
    val size: Long? = null,
    val failures: Map<String, String> = emptyMap(),
)

data class UpdatesUiState(
    val status: SyncStatus = SyncStatus(),
    val updates: List<AvailableUpdate> = emptyList(),
    val manualUpdates: List<AvailableUpdate> = emptyList(),
    val installedApps: List<AppRecord> = emptyList(),
    val otherInstalled: List<InstalledApp> = emptyList(),
    val loaded: Boolean = false,
    val updateAll: UpdateAllUi = UpdateAllUi(),
)

class UpdatesViewModel(
    private val zapstore: ZapstoreApplication,
    private val catalogSync: CatalogSync,
) : ViewModel() {
    private val batch = MutableStateFlow(UpdateAllUi())

    val uiState: StateFlow<UpdatesUiState> = combine(
        catalogSync.status,
        catalogSync.updates,
        catalogSync.installedApps,
        zapstore.iolite.observeApps(),
        batch,
    ) { status, updates, installed, apps, batch ->
        val groups = updateGroups(updates, installed, apps) { update -> update.isReady() }
        UpdatesUiState(
            status = status,
            updates = groups.updates,
            manualUpdates = groups.manualUpdates,
            installedApps = groups.installedApps,
            otherInstalled = groups.otherInstalled,
            loaded = true,
            updateAll = batch.copy(ready = groups.updates.size, skipped = groups.manualUpdates.size),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UpdatesUiState())

    fun sync() {
        viewModelScope.launch { catalogSync.sync() }
    }

    fun prepareUpdateAll() {
        val state = uiState.value
        if (state.updateAll.running || state.updateAll.ready == 0) return
        batch.update {
            it.copy(confirm = true, failures = emptyMap(), received = 0, size = null)
        }
    }

    fun dismissUpdateAll() {
        batch.update { it.copy(confirm = false) }
    }

    fun confirmUpdateAll() {
        val ready = uiState.value.updates
        if (ready.isEmpty() || batch.value.running) return
        if (!zapstore.packageManager.canRequestPackageInstalls()) {
            batch.update { it.copy(confirm = false) }
            return
        }
        batch.update {
            it.copy(confirm = false, running = true, done = 0, total = ready.size, failures = emptyMap())
        }
        viewModelScope.launch {
            val failures = mutableMapOf<String, String>()
            try {
                ready.forEachIndexed { index, update ->
                    if (!isActive) return@launch
                    batch.update {
                        it.copy(
                            done = index,
                            currentId = update.app.appId,
                            currentName = update.app.name,
                            received = 0,
                            size = null,
                            failures = failures.toMap(),
                        )
                    }
                    val failure = runOne(update, bulk = ready.size > 1)
                    if (failure != null) failures[update.app.appId] = failure
                    else catalogSync.refreshInstalled()
                }
            } finally {
                batch.update {
                    it.copy(
                        running = false,
                        currentId = null,
                        currentName = null,
                        received = 0,
                        size = null,
                        done = it.total,
                        failures = failures.toMap(),
                    )
                }
            }
        }
    }

    private suspend fun runOne(update: AvailableUpdate, bulk: Boolean): String? = try {
        val result = InstallTurn.exclusive {
            awaitTerminal(update.app.appId) {
                stageAndCommit(
                    context = zapstore,
                    calls = zapstore.network.transport.callFactory,
                    app = update.app,
                    origin = InstallOrigin(
                        installed = true,
                        versionCode = update.installedVersionCode,
                        installingPackage = update.installingPackage,
                        updateOwner = update.updateOwner,
                        certificates = update.certificateHashes,
                    ),
                    ourPackage = zapstore.packageName,
                    bulk = bulk,
                ) { read, total ->
                    batch.update { it.copy(received = read, size = total) }
                }
            }
        }
        if (result.success) null else result.message ?: zapstore.getString(R.string.install_timeout)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        zapstore.installFailure(e)
    }

    private fun AvailableUpdate.isReady(): Boolean = isSilentUpdate(
        ourPackage = zapstore.packageName,
        packageId = app.appId,
        installingPackage = installingPackage,
        updateOwner = updateOwner,
        listedVersionCode = this.app.versionCode,
        installedVersionCode = installedVersionCode,
        listedHash = this.app.apkHash,
        listedCertificate = this.app.certificateHash,
    )

    companion object {
        fun factory(app: ZapstoreApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { UpdatesViewModel(app, app.catalogSync) }
        }
    }
}
