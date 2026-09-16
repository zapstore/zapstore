package dev.zapstore.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.vitorpamplona.quartz.lightning.LnInvoiceUtil
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import dev.zapstore.iolite.QueryPhase
import dev.zapstore.iolite.RemoteMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val STACK_ID_ARGUMENT = "stackId"
const val APP_IDENTIFIER_ARGUMENT = "identifier"
const val APP_AUTHOR_ARGUMENT = "author"
const val PROFILE_PUBKEY_ARGUMENT = "pubkey"

data class HomeUiState(
    val searchQuery: String = "",
    val submittedSearchQuery: String? = null,
    val searchResults: List<AppInfo> = emptyList(),
    val searchMessage: String? = null,
    val isSearching: Boolean = false,
    val stacks: List<StackInfo> = emptyList(),
    val stackApps: Map<String, AppInfo> = emptyMap(),
    val stacksLoading: Boolean = true,
    val stacksError: String? = null,
    val releaseFeed: ReleaseFeedUiState = ReleaseFeedUiState(),
)

data class ReleaseFeedEntry(
    val app: AppInfo,
    val release: ReleaseInfo? = null,
)

data class ReleaseFeedUiState(
    val entries: List<ReleaseFeedEntry> = emptyList(),
    val initialLoading: Boolean = true,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val error: String? = null,
)

/**
 * App-first feed with release-ranked presentation.
 *
 * Kind 32267 app events are the identity and pagination source, so exhausting the
 * feed exhausts the app catalog. Kinds 30063 and 3063 only provide each app's
 * latest-version metadata and sort key; they can never create a feed row.
 */
