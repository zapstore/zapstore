package dev.zapstore.app.screens

import dev.zapstore.app.Toasts
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
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
import dev.zapstore.app.catalog.otherStore
import dev.zapstore.app.catalog.StoreApp
import dev.zapstore.app.catalog.SyncStatus
import dev.zapstore.app.catalog.updateGroups
import dev.zapstore.iolite.AppFilter
import dev.zapstore.iolite.SearchFact
import dev.zapstore.iolite.excludeBit
import dev.zapstore.iolite.hardBit
import dev.zapstore.iolite.repositoryQuery
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.normalizeSearchQuery
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.app.install.InstallTask
import dev.zapstore.app.install.SessionInstaller
import dev.zapstore.app.install.TaskPhase
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

/** A catalog app another store keeps current with its own key, with that store's name. */
data class OtherStoreApp(val entry: StoreApp, val storeLabel: String)

data class UpdatesUiState(
    val status: SyncStatus = SyncStatus(),
    /** Epoch of each active catalog's bundle stored here, in the user's order; 0 before its first import. */
    val catalogEpochs: List<Long> = emptyList(),
    val updates: List<AvailableUpdate> = emptyList(),
    val manualUpdates: List<AvailableUpdate> = emptyList(),
    /** Why each manual update needs Android's dialog, by app id. */
    val manualReasons: Map<String, ManualReason> = emptyMap(),
    /** The store that updates a manual update's app today, by app id. This update hands the app to Zapstore. */
    val manualStores: Map<String, String> = emptyMap(),
    val installedApps: List<AppRecord> = emptyList(),
    /** Apps with an install in flight, waiting or failed that no update row already carries. */
    val installing: List<AppRecord> = emptyList(),
    val otherStores: List<OtherStoreApp> = emptyList(),
    val otherInstalled: List<InstalledApp> = emptyList(),
    val ignored: List<IgnoredApp> = emptyList(),
    val profiles: Map<String, ProfileRecord> = emptyMap(),
    val loaded: Boolean = false,
    val updateAll: UpdateAllUi = UpdateAllUi(),
    /** Installs in flight or waiting, by app id, from [dev.zapstore.app.install.Installer]. */
    val tasks: Map<String, InstallTask> = emptyMap(),
    /** What the search field holds. */
    val searchQuery: String = "",
    /** The query last submitted with Enter; empty until then. */
    val submittedQuery: String = "",
    /** Ranked matches among installed and ignored apps; null until a query is submitted. */
    val searchResults: List<AppRecord>? = null,
    /** Active hard filters, as on Home. */
    val searchFacts: List<SearchFact> = emptyList(),
    /** Installed apps outside the catalog whose name or package matches the query; they have no vectors. */
    val otherSearchResults: List<InstalledApp> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesViewModel(
    private val zapstore: ZapstoreApplication,
    private val catalogSync: CatalogSync,
    private val encodeQuery: (String) -> ByteArray?,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val batch = MutableStateFlow(UpdateAllUi())
    /** Kept in the saved state, as on Home: the text and the submitted search survive leaving the tab and the process. */
    private val searchQuery = savedStateHandle.getStateFlow(SEARCH_QUERY_KEY, "")
    private val submittedQuery = savedStateHandle.getStateFlow(SUBMITTED_QUERY_KEY, "")
    private val chosenFilters = MutableStateFlow<Set<SearchFact>>(emptySet())
    private val installerLabels = HashMap<String, String?>()
    private val ignoredIds = zapstore.lists.ignored

    private val taskIds: Flow<Set<String>> = zapstore.installer.tasks.map { it.keys }.distinctUntilChanged()

    private val myAppIds: Flow<Set<String>> =
        combine(catalogSync.installedApps, ignoredIds, taskIds) { installed, ignored, tasks ->
            buildSet {
                installed.mapTo(this) { it.packageId }
                addAll(ignored)
                addAll(tasks)
            }
        }.distinctUntilChanged()

    /** Listings of installed and ignored apps: everything this screen shows. The whole catalog is tens of MB. */
    private val myApps: Flow<List<AppRecord>> =
        myAppIds.flatMapLatest { ids -> zapstore.iolite.observeApps(AppFilter(appIds = ids)) }
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 1)

    private val profiles = myApps.authorProfiles(zapstore.iolite)

    /** Same ranking, vectors, repository match and filters as Home search, over the user's apps only. */
    private val search: Flow<MyAppsSearch?> =
        combine(submittedQuery, chosenFilters, myAppIds, ::Triple).flatMapLatest { (submitted, chosen, ids) ->
            val query = normalizeSearchQuery(submitted)
            if (query.length < MIN_SEARCH_LENGTH) return@flatMapLatest flowOf(null)
            flow {
                val repository = repositoryQuery(query)
                if (repository != null) {
                    emitAll(zapstore.iolite.observeApps(AppFilter(appIds = ids, repository = repository)).map { MyAppsSearch(it, emptyList()) })
                    return@flow
                }
                val facts = SearchFact.entries.filter { it in chosen }
                val hard = facts.fold(0) { bits, fact -> bits or fact.hardBit }
                val vector = withContext(Dispatchers.Default) { runCatching { encodeQuery(query) }.getOrNull() }
                val filter = AppFilter(
                    appIds = ids,
                    search = query,
                    queryVector = vector,
                    hard = hard,
                    exclude = facts.fold(0) { bits, fact -> bits or fact.excludeBit },
                    boost = hard,
                )
                emitAll(zapstore.iolite.observeApps(filter).map { MyAppsSearch(it, facts) })
            }
        }

    val uiState: StateFlow<UpdatesUiState> = combine(
        combine(catalogSync.status, catalogSync.updates, profiles, zapstore.iolite.observeCatalogs()) { status, _, profiles, catalogs ->
            Triple(status, profiles, catalogs.map { it.epoch })
        },
        catalogSync.installedApps,
        myApps,
        ignoredIds,
        combine(batch, search, submittedQuery, zapstore.installer.tasks) { batch, search, submitted, tasks -> Quad(batch, search, submitted, tasks) },
    ) { (status, profiles, epochs), installed, apps, ignored, (batch, search, typed, tasks) ->
        val groups = updateGroups(installed, apps, ignored.toSet(), zapstore.packageName) { update -> update.isReady() }
        val byId = apps.associateBy { it.appId }
        val installedById = installed.associateBy { it.packageId }
        val updateRows = (groups.updates + groups.manualUpdates).mapTo(HashSet()) { it.app.appId }
        // Only work in progress. A verified APK waiting for a tap stays on its update row, or on the app page.
        val installing = tasks.values.filter { it.appId !in updateRows && it.phase != TaskPhase.Ready && it.phase != TaskPhase.Done }
            .mapNotNull { byId[it.appId] }.sortedBy { it.name.lowercase() }
        val installingIds = installing.mapTo(HashSet()) { it.appId }
        UpdatesUiState(
            profiles = profiles,
            ignored = ignored.filter { it !in installingIds }.mapNotNull { id ->
                byId[id]?.let { IgnoredApp(it, installerLabel(installedById[id]?.installingPackage)) }
            },
            status = status,
            catalogEpochs = epochs,
            updates = groups.updates,
            manualUpdates = groups.manualUpdates,
            manualReasons = groups.manualUpdates.mapNotNull { update ->
                update.manualReason(zapstore.packageName, SessionInstaller.silentSupported)?.let { update.app.appId to it }
            }.toMap(),
            manualStores = groups.manualUpdates.mapNotNull { update ->
                update.otherStore(zapstore.packageName)?.let { update.app.appId to (installerLabel(it) ?: it) }
            }.toMap(),
            installedApps = groups.installedApps.filter { it.appId !in installingIds },
            installing = installing,
            otherStores = groups.otherStores
                .filter { it.app.appId !in installingIds }
                .map { OtherStoreApp(it, installerLabel(it.store) ?: it.store) },
            otherInstalled = groups.otherInstalled,
            loaded = true,
            updateAll = batch,
            tasks = tasks,
            submittedQuery = typed,
            searchResults = search?.apps,
            searchFacts = search?.facts.orEmpty(),
            otherSearchResults = if (search == null) emptyList() else groups.otherInstalled.matching(normalizeSearchQuery(typed)),
        )
    }.flowOn(Dispatchers.Default)
        .combine(searchQuery) { state, typed -> state.copy(searchQuery = typed) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            UpdatesUiState(searchQuery = searchQuery.value, submittedQuery = submittedQuery.value),
        )

    fun sync() = catalogSync.request(force = true)

    fun onSearchQueryChanged(value: String) {
        savedStateHandle[SEARCH_QUERY_KEY] = value
    }

    fun submitSearch() {
        savedStateHandle[SUBMITTED_QUERY_KEY] = searchQuery.value.trim()
    }

    fun clearSearch() {
        savedStateHandle[SEARCH_QUERY_KEY] = ""
        savedStateHandle[SUBMITTED_QUERY_KEY] = ""
    }

    fun removeFilter(fact: SearchFact) {
        chosenFilters.update { it - fact }
    }

    fun toggleFilter(fact: SearchFact) {
        chosenFilters.update { if (fact in it) it - fact else it + fact }
    }

    fun resumeUpdates(appId: String) {
        zapstore.lists.setIgnored(appId, false)
        Toasts.show(zapstore.getString(R.string.toast_unignored))
    }

    /** Ignoring ends whatever the app has in progress and drops its downloaded file. */
    fun ignoreUpdates(appId: String) {
        zapstore.installer.cancel(appId)
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

    /** Starts, resumes or retries an install from the Installing section. */
    fun install(app: AppRecord) = zapstore.installer.install(app)

    fun cancelInstall(appId: String) = zapstore.installer.cancel(appId)

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
        private const val SEARCH_QUERY_KEY = "searchQuery"
        private const val SUBMITTED_QUERY_KEY = "submittedQuery"

        fun factory(app: ZapstoreApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { UpdatesViewModel(app, app.catalogSync, app.queryEncoder::encode, createSavedStateHandle()) }
        }
    }
}

private fun List<InstalledApp>.matching(query: String): List<InstalledApp> =
    filter { it.label.contains(query, ignoreCase = true) || it.packageId.contains(query, ignoreCase = true) }

private fun UpdatesUiState.startable(group: UpdateGroup): List<AvailableUpdate> = when (group) {
    UpdateGroup.Automatic -> updates
    UpdateGroup.Manual -> manualUpdates
}.filter { tasks[it.app.appId]?.busy != true }

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** Ranked apps and the filters that narrowed them. */
private class MyAppsSearch(val apps: List<AppRecord>, val facts: List<SearchFact>)
