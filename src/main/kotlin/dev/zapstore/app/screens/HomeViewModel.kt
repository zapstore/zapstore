package dev.zapstore.app.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.components.AppListState
import dev.zapstore.iolite.AppFilter
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CatalogStackPubkey
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import dev.zapstore.iolite.QueryState
import dev.zapstore.iolite.StackRecord
import dev.zapstore.iolite.SearchFact
import dev.zapstore.iolite.normalizeSearchQuery
import dev.zapstore.iolite.parseSearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

data class HomeUiState(
    val searchQuery: String = "",
    /** The query last submitted with Enter; empty until then. */
    val submittedQuery: String = "",
    /** Null when no search is active; empty when the query matched nothing. */
    val searchResults: List<AppRecord>? = null,
    /** Encode plus first ranking pass for [searchResults]. Null when no search is active. */
    val searchDurationMillis: Long? = null,
    /** Facts parsed out of [submittedQuery], in the order they were found. */
    val searchFacts: List<SearchFact> = emptyList(),
    val stacks: List<StackRecord> = emptyList(),
    val stackApps: Map<String, AppRecord> = emptyMap(),
    val stacksLoading: Boolean = true,
    val stacksError: String? = null,
    val feed: AppListState = AppListState(),
    val profiles: Map<String, ProfileRecord> = emptyMap(),
)

/**
 * Home: local search, catalog stacks, and the recently-updated feed. Every flow here is a SQLite
 * observer and stops with the collector.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val iolite: Iolite,
    private val savedStateHandle: SavedStateHandle,
    private val encodeQuery: (String) -> ByteArray?,
) : ViewModel() {
    private val searchQuery = savedStateHandle.getStateFlow(SEARCH_QUERY_KEY, "")
    private val submittedQuery = savedStateHandle.getStateFlow(SUBMITTED_QUERY_KEY, "")
    private val requestedPages = MutableStateFlow(1)

    private val search: Flow<SearchSnapshot> = submittedQuery
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { raw ->
            val query = normalizeSearchQuery(raw)
            if (query.length < MIN_SEARCH_LENGTH) {
                flowOf(SearchSnapshot())
            } else {
                val parsed = parseSearchQuery(query)
                flow {
                    val started = TimeSource.Monotonic.markNow()
                    val vector = if (parsed.residual.isEmpty()) {
                        null
                    } else {
                        try {
                            withContext(Dispatchers.Default) { encodeQuery(parsed.residual) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            null
                        }
                    }
                    var durationMillis: Long? = null
                    val filter = AppFilter(
                        search = parsed.residual.takeIf { it.isNotEmpty() },
                        queryVector = vector,
                        hard = parsed.hard,
                        boost = parsed.boost,
                        soft = parsed.soft,
                        penalty = parsed.penalty,
                        limit = SEARCH_LIMIT,
                    )
                    iolite.observeApps(filter).collect { apps ->
                        if (durationMillis == null) durationMillis = started.elapsedNow().inWholeMilliseconds
                        emit(SearchSnapshot(apps, durationMillis, parsed.facts))
                    }
                }
            }
        }

    private class FeedPage(val apps: List<AppRecord>, val limit: Int)

    private val feedPage: Flow<FeedPage> = requestedPages.flatMapLatest { pages ->
        val limit = pages * PAGE_SIZE
        iolite.observeApps(AppFilter(limit = limit)).map { FeedPage(it, limit) }
    }

    private val stacks: Flow<QueryState<List<StackRecord>>> =
        iolite.query(Query.stacks(CatalogStackPubkey, limit = STACK_LIMIT))

    private val stackApps: Flow<Map<String, AppRecord>> = stacks
        .map { state -> state.items.flatMap(StackRecord::appIds).toSet() }
        .distinctUntilChanged()
        .flatMapLatest(iolite::observeAppsById)

    private val profiles: Flow<Map<String, ProfileRecord>> =
        combine(feedPage, search) { page, snapshot -> page.apps + snapshot.results.orEmpty() }.authorProfiles(iolite)

    val uiState: StateFlow<HomeUiState> = combine(
        combine(searchQuery, submittedQuery, search, ::Triple),
        feedPage,
        combine(stacks, stackApps, ::Pair),
        profiles,
        requestedPages,
    ) { (query, submitted, snapshot), page, (stackState, stackApps), profiles, pages ->
        HomeUiState(
            searchQuery = query,
            submittedQuery = submitted,
            searchResults = snapshot.results,
            searchDurationMillis = snapshot.durationMillis,
            searchFacts = snapshot.facts,
            stacks = stackState.items,
            stackApps = stackApps,
            stacksLoading = stackState.items.isEmpty() && stackState.isLoading,
            stacksError = stackState.error?.message,
            feed = AppListState(
                apps = page.apps,
                profiles = profiles,
                loading = false,
                loadingMore = page.limit < pages * PAGE_SIZE,
                canLoadMore = page.apps.size >= page.limit,
            ),
            profiles = profiles,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        HomeUiState(searchQuery = searchQuery.value, submittedQuery = submittedQuery.value),
    )

    fun onSearchQueryChanged(value: String) {
        savedStateHandle[SEARCH_QUERY_KEY] = value
    }

    fun submitSearch() {
        val query = searchQuery.value.trim()
        savedStateHandle[SEARCH_QUERY_KEY] = query
        savedStateHandle[SUBMITTED_QUERY_KEY] = query
    }

    fun clearSearch() {
        savedStateHandle[SEARCH_QUERY_KEY] = ""
        savedStateHandle[SUBMITTED_QUERY_KEY] = ""
    }

    fun loadMore() {
        val current = uiState.value.feed
        if (current.canLoadMore && !current.loadingMore) requestedPages.update { it + 1 }
    }

    companion object {
        private const val SEARCH_QUERY_KEY = "searchQuery"
        private const val SUBMITTED_QUERY_KEY = "submittedQuery"
        private const val MIN_SEARCH_LENGTH = 2
        private const val SEARCH_LIMIT = 12
        private const val PAGE_SIZE = 20
        private const val STACK_LIMIT = 20

        fun factory(iolite: Iolite, encodeQuery: (String) -> ByteArray?): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(iolite, createSavedStateHandle(), encodeQuery) }
        }
    }
}

internal const val STOP_TIMEOUT_MILLIS = 5_000L

/** [results] is null until a query is long enough to run. [durationMillis] is that first pass. */
private data class SearchSnapshot(
    val results: List<AppRecord>? = null,
    val durationMillis: Long? = null,
    val facts: List<SearchFact> = emptyList(),
)