private class ReleaseFeedLoader(
    private val repository: CatalogRepository,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val author: String? = null,
    private val onStateChanged: (ReleaseFeedUiState) -> Unit,
) {
    private val appsByAddress = linkedMapOf<String, AppInfo>()
    private val releasesByApp = mutableMapOf<String, ReleaseInfo>()
    private var nextUntil: Long? = null
    private val boundaryEventIds = mutableSetOf<String>()
    private var releaseLookupKeys: Set<String> = emptySet()
    private var releaseJob: Job? = null
    private var verificationAuthors: Set<String> = emptySet()
    private var verificationLookupAuthors: Set<String> = emptySet()
    private var verificationJob: Job? = null
    private var requestInFlight = false
    private var state = ReleaseFeedUiState()

    fun loadNextPage() {
        if (requestInFlight || !state.canLoadMore) return
        requestInFlight = true

        updateState(
            state.copy(
                initialLoading = appsByAddress.isEmpty(),
                loadingMore = appsByAddress.isNotEmpty(),
                error = null,
            ),
        )
        val pageUntil = nextUntil
        val pageLimit = APP_PAGE_SIZE + boundaryEventIds.size
        scope.launch {
            var receivedTerminalState = false
            repository.query(
                Filter(
                    authors = author?.let(::listOf),
                    kinds = listOf(Catalog.appKind),
                    limit = pageLimit,
                    until = pageUntil,
                ),
            ).takeWhile { queryState ->
                val page = queryState.items
                    .filter { it.kind == Catalog.appKind }
                    .map(::AppInfo)
                    .sortedByDescending { it.event.createdAt }
                    .distinctBy { it.event.id }
                page.forEach { app ->
                    val existing = appsByAddress[app.address]
                    if (
                        existing == null ||
                        app.event.createdAt > existing.event.createdAt ||
                        app.event.createdAt == existing.event.createdAt && app.event.id < existing.event.id
                    ) {
                        appsByAddress[app.address] = app
                    }
                }
                publishEntries()

                val terminal = !queryState.phase.isLoading || queryState.error != null
                if (!terminal && appsByAddress.isNotEmpty()) {
                    updateState(state.copy(initialLoading = false, loadingMore = true))
                } else if (terminal) {
                    receivedTerminalState = true
                    requestInFlight = false
                    page.minOfOrNull { it.event.createdAt }?.let { oldestTimestamp ->
                        val idsAtBoundary = page
                            .asSequence()
                            .filter { it.event.createdAt == oldestTimestamp }
                            .mapTo(mutableSetOf()) { it.event.id }
                        if (pageUntil != oldestTimestamp) boundaryEventIds.clear()
                        boundaryEventIds += idsAtBoundary
                        nextUntil = oldestTimestamp
                    }
                    updateState(
                        state.copy(
                            initialLoading = false,
                            loadingMore = false,
                            canLoadMore = queryState.error == null && page.size >= pageLimit,
                            error = queryState.error?.message,
                        ),
                    )
                }
                !terminal
            }.collect {
                // State is handled in takeWhile so the terminal emission can stop this live query.
            }
            if (!receivedTerminalState) {
                requestInFlight = false
                updateState(state.copy(initialLoading = false, loadingMore = false, canLoadMore = false))
            }
        }
    }

    private fun publishEntries() {
        val entries = appsByAddress.values
            .sortedWith(
                compareByDescending<AppInfo> {
                    releasesByApp[it.address]?.event?.createdAt ?: Long.MIN_VALUE
                }.thenByDescending { it.event.createdAt }
                    .thenBy { it.address },
            )
            .map { app ->
                ReleaseFeedEntry(
                    app = app.copy(hasVerifiedC1 = app.event.pubKey in verificationAuthors),
                    release = releasesByApp[app.address],
                )
            }
        updateState(state.copy(entries = entries))
        observeReleases(entries.map(ReleaseFeedEntry::app))
        observeVerification(appsByAddress.values.toList())
    }

    private fun observeVerification(apps: List<AppInfo>) {
        val authors = apps.map(AppInfo::event).map { it.pubKey }.toSet()
        if (authors == verificationLookupAuthors) return
        verificationLookupAuthors = authors
        verificationJob?.cancel()
        if (authors.isEmpty()) {
            verificationAuthors = emptySet()
            publishEntries()
            return
        }

        verificationJob = scope.launch {
            verificationAuthors = repository.verifiedApps(apps).map { it.event.pubKey }.toSet()
            val visibleEntries = appsByAddress.values
                .sortedWith(
                    compareByDescending<AppInfo> {
                        releasesByApp[it.address]?.event?.createdAt ?: Long.MIN_VALUE
                    }.thenByDescending { it.event.createdAt }
                        .thenBy { it.address },
                )
                .map { app ->
                    ReleaseFeedEntry(
                        app = app.copy(hasVerifiedC1 = app.event.pubKey in verificationAuthors),
                        release = releasesByApp[app.address],
                    )
                }
            updateState(state.copy(entries = visibleEntries))
            observeReleases(visibleEntries.map(ReleaseFeedEntry::app))
        }
    }

    private fun observeReleases(apps: List<AppInfo>) {
        val lookupKeys = apps.map(AppInfo::address).toSet()
        if (lookupKeys == releaseLookupKeys) return
        releaseLookupKeys = lookupKeys
        releaseJob?.cancel()

        if (apps.isEmpty()) return

        val releaseFilters = apps.map { app ->
            Filter(
                authors = listOf(app.event.pubKey),
                kinds = Catalog.releaseKinds,
                tags = mapOf("i" to listOf(app.identifier)),
                limit = 1,
            )
        }
        releaseJob = scope.launch {
            repository.query(
                releaseFilters,
            ).collect { queryState ->
                queryState.items
                    .filter { it.kind in Catalog.releaseKinds }
                    .map(::ReleaseInfo)
                    .forEach { release ->
                        val identifier = release.appIdentifier ?: return@forEach
                        val address = "${Catalog.appKind}:${release.event.pubKey}:$identifier"
                        if (address in appsByAddress) {
                            releasesByApp[address] = preferredRelease(releasesByApp[address], release)
                        }
                    }
                publishEntries()
            }
        }
    }

    private fun updateState(value: ReleaseFeedUiState) {
        state = value
        onStateChanged(value)
    }

    private companion object {
        const val APP_PAGE_SIZE = 20
    }
}

