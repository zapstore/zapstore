package dev.zapstore.app.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.components.AppListState
import dev.zapstore.app.AppConfig
import dev.zapstore.iolite.AppCoordinate
import dev.zapstore.iolite.AppFilter
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import dev.zapstore.iolite.QueryPhase
import dev.zapstore.iolite.QueryState
import dev.zapstore.iolite.StackRecord
import dev.zapstore.iolite.SearchFact
import dev.zapstore.iolite.excludeBit
import dev.zapstore.iolite.hardBit
import dev.zapstore.iolite.normalizeSearchQuery
import dev.zapstore.iolite.parseSearchQuery
import dev.zapstore.iolite.repositoryQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class HomeUiState(
    val searchQuery: String = "",
    /** The query last submitted with Enter; empty until then. */
    val submittedQuery: String = "",
    /** Null until a query is submitted; empty when it matched nothing. */
    val searchResults: List<AppRecord>? = null,
    /** Active hard filters: facts parsed out of [submittedQuery] and not removed, then those chosen in the sheet. */
    val searchFacts: List<SearchFact> = emptyList(),
    val stacks: List<StackRecord> = emptyList(),
    val stackApps: Map<String, AppRecord> = emptyMap(),
    val stacksLoading: Boolean = true,
    val stacksError: String? = null,
    val stacksCanLoadMore: Boolean = false,
    /** Newest comments and zaps on catalog apps. */
    val activity: List<ActivityItem> = emptyList(),
    val activityLoading: Boolean = true,
    val activityCanLoadMore: Boolean = false,
    val feed: AppListState = AppListState(),
    val profiles: Map<String, ProfileRecord> = emptyMap(),
)

