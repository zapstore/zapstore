package dev.zapstore.app.screens

import dev.zapstore.app.Toasts
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
import dev.zapstore.app.catalog.StoreApp
import dev.zapstore.app.catalog.SyncStatus
import dev.zapstore.app.catalog.updateGroups
import dev.zapstore.iolite.AppFilter
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.normalizeSearchQuery
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.app.install.InstallOrigin
import dev.zapstore.app.install.InstallTurn
import dev.zapstore.app.install.awaitTerminal
import dev.zapstore.app.install.installFailure
import dev.zapstore.app.install.isSilentUpdate
import dev.zapstore.app.install.stageAndCommit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Silent updates install without a prompt; manual ones make Android ask once each. */
enum class UpdateGroup { Automatic, Manual }

data class UpdateAllUi(
    val group: UpdateGroup = UpdateGroup.Automatic,
    val ready: Int = 0,
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

/** An installed app whose updates are ignored, and the store Android says installed it. */
data class IgnoredApp(val app: AppRecord, val installer: String?)

/** A catalog app another store keeps current, with that store's name. */
data class OtherStoreApp(val entry: StoreApp, val storeLabel: String)

data class UpdatesUiState(
    val status: SyncStatus = SyncStatus(),
    val updates: List<AvailableUpdate> = emptyList(),
    val manualUpdates: List<AvailableUpdate> = emptyList(),
    val installedApps: List<AppRecord> = emptyList(),
    val otherStores: List<OtherStoreApp> = emptyList(),
    val otherInstalled: List<InstalledApp> = emptyList(),
    val ignored: List<IgnoredApp> = emptyList(),
    val profiles: Map<String, ProfileRecord> = emptyMap(),
    val loaded: Boolean = false,
    val updateAll: UpdateAllUi = UpdateAllUi(),
    /** Ranked matches among installed and ignored apps; null until a query is submitted. */
    val searchResults: List<AppRecord>? = null,
    /** Installed apps outside the catalog whose name or package matches the query; they have no vectors. */
    val otherSearchResults: List<InstalledApp> = emptyList(),
) {
    val newCount: Int get() = updates.size + manualUpdates.size + otherStores.count { it.entry.update != null }
    val installedCount: Int get() = installedApps.size + otherStores.count { it.entry.update == null } + otherInstalled.size
}

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModel(
    private val zapstore: ZapstoreApplication,
    private val catalogSync: CatalogSync,
    private val encodeQuery: (String) -> ByteArray?,
) : ViewModel() {
    private val batch = MutableStateFlow(UpdateAllUi())
    private val searchQuery = MutableStateFlow("")
    private val installerLabels = HashMap<String, String?>()
    private val ignoredIds = zapstore.lists.ignored
    private val profiles = zapstore.iolite.observeApps().authorProfiles(zapstore.iolite)

    private val myAppIds: Flow<Set<String>> =
        combine(catalogSync.installedApps, ignoredIds) { installed, ignored ->
            buildSet {
                installed.mapTo(this) { it.packageId }
                addAll(ignored)
            }
        }.distinctUntilChanged()

    /** Same ranking and vectors as Home search, over the user's apps only. */
    private val search: Flow<List<AppRecord>?> =
        combine(searchQuery, myAppIds, ::Pair).flatMapLatest { (typed, ids) ->
            val query = normalizeSearchQuery(typed)
            if (query.length < MIN_SEARCH_LENGTH) return@flatMapLatest flowOf(null)
            flow {
                val vector = withContext(Dispatchers.Default) { runCatching { encodeQuery(query) }.getOrNull() }
                val filter = AppFilter(appIds = ids, search = query, queryVector = vector)
                emitAll(zapstore.iolite.observeApps(filter))
            }
        }

    val uiState: StateFlow<UpdatesUiState> = combine(
        combine(catalogSync.status, catalogSync.updates, profiles, ::Triple),
        catalogSync.installedApps,
        zapstore.iolite.observeApps(),
        ignoredIds,
        combine(batch, search, searchQuery, ::Triple),
    ) { (status, _, profiles), installed, apps, ignored, (batch, search, typed) ->
        val groups = updateGroups(installed, apps, ignored.toSet(), zapstore.packageName) { update -> update.isReady() }
        val byId = apps.associateBy { it.appId }
        val installedById = installed.associateBy { it.packageId }
        UpdatesUiState(
            profiles = profiles,
            ignored = ignored.mapNotNull { id ->
                byId[id]?.let { IgnoredApp(it, installerLabel(installedById[id]?.installingPackage)) }
            },
            status = status,
            updates = groups.updates,
            manualUpdates = groups.manualUpdates,
            installedApps = groups.installedApps,
            otherStores = groups.otherStores.map { OtherStoreApp(it, installerLabel(it.store) ?: it.store) },
            otherInstalled = groups.otherInstalled,
            loaded = true,
            updateAll = batch,
            searchResults = search,
            otherSearchResults = if (search == null) emptyList() else groups.otherInstalled.matching(normalizeSearchQuery(typed)),
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UpdatesUiState())

    fun sync() = catalogSync.request(force = true)

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun resumeUpdates(appId: String) {
        zapstore.lists.setIgnored(appId, false)
        Toasts.show(zapstore.getString(R.string.toast_unignored))
    }

    private fun installerLabel(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return installerLabels.getOrPut(packageName) {
            runCatching {
                val pm = zapstore.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
        }
    }

    fun prepareUpdateAll(group: UpdateGroup) {
        val state = uiState.value
        val ready = state.group(group).size
        if (state.updateAll.running || ready == 0) return
        batch.update {
            it.copy(group = group, ready = ready, confirm = true, failures = emptyMap(), received = 0, size = null)
        }
    }

    fun dismissUpdateAll() {
        batch.update { it.copy(confirm = false) }
    }

    fun confirmUpdateAll() = runBatch(uiState.value.group(batch.value.group))

    /** Updates one app; a manual one waits for Android's prompt. */
    fun updateOne(update: AvailableUpdate) {
        val state = uiState.value
        if (update in state.updates || update in state.manualUpdates) runBatch(listOf(update))
    }

    private fun runBatch(ready: List<AvailableUpdate>) {
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
                    proofOf = zapstore.iolite::proof,
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
        private const val MIN_SEARCH_LENGTH = 2

        fun factory(app: ZapstoreApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { UpdatesViewModel(app, app.catalogSync, app.queryEncoder::encode) }
        }
    }
}

private fun List<InstalledApp>.matching(query: String): List<InstalledApp> =
    filter { it.label.contains(query, ignoreCase = true) || it.packageId.contains(query, ignoreCase = true) }

private fun UpdatesUiState.group(group: UpdateGroup): List<AvailableUpdate> = when (group) {
    UpdateGroup.Automatic -> updates
    UpdateGroup.Manual -> manualUpdates
}
