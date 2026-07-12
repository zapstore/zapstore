package dev.zapstore.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import dev.zapstore.purplequartz.QuerySync
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val STACK_ID_ARGUMENT = "stackId"
const val APP_IDENTIFIER_ARGUMENT = "identifier"
const val APP_AUTHOR_ARGUMENT = "author"

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
    val releases: List<ReleaseInfo> = emptyList(),
    val releaseApps: Map<String, AppInfo> = emptyMap(),
    val releasesLoading: Boolean = true,
    val releasesMessage: String = "Connecting to relay.zapstore.dev",
)

class HomeViewModel(
    private val repository: CatalogRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        HomeUiState(searchQuery = savedStateHandle[SEARCH_QUERY_KEY] ?: ""),
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var stackPreviewJob: Job? = null
    private var releaseAppsJob: Job? = null
    private var searchJob: Job? = null
    private var stackAddresses: List<String> = emptyList()
    private var releaseIdentifiers: Set<String> = emptySet()

    init {
        observeStacks()
        observeLatestReleases()
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
                type = QueryType.Remote,
            ).collect { state ->
                val apps = state.items.map(::AppInfo).distinctBy(AppInfo::address)
                _uiState.update {
                    it.copy(
                        submittedSearchQuery = query,
                        searchResults = apps,
                        searchMessage = state.error?.message ?: when {
                            apps.isEmpty() && state.sync.isLoading -> "Searching relay.zapstore.dev…"
                            apps.isEmpty() -> "No apps found for “$query”."
                            else -> "${apps.size} results for “$query”"
                        },
                        isSearching = apps.isEmpty() && state.sync.isLoading,
                    )
                }
            }
        }
    }

    private fun observeStacks() {
        viewModelScope.launch {
            repository.query(
                Filter(
                    authors = listOf(Catalog.communityPubkey),
                    kinds = listOf(Catalog.appStackKind),
                    limit = 20,
                ),
                type = QueryType.LocalAndRemote,
            ).collect { state ->
                val stacks = state.items.map(::StackInfo).sortedByDescending { it.event.createdAt }
                _uiState.update {
                    it.copy(
                        stacks = stacks,
                        stacksLoading = stacks.isEmpty() && state.sync.isLoading,
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
                type = QueryType.LocalAndRemote,
            ).collect { state ->
                _uiState.update {
                    it.copy(stackApps = state.items.map(::AppInfo).associateBy(AppInfo::address))
                }
            }
        }
    }

    private fun observeLatestReleases() {
        viewModelScope.launch {
            repository.query(
                Filter(kinds = listOf(Catalog.releaseKind), limit = 20),
                type = QueryType.LocalAndRemote,
            ).collect { state ->
                val releases = state.items.map(::ReleaseInfo)
                    .sortedByDescending { it.event.createdAt }
                    .distinctBy { it.appIdentifier ?: it.event.id }
                    .take(10)
                _uiState.update {
                    it.copy(
                        releases = releases,
                        releasesLoading = releases.isEmpty() && state.sync.isLoading,
                        releasesMessage = state.error?.message ?: "${releases.size} recently updated apps",
                    )
                }
                observeReleaseApps(releases)
            }
        }
    }

    private fun observeReleaseApps(releases: List<ReleaseInfo>) {
        val identifiers = releases.mapNotNull(ReleaseInfo::appIdentifier).toSet()
        if (identifiers == releaseIdentifiers) return
        releaseIdentifiers = identifiers
        releaseAppsJob?.cancel()
        if (identifiers.isEmpty()) {
            _uiState.update { it.copy(releaseApps = emptyMap()) }
            return
        }

        releaseAppsJob = viewModelScope.launch {
            repository.query(
                Filter(
                    kinds = listOf(Catalog.appKind),
                    tags = mapOf("d" to identifiers.toList()),
                    limit = identifiers.size * 3,
                ),
                type = QueryType.LocalAndRemote,
            ).collect { state ->
                _uiState.update {
                    it.copy(releaseApps = state.items.map(::AppInfo).associateBy(AppInfo::identifier))
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
    private var appAddresses: List<String> = emptyList()

    init {
        viewModelScope.launch {
            repository.query(
                Filter(ids = listOf(stackId), kinds = listOf(Catalog.appStackKind), limit = 1),
                type = QueryType.LocalAndRemote,
            ).collect { state ->
                val stack = state.items.firstOrNull()?.let(::StackInfo)
                _uiState.update {
                    it.copy(
                        stack = stack,
                        stackLoading = stack == null && state.sync.isLoading,
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
        val coordinates = addresses.mapNotNull(String::toAppCoordinate)
        if (coordinates.isEmpty()) {
            _uiState.update { it.copy(appsByAddress = emptyMap(), appsLoading = false) }
            return
        }

        _uiState.update { it.copy(appsByAddress = emptyMap(), appsLoading = true) }
        appsJob = viewModelScope.launch {
            repository.query(
                Filter(
                    authors = coordinates.map(AppCoordinate::author).distinct(),
                    kinds = listOf(Catalog.appKind),
                    tags = mapOf("d" to coordinates.map(AppCoordinate::identifier).distinct()),
                    limit = coordinates.size * 2,
                ),
                type = QueryType.LocalAndRemote,
            ).collect { state ->
                val apps = state.items.map(::AppInfo).associateBy(AppInfo::address)
                _uiState.update {
                    it.copy(
                        appsByAddress = apps,
                        appsLoading = apps.isEmpty() && state.sync.isLoading,
                        error = state.error?.message ?: it.error,
                    )
                }
            }
        }
    }
}

data class AppDetailUiState(
    val app: AppInfo? = null,
    val release: ReleaseInfo? = null,
    val appLoading: Boolean = true,
    val releaseLoading: Boolean = true,
    val error: String? = null,
)

class AppDetailViewModel(
    private val repository: CatalogRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val identifier = requireNotNull(savedStateHandle.get<String>(APP_IDENTIFIER_ARGUMENT))
    private val author = savedStateHandle.get<String>(APP_AUTHOR_ARGUMENT)?.takeIf(String::isNotBlank)
    private val _uiState = MutableStateFlow(AppDetailUiState())
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()

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
                type = QueryType.LocalAndRemote,
            ).collect { state ->
                val app = state.items.firstOrNull()?.let(::AppInfo)
                _uiState.update {
                    it.copy(
                        app = app,
                        appLoading = app == null && state.sync.isLoading,
                        error = state.error?.message ?: it.error,
                    )
                }
            }
        }
    }

    private fun observeLatestRelease() {
        viewModelScope.launch {
            repository.query(
                Filter(
                    kinds = listOf(Catalog.releaseKind),
                    tags = mapOf("i" to listOf(identifier)),
                    limit = 10,
                ),
                type = QueryType.LocalAndRemote,
            ).collect { state ->
                val release = state.items.map(::ReleaseInfo).maxByOrNull { it.event.createdAt }
                _uiState.update {
                    it.copy(
                        release = release,
                        releaseLoading = release == null && state.sync.isLoading,
                        error = state.error?.message ?: it.error,
                    )
                }
            }
        }
    }
}

fun homeViewModelFactory(repository: CatalogRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { HomeViewModel(repository, createSavedStateHandle()) }
}

fun stackDetailViewModelFactory(repository: CatalogRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { StackDetailViewModel(repository, createSavedStateHandle()) }
}

fun appDetailViewModelFactory(repository: CatalogRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { AppDetailViewModel(repository, createSavedStateHandle()) }
}

private val QuerySync.isLoading: Boolean
    get() = this == QuerySync.Connecting || this == QuerySync.CatchingUp