/** A comment ([comment] set) or a zap ([sats] set) on [app]. */
data class ActivityItem(
    val eventId: String,
    val pubkey: String,
    val createdAt: Long,
    val app: AppRecord,
    val comment: String? = null,
    val sats: Long? = null,
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
    private val stackLimit = MutableStateFlow(STACK_PAGE)
    private val activityLimit = MutableStateFlow(ACTIVITY_PAGE)

    /** Filters chosen in the sheet; they outlive a query. */
    private val chosenFilters = MutableStateFlow<Set<SearchFact>>(emptySet())

    /** Parsed facts whose chip was removed; cleared by the next submit. */
    private val dismissedFacts = MutableStateFlow<Set<SearchFact>>(emptySet())

    /** Runs only for a submitted query; typing alone searches nothing. */
    private val search: Flow<SearchSnapshot> =
        combine(submittedQuery, chosenFilters, dismissedFacts) { submitted, chosen, dismissed ->
            SearchInput(normalizeSearchQuery(submitted), chosen, dismissed)
        }
            .distinctUntilChanged()
            .flatMapLatest { input ->
                if (input.submitted.length < MIN_SEARCH_LENGTH) flowOf(SearchSnapshot()) else ranked(input.submitted, input)
            }

    /** Full ranking. A repository URL matches only the repository and skips the encoder. */
    private fun ranked(query: String, input: SearchInput): Flow<SearchSnapshot> = flow {
        val repository = repositoryQuery(query)
        val facts: List<SearchFact>
        val filter: AppFilter
        if (repository != null) {
            facts = emptyList()
            filter = AppFilter(repository = repository, limit = SEARCH_LIMIT)
        } else {
            val parsed = parseSearchQuery(query)
            val parsedFacts = if (isAppName(query)) emptyList() else parsed.facts
            val text = if (parsedFacts.isEmpty()) query else parsed.residual
            facts = (parsedFacts - input.dismissed + input.chosen).distinct()
            val hard = facts.fold(0) { bits, fact -> bits or fact.hardBit }
            filter = AppFilter(
                search = text.takeIf { it.isNotEmpty() },
                queryVector = if (text.isEmpty()) null else encode(query),
                hard = hard,
                exclude = facts.fold(0) { bits, fact -> bits or fact.excludeBit },
                boost = hard,
                limit = SEARCH_LIMIT,
            )
        }
        emitAll(iolite.observeApps(filter).map { apps -> SearchSnapshot(apps, facts) })
    }

    /** A query that is an app's whole name or app id names that app; fact phrases in it are words. */
    private suspend fun isAppName(query: String): Boolean =
        iolite.observeApps(AppFilter(search = query, limit = 1)).first().firstOrNull()
            ?.let { normalizeSearchQuery(it.name) == query || it.appId.lowercase() == query } == true

    private suspend fun encode(query: String): ByteArray? = try {
        withContext(Dispatchers.Default) { encodeQuery(query) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private class FeedPage(val apps: List<AppRecord>, val limit: Int)

    private val feedPage: Flow<FeedPage> = requestedPages.flatMapLatest { pages ->
        val limit = pages * PAGE_SIZE
        iolite.observeApps(AppFilter(limit = limit)).map { FeedPage(it, limit) }
    }

    private val stacks: Flow<QueryState<List<StackRecord>>> =
        iolite.observeCatalogs()
            .map { catalogs -> catalogs.firstOrNull()?.manifestPubkey }
            .distinctUntilChanged()
            .flatMapLatest { pubkey -> stackLimit.map { pubkey to it } }
            .flatMapLatest { (pubkey, limit) ->
                if (pubkey == null) flowOf(QueryState(emptyList(), QueryPhase.LocalOnly))
                else iolite.query(Query.stacks(pubkey, limit = limit))
            }

    private val stackApps: Flow<Map<String, AppRecord>> = stacks
        .map { state -> state.items.flatMap(StackRecord::appIds).toSet() }
        .distinctUntilChanged()
        .flatMapLatest(iolite::observeAppsById)

    /** Comments on any app, and zaps on the latest releases (receipts can only be asked for by app). */
    private val activity: Flow<ActivityPage> =
        iolite.observeApps(AppFilter(limit = ACTIVITY_APPS))
            .map { apps -> apps.map { AppCoordinate(it.eventPubkey, it.appId) } }
            .distinctUntilChanged()
            .flatMapLatest { anchors -> activityLimit.map { anchors to it } }
            .flatMapLatest { (anchors, limit) ->
                iolite.query(Query.activity(anchors, limit), AppConfig.activityQuery).map { limit to it }
            }
            .flatMapLatest { (limit, state) ->
                val record = state.items
                val ids = (record.comments.mapNotNull { it.appId } + record.zaps.map { it.appId }).toSet()
                iolite.observeAppsById(ids).map { apps ->
                    val items = record.comments.mapNotNull { comment ->
                        apps[comment.appId]?.let { ActivityItem(comment.eventId, comment.pubkey, comment.createdAt, it, comment = comment.content) }
                    } + record.zaps.mapNotNull { zap ->
                        apps[zap.appId]?.let { ActivityItem(zap.eventId, zap.pubkey, zap.createdAt, it, sats = zap.amountSats) }
                    }
                    ActivityPage(
                        QueryState(items.sortedByDescending { it.createdAt }.take(limit), state.phase, error = state.error),
                        canLoadMore = record.comments.size >= limit || record.zaps.size >= limit,
                    )
                }
            }

    private val profiles: Flow<Map<String, ProfileRecord>> = combine(
        combine(feedPage, search) { page, snapshot -> page.apps + snapshot.results.orEmpty() }.authorProfiles(iolite),
        combine(stacks, activity) { stackState, activityState ->
            stackState.items.map { it.pubkey }.toSet() + activityState.state.items.map { it.pubkey }
        }.distinctUntilChanged().flatMapLatest(iolite::observeProfiles),
    ) { authors, people -> people + authors }

    val uiState: StateFlow<HomeUiState> = combine(
        combine(searchQuery, submittedQuery, search, ::Triple),
        feedPage,
        combine(stacks, stackApps, stackLimit, ::Triple),
        combine(profiles, activity, ::Pair),
        requestedPages,
    ) { (query, submitted, snapshot), page, (stackState, stackApps, stackCap), (profiles, activity), pages ->
        HomeUiState(
            searchQuery = query,
            submittedQuery = submitted,
            searchResults = snapshot.results,
            searchFacts = snapshot.facts,
            stacks = stackState.items,
            stackApps = stackApps,
            stacksLoading = stackState.items.isEmpty() && stackState.isLoading,
            stacksError = stackState.error?.message,
            stacksCanLoadMore = stackState.items.size >= stackCap,
            activity = activity.state.items,
            activityLoading = activity.state.items.isEmpty() && activity.state.isLoading,
            activityCanLoadMore = activity.canLoadMore,
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
        dismissedFacts.value = emptySet()
        savedStateHandle[SEARCH_QUERY_KEY] = query
        savedStateHandle[SUBMITTED_QUERY_KEY] = query
    }

    fun clearSearch() {
        dismissedFacts.value = emptySet()
        savedStateHandle[SEARCH_QUERY_KEY] = ""
        savedStateHandle[SUBMITTED_QUERY_KEY] = ""
    }

    /** Removes a chip: a sheet filter is unchosen, a parsed fact stops filtering this query. */
    fun removeFilter(fact: SearchFact) {
        if (fact in chosenFilters.value) chosenFilters.update { it - fact } else dismissedFacts.update { it + fact }
    }

    fun toggleFilter(fact: SearchFact) {
        if (fact in uiState.value.searchFacts) removeFilter(fact) else {
            dismissedFacts.update { it - fact }
            chosenFilters.update { it + fact }
        }
    }

    fun loadMoreActivity() {
        if (uiState.value.activityCanLoadMore) activityLimit.update { it + ACTIVITY_PAGE }
    }

    fun loadMoreStacks() {
        if (uiState.value.stacksCanLoadMore) stackLimit.update { it + STACK_PAGE }
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
        private const val STACK_PAGE = 20
        private const val ACTIVITY_PAGE = 20
        /** Latest releases whose zaps the activity feed asks for. */
        private const val ACTIVITY_APPS = 100

        fun factory(iolite: Iolite, encodeQuery: (String) -> ByteArray?): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(iolite, createSavedStateHandle(), encodeQuery) }
        }
    }
}

internal const val STOP_TIMEOUT_MILLIS = 5_000L

private class ActivityPage(val state: QueryState<List<ActivityItem>>, val canLoadMore: Boolean)

private data class SearchInput(val submitted: String, val chosen: Set<SearchFact>, val dismissed: Set<SearchFact>)

/** [results] is null until a submitted query is long enough to run. */
private data class SearchSnapshot(
    val results: List<AppRecord>? = null,
    val facts: List<SearchFact> = emptyList(),
)