class HomeViewModel(
    private val repository: CatalogRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        HomeUiState(searchQuery = savedStateHandle[SEARCH_QUERY_KEY] ?: ""),
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var stackPreviewJob: Job? = null
    private var searchJob: Job? = null
    private var stackAddresses: List<String> = emptyList()
    private val releaseFeed = ReleaseFeedLoader(repository, viewModelScope) { feed ->
        _uiState.update { it.copy(releaseFeed = feed) }
    }

    init {
        observeStacks()
        releaseFeed.loadNextPage()
    }

    fun onSearchQueryChanged(value: String) {
        savedStateHandle[SEARCH_QUERY_KEY] = value
        _uiState.update { it.copy(searchQuery = value) }
    }

    fun clearSearch() {
        searchJob?.cancel()
        savedStateHandle[SEARCH_QUERY_KEY] = ""
        _uiState.update {
            it.copy(
                searchQuery = "",
                submittedSearchQuery = null,
                searchResults = emptyList(),
                searchMessage = null,
                isSearching = false,
            )
        }
    }

    fun submitSearch() {
        val query = _uiState.value.searchQuery.trim()
        searchJob?.cancel()
        if (query.length < MIN_SEARCH_LENGTH) {
            _uiState.update {
                it.copy(
                    submittedSearchQuery = null,
                    searchResults = emptyList(),
                    searchMessage = null,
                    isSearching = false,
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                submittedSearchQuery = query,
                searchResults = emptyList(),
                searchMessage = "Searching relay.zapstore.dev…",
                isSearching = true,
            )
        }
        searchJob = viewModelScope.launch {
            repository.query(
                Filter(kinds = listOf(Catalog.appKind), search = query, limit = 20),
                options = catalogQueryOptions(),
            ).collect { state ->
                val apps = state.items.map(::AppInfo).distinctBy(AppInfo::address)
                _uiState.update {
                    it.copy(
                        submittedSearchQuery = query,
                        searchResults = apps,
                        searchMessage = state.error?.message ?: when {
                            apps.isEmpty() && state.phase.isLoading -> "Searching relay.zapstore.dev…"
                            apps.isEmpty() -> "No apps found for “$query”."
                            else -> "${apps.size} results for “$query”"
                        },
                        isSearching = apps.isEmpty() && state.phase.isLoading,
                    )
                }
            }
        }
    }

    fun loadMoreReleases() = releaseFeed.loadNextPage()

    private fun observeStacks() {
        viewModelScope.launch {
            repository.query(
                Filter(
                    authors = listOf(Catalog.communityPubkey),
                    kinds = listOf(Catalog.appStackKind),
                    limit = 20,
                ),
                options = catalogQueryOptions(remoteMode = RemoteMode.Stream),
            ).collect { state ->
                val stacks = state.items.map(::StackInfo).sortedByDescending { it.event.createdAt }
                _uiState.update {
                    it.copy(
                        stacks = stacks,
                        stacksLoading = stacks.isEmpty() && state.phase.isLoading,
                        stacksError = state.error?.message,
                    )
                }
                observeStackPreviews(stacks)
            }
        }
    }

    private fun observeStackPreviews(stacks: List<StackInfo>) {
        val addresses = stacks.flatMap(StackInfo::appAddresses).distinct()
        if (addresses == stackAddresses) return
        stackAddresses = addresses
        stackPreviewJob?.cancel()

        val coordinates = addresses.mapNotNull(String::toAppCoordinate)
        if (coordinates.isEmpty()) {
            _uiState.update { it.copy(stackApps = emptyMap()) }
            return
        }

        stackPreviewJob = viewModelScope.launch {
            repository.query(
                Filter(
                    authors = coordinates.map(AppCoordinate::author).distinct(),
                    kinds = listOf(Catalog.appKind),
                    tags = mapOf("d" to coordinates.map(AppCoordinate::identifier).distinct()),
                    limit = coordinates.size * 2,
                ),
            ).collect { state ->
                val apps = state.items.map(::AppInfo).associateBy(AppInfo::address)
                _uiState.update {
                    it.copy(stackApps = apps)
                }
            }
        }
    }

    private companion object {
        const val SEARCH_QUERY_KEY = "searchQuery"
        const val MIN_SEARCH_LENGTH = 3
    }
}

data class StackDetailUiState(
    val stack: StackInfo? = null,
    val appsByAddress: Map<String, AppInfo> = emptyMap(),
    val releasesByAddress: Map<String, ReleaseInfo> = emptyMap(),
    val stackLoading: Boolean = true,
    val appsLoading: Boolean = false,
    val error: String? = null,
)

class StackDetailViewModel(
    private val repository: CatalogRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val stackId = requireNotNull(savedStateHandle.get<String>(STACK_ID_ARGUMENT))
    private val _uiState = MutableStateFlow(StackDetailUiState())
    val uiState: StateFlow<StackDetailUiState> = _uiState.asStateFlow()

    private var appsJob: Job? = null
    private var releasesJob: Job? = null
    private var appAddresses: List<String> = emptyList()
    private var releaseLookupKeys: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            repository.query(
                Filter(ids = listOf(stackId), kinds = listOf(Catalog.appStackKind), limit = 1),
            ).collect { state ->
                val stack = state.items.firstOrNull()?.let(::StackInfo)
                _uiState.update {
                    it.copy(
                        stack = stack,
                        stackLoading = stack == null && state.phase.isLoading,
                        error = state.error?.message,
                    )
                }
                observeApps(stack)
            }
        }
    }

    private fun observeApps(stack: StackInfo?) {
        val addresses = stack?.appAddresses.orEmpty()
        if (addresses == appAddresses) return
        appAddresses = addresses
        appsJob?.cancel()
        releasesJob?.cancel()
        releaseLookupKeys = emptySet()
        val coordinates = addresses.mapNotNull(String::toAppCoordinate)
        if (coordinates.isEmpty()) {
            _uiState.update {
                it.copy(appsByAddress = emptyMap(), releasesByAddress = emptyMap(), appsLoading = false)
            }
            return
        }

        _uiState.update {
            it.copy(appsByAddress = emptyMap(), releasesByAddress = emptyMap(), appsLoading = true)
        }
        appsJob = viewModelScope.launch {
            repository.query(
                Filter(
                    authors = coordinates.map(AppCoordinate::author).distinct(),
                    kinds = listOf(Catalog.appKind),
                    tags = mapOf("d" to coordinates.map(AppCoordinate::identifier).distinct()),
                    limit = coordinates.size * 2,
                ),
            ).collect { state ->
                val apps = state.items.map(::AppInfo).associateBy(AppInfo::address).values.toList()
                _uiState.update {
                    it.copy(
                        appsByAddress = apps.associateBy(AppInfo::address),
                        appsLoading = apps.isEmpty() && state.phase.isLoading,
                        error = state.error?.message ?: it.error,
                    )
                }
                observeReleases(apps)
            }
        }
    }

    private fun observeReleases(apps: List<AppInfo>) {
        val lookupKeys = apps.map(AppInfo::address).toSet()
        if (lookupKeys == releaseLookupKeys) return
        releaseLookupKeys = lookupKeys
        releasesJob?.cancel()
        if (apps.isEmpty()) {
            _uiState.update { it.copy(releasesByAddress = emptyMap()) }
            return
        }
        val releaseFilters = apps.map { app ->
            Filter(
                authors = listOf(app.event.pubKey),
                kinds = Catalog.releaseKinds,
                tags = mapOf("i" to listOf(app.identifier)),
                limit = 1,
            )
        }
        releasesJob = viewModelScope.launch {
            repository.query(releaseFilters).collect { queryState ->
                val releases = linkedMapOf<String, ReleaseInfo>()
                queryState.items
                    .filter { it.kind in Catalog.releaseKinds }
                    .map(::ReleaseInfo)
                    .forEach { release ->
                        val identifier = release.appIdentifier ?: return@forEach
                        val address = "${Catalog.appKind}:${release.event.pubKey}:$identifier"
                        releases[address] = preferredRelease(releases[address], release)
                    }
                _uiState.update { it.copy(releasesByAddress = releases) }
            }
        }
    }
}

