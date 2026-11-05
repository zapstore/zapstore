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
import dev.zapstore.app.catalog.ManualReason
import dev.zapstore.app.catalog.manualReason
import dev.zapstore.app.catalog.StoreApp
import dev.zapstore.app.catalog.SyncStatus
import dev.zapstore.app.catalog.updateGroups
import dev.zapstore.iolite.AppFilter
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.normalizeSearchQuery
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.app.install.InstallTask
import dev.zapstore.app.install.SessionInstaller
import dev.zapstore.app.install.isSilentUpdate
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
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Silent updates install without a prompt; manual ones make Android ask once each. */
enum class UpdateGroup { Automatic, Manual }

/** The Update all sheet: how many it would start, for which group. */
data class UpdateAllUi(
    val group: UpdateGroup = UpdateGroup.Automatic,
    val ready: Int = 0,
    val confirm: Boolean = false,
)

/** An installed app whose updates are ignored, and the store Android says installed it. */
data class IgnoredApp(val app: AppRecord, val installer: String?)

/** A catalog app another store keeps current, with that store's name. */
data class OtherStoreApp(val entry: StoreApp, val storeLabel: String)

data class UpdatesUiState(
    val status: SyncStatus = SyncStatus(),
    /** Epoch of each active catalog's bundle stored here, in the user's order; 0 before its first import. */
    val catalogEpochs: List<Long> = emptyList(),
    val updates: List<AvailableUpdate> = emptyList(),
    val manualUpdates: List<AvailableUpdate> = emptyList(),
    /** Why each manual update needs Android's dialog, by app id. */
    val manualReasons: Map<String, ManualReason> = emptyMap(),
    val installedApps: List<AppRecord> = emptyList(),
    val otherStores: List<OtherStoreApp> = emptyList(),
    val otherInstalled: List<InstalledApp> = emptyList(),
    val ignored: List<IgnoredApp> = emptyList(),
    val profiles: Map<String, ProfileRecord> = emptyMap(),
    val loaded: Boolean = false,
    val updateAll: UpdateAllUi = UpdateAllUi(),
    /** Installs in flight or waiting, by app id, from [dev.zapstore.app.install.Installer]. */
    val tasks: Map<String, InstallTask> = emptyMap(),
    /** Ranked matches among installed and ignored apps; null until a query is submitted. */
    val searchResults: List<AppRecord>? = null,
    /** Installed apps outside the catalog whose name or package matches the query; they have no vectors. */
    val otherSearchResults: List<InstalledApp> = emptyList(),
)

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

    private val myAppIds: Flow<Set<String>> =
        combine(catalogSync.installedApps, ignoredIds) { installed, ignored ->
            buildSet {
                installed.mapTo(this) { it.packageId }
                addAll(ignored)
            }
        }.distinctUntilChanged()

    /** Listings of installed and ignored apps: everything this screen shows. The whole catalog is tens of MB. */
    private val myApps: Flow<List<AppRecord>> =
        myAppIds.flatMapLatest { ids -> zapstore.iolite.observeApps(AppFilter(appIds = ids)) }
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 1)

    private val profiles = myApps.authorProfiles(zapstore.iolite)

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
        combine(catalogSync.status, catalogSync.updates, profiles, zapstore.iolite.observeCatalogs()) { status, _, profiles, catalogs ->
            Triple(status, profiles, catalogs.map { it.epoch })
        },
        catalogSync.installedApps,
        myApps,
        ignoredIds,
        combine(batch, search, searchQuery, zapstore.installer.tasks) { batch, search, typed, tasks -> Quad(batch, search, typed, tasks) },
    ) { (status, profiles, epochs), installed, apps, ignored, (batch, search, typed, tasks) ->
        val groups = updateGroups(installed, apps, ignored.toSet(), zapstore.packageName) { update -> update.isReady() }
        val byId = apps.associateBy { it.appId }
        val installedById = installed.associateBy { it.packageId }
        UpdatesUiState(
            profiles = profiles,
            ignored = ignored.mapNotNull { id ->
                byId[id]?.let { IgnoredApp(it, installerLabel(installedById[id]?.installingPackage)) }
            },
            status = status,
            catalogEpochs = epochs,
            updates = groups.updates,
            manualUpdates = groups.manualUpdates,
            manualReasons = groups.manualUpdates.mapNotNull { update ->
                update.manualReason(zapstore.packageName, SessionInstaller.silentSupported)?.let { update.app.appId to it }
            }.toMap(),
            installedApps = groups.installedApps,
            otherStores = groups.otherStores.map { OtherStoreApp(it, installerLabel(it.store) ?: it.store) },
            otherInstalled = groups.otherInstalled,
            loaded = true,
            updateAll = batch,
            tasks = tasks,
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

    fun ignoreUpdates(appId: String) {
        zapstore.lists.setIgnored(appId, true)
        Toasts.show(zapstore.getString(R.string.toast_ignored))
    }

    fun pause(appId: String) = zapstore.installer.pause(appId)

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
        val ready = uiState.value.startable(group).size
        if (ready == 0) return
        batch.update { it.copy(group = group, ready = ready, confirm = true) }
    }

    fun dismissUpdateAll() {
        batch.update { it.copy(confirm = false) }
    }

    /** Starts every idle update in the group. Downloads run a few at a time; Android's dialogs follow one by one. */
    fun confirmUpdateAll() {
        val ready = uiState.value.startable(batch.value.group)
        batch.update { it.copy(confirm = false) }
        ready.forEach { zapstore.installer.install(it.app, bulk = ready.size > 1) }
    }

    /** Starts, resumes or shows Android's waiting dialog for one update. */
    fun updateOne(update: AvailableUpdate) = zapstore.installer.install(update.app)

    private fun AvailableUpdate.isReady(): Boolean = SessionInstaller.silentSupported && isSilentUpdate(
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

private fun UpdatesUiState.startable(group: UpdateGroup): List<AvailableUpdate> = when (group) {
    UpdateGroup.Automatic -> updates
    UpdateGroup.Manual -> manualUpdates + otherStores.mapNotNull { it.entry.update }
}.filter { tasks[it.app.appId]?.busy != true }

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