data class AppDetailUiState(
    val app: AppInfo? = null,
    val release: ReleaseInfo? = null,
    val zapSummary: ZapSummaryUiState = ZapSummaryUiState(),
    val appLoading: Boolean = true,
    val releaseLoading: Boolean = true,
    val error: String? = null,
)

data class ZapSummaryUiState(
    val zapCount: Int = 0,
    val totalSats: Long = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
)

data class ProfileUiState(
    val profile: ProfileInfo? = null,
    val pubkey: String = "",
    val releaseFeed: ReleaseFeedUiState = ReleaseFeedUiState(),
    val profileLoading: Boolean = true,
    val error: String? = null,
)

class ProfileViewModel(
    private val repository: CatalogRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val pubkey = requireNotNull(savedStateHandle.get<String>(PROFILE_PUBKEY_ARGUMENT))
    private val _uiState = MutableStateFlow(ProfileUiState(pubkey = pubkey))
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()
    private val releaseFeed = ReleaseFeedLoader(repository, viewModelScope, pubkey) { feed ->
        _uiState.update { it.copy(releaseFeed = feed) }
    }

    init {
        observeProfile()
        releaseFeed.loadNextPage()
    }

    private fun observeProfile() {
        viewModelScope.launch {
            repository.queryWithOutbox(
                filters = listOf(
                    Filter(authors = listOf(pubkey), kinds = listOf(Catalog.profileKind), limit = 1),
                ),
                authors = listOf(pubkey),
                cachedFor = PROFILE_CACHE_DURATION,
                relays = PROFILE_RELAYS,
                remoteMode = RemoteMode.OneShot(),
                unionWithFallback = false,
            ).collect { state ->
                val profile = state.items.maxByOrNull { it.createdAt }?.let(::ProfileInfo)
                _uiState.update {
                    it.copy(
                        profile = profile,
                        profileLoading = profile == null && state.phase.isLoading,
                        error = state.error?.message,
                    )
                }
            }
        }
    }

    fun loadMoreReleases() = releaseFeed.loadNextPage()
}

class AppDetailViewModel(
    private val repository: CatalogRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val identifier = requireNotNull(savedStateHandle.get<String>(APP_IDENTIFIER_ARGUMENT))
    private val author = savedStateHandle.get<String>(APP_AUTHOR_ARGUMENT)?.takeIf(String::isNotBlank)
    private val _uiState = MutableStateFlow(AppDetailUiState())
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()
    private var zapAssetJob: Job? = null
    private var zapReceiptJob: Job? = null
    private var zapAddress: String? = null
    private var zapAssetIds: Set<String> = emptySet()

    init {
        observeApp()
        observeLatestRelease()
    }

    private fun observeApp() {
        viewModelScope.launch {
            repository.query(
                Filter(
                    authors = author?.let(::listOf),
                    kinds = listOf(Catalog.appKind),
                    tags = mapOf("d" to listOf(identifier)),
                    limit = 3,
                ),
                options = catalogQueryOptions(remoteMode = RemoteMode.Stream),
            ).collect { state ->
                val app = state.items.firstOrNull()?.let(::AppInfo)
                _uiState.update {
                    it.copy(
                        app = app,
                        appLoading = app == null && state.phase.isLoading,
                        error = state.error?.message ?: it.error,
                    )
                }
                app?.let(::observeZaps)
            }
        }
    }

    private fun observeZaps(app: AppInfo) {
        if (app.address == zapAddress) return
        zapAddress = app.address
        zapAssetIds = emptySet()
        startZapReceiptQuery(app)

        zapAssetJob?.cancel()
        zapAssetJob = viewModelScope.launch {
            repository.query(
                Filter(
                    authors = listOf(app.event.pubKey),
                    kinds = listOf(Catalog.assetKind),
                    tags = mapOf("i" to listOf(app.identifier)),
                    limit = ASSET_LOOKUP_LIMIT,
                ),
                options = catalogQueryOptions(
                    remoteMode = RemoteMode.Stream,
                    relays = Catalog.defaultZapRelays,
                ),
            ).collect { state ->
                val nextAssetIds = state.items
                    .asSequence()
                    .filter { it.kind == Catalog.assetKind }
                    .map { it.id }
                    .toSet()
                if (nextAssetIds != zapAssetIds) {
                    zapAssetIds = nextAssetIds
                    startZapReceiptQuery(app)
                }
            }
        }
    }

    private fun startZapReceiptQuery(app: AppInfo) {
        zapReceiptJob?.cancel()
        _uiState.update { it.copy(zapSummary = it.zapSummary.copy(isLoading = true, error = null)) }
        zapReceiptJob = viewModelScope.launch {
            repository.queryWithOutbox(
                zapFilters(app),
                authors = listOf(app.event.pubKey),
                relays = Catalog.defaultZapRelays,
            ).collect { state ->
                val receipts = state.items
                    .asSequence()
                    .filter { it.kind == Catalog.zapReceiptKind }
                    .filter { receipt ->
                        app.address in receipt.tagValues("a") ||
                            receipt.tagValues("e").any(zapAssetIds::contains)
                    }
                    .distinctBy { it.id }
                    .toList()
                _uiState.update {
                    it.copy(
                        zapSummary = ZapSummaryUiState(
                            zapCount = receipts.size,
                            totalSats = receipts.sumOf { receipt ->
                                receipt.tagValue("bolt11")
                                    ?.let { invoice ->
                                        runCatching { LnInvoiceUtil.getAmountInSats(invoice).toLong() }.getOrDefault(0)
                                    }
                                    ?: 0
                            },
                            isLoading = state.phase.isLoading,
                            error = state.error?.message,
                        ),
                    )
                }
            }
        }
    }

    private fun zapFilters(app: AppInfo): List<Filter> = buildList {
        add(
            Filter(
                kinds = listOf(Catalog.zapReceiptKind),
                tags = mapOf("a" to listOf(app.address)),
                limit = ZAP_RECEIPT_LIMIT,
            ),
        )
        zapAssetIds.takeIf(Set<String>::isNotEmpty)?.let { assetIds ->
            add(
                Filter(
                    kinds = listOf(Catalog.zapReceiptKind),
                    tags = mapOf("e" to assetIds.toList()),
                    limit = ZAP_RECEIPT_LIMIT,
                ),
            )
        }
    }

    private fun observeLatestRelease() {
        viewModelScope.launch {
            repository.query(
                Filter(
                    authors = author?.let(::listOf),
                    kinds = Catalog.releaseKinds,
                    tags = mapOf("i" to listOf(identifier)),
                    limit = 10,
                ),
            ).collect { state ->
                val release = state.items
                    .filter { it.kind in Catalog.releaseKinds }
                    .map(::ReleaseInfo)
                    .reduceOrNull(::preferredRelease)
                _uiState.update {
                    it.copy(
                        release = release,
                        releaseLoading = release == null && state.phase.isLoading,
                        error = state.error?.message ?: it.error,
                    )
                }
            }
        }
    }

    private companion object {
        /** Caps the zap summary to the newest receipts per filter; the summary is a recent-activity signal, not a ledger. */
        const val ZAP_RECEIPT_LIMIT = 500
        const val ASSET_LOOKUP_LIMIT = 500
    }
}

class UpdatesViewModel(
    private val catalogSync: dev.zapstore.app.catalogsync.CatalogSyncRepository,
) : ViewModel() {
    val uiState = catalogSync.state

    init {
        catalogSync.refreshUpdates()
    }

    fun sync() {
        viewModelScope.launch { catalogSync.sync() }
    }
}

fun homeViewModelFactory(repository: CatalogRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { HomeViewModel(repository, createSavedStateHandle()) }
}

fun updatesViewModelFactory(
    catalogSync: dev.zapstore.app.catalogsync.CatalogSyncRepository,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { UpdatesViewModel(catalogSync) }
}

fun stackDetailViewModelFactory(repository: CatalogRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { StackDetailViewModel(repository, createSavedStateHandle()) }
}

fun appDetailViewModelFactory(repository: CatalogRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { AppDetailViewModel(repository, createSavedStateHandle()) }
}

fun profileViewModelFactory(repository: CatalogRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { ProfileViewModel(repository, createSavedStateHandle()) }
}

private val QueryPhase.isLoading: Boolean
    get() = this == QueryPhase.Connecting || this == QueryPhase.CatchingUp
