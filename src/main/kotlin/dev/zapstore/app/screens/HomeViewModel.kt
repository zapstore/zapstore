package dev.zapstore.app.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.AppConfig
import dev.zapstore.iolite.AppCoordinate
import dev.zapstore.iolite.AppFilter
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.Query
import dev.zapstore.iolite.QueryState
import dev.zapstore.iolite.StackRecord
import dev.zapstore.iolite.SearchFact
import dev.zapstore.iolite.excludeBit
import dev.zapstore.iolite.hardBit
import dev.zapstore.iolite.normalizeSearchQuery
import dev.zapstore.iolite.repositoryQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class HomeUiState(
    val searchQuery: String = "",
    /** The query last submitted with Enter; empty until then. */
    val submittedQuery: String = "",
    /** Null until a query is submitted; empty when it matched nothing. */
    val searchResults: List<AppRecord>? = null,
    /** Active hard filters: only the ones the user chose. A fact phrase in [submittedQuery] filters nothing. */
    val searchFacts: List<SearchFact> = emptyList(),
    /** Catalog stacks whose name or description holds the submitted query. Fact filters do not apply to them. */
    val searchStacks: List<StackRecord> = emptyList(),
    /** The apps the tiles of [searchStacks] show. */
    val searchStackApps: Map<String, AppRecord> = emptyMap(),
    /** Developers (hex keys, best match first) whose profile name or npub holds the submitted query. */
    val searchDevelopers: List<String> = emptyList(),
    val stacks: List<StackRecord> = emptyList(),
    val stackApps: Map<String, AppRecord> = emptyMap(),
    val stacksCanLoadMore: Boolean = false,
    /** Newest comments and zaps on catalog apps, with the stacks their curators last changed. */
    val activity: List<ActivityItem> = emptyList(),
    /** The apps the tiles of the stacks in [activity] show. */
    val activityStackApps: Map<String, AppRecord> = emptyMap(),
    val activityCanLoadMore: Boolean = false,
    val activityFilter: ActivityFilter = ActivityFilter.All,
    val profiles: Map<String, ProfileRecord> = emptyMap(),
    /** Keys of the active catalogs, and the one they signed with before the rotation. */
    val catalogKeys: Set<String> = emptySet(),
    /**
     * Every local read behind the first screen has landed: stacks and the apps their tiles show, the activity feed, and
     * the profiles of the people in both. The launch splash stays up until this is true, so nothing fills in after it.
     */
    val ready: Boolean = false,
)

/**
 * Home: local search of apps, stacks and developers, Zapstore Community stacks that name a catalog app, and Activity: releases, comments, zaps and changed stacks.
 * Every flow here is a SQLite observer and stops with the collector.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val iolite: Iolite,
    private val savedStateHandle: SavedStateHandle,
    private val encodeQuery: (String) -> ByteArray?,
) : ViewModel() {
    private val searchQuery = savedStateHandle.getStateFlow(SEARCH_QUERY_KEY, "")
    private val submittedQuery = savedStateHandle.getStateFlow(SUBMITTED_QUERY_KEY, "")
    private val activityFilter = MutableStateFlow(ActivityFilter.All)
    private val stackLimit = MutableStateFlow(STACK_PAGE)
    private val activityLimit = MutableStateFlow(ACTIVITY_PAGE)

    /** The hour the shelf is shuffled for. It ticks when the hour changes, so an open Home rotates with the web shelf. */
    private val shelfHour: Flow<Int> = flow {
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            emit((now / SHELF_ROTATE_MS).toInt())
            val wait = SHELF_ROTATE_MS - now % SHELF_ROTATE_MS
            delay(if (wait == 0L) SHELF_ROTATE_MS else wait)
        }
    }.distinctUntilChanged()

    /** Filters chosen in the sheet; they outlive a query. */
    private val chosenFilters = MutableStateFlow<Set<SearchFact>>(emptySet())

    /** Runs only for a submitted query; typing alone searches nothing. */
    private val search: Flow<SearchSnapshot> =
        combine(submittedQuery, chosenFilters) { submitted, chosen -> SearchInput(normalizeSearchQuery(submitted), chosen) }
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
            facts = SearchFact.entries.filter { it in input.chosen }
            val hard = facts.fold(0) { bits, fact -> bits or fact.hardBit }
            filter = AppFilter(
                search = query,
                queryVector = encode(query),
                hard = hard,
                exclude = facts.fold(0) { bits, fact -> bits or fact.excludeBit },
                boost = hard,
                limit = SEARCH_LIMIT,
            )
        }
        emitAll(iolite.observeApps(filter).map { apps -> SearchSnapshot(apps, facts) })
    }

    private suspend fun encode(query: String): ByteArray? = try {
        withContext(Dispatchers.Default) { encodeQuery(query) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    /** The keys of every active catalog, in the user's order. */
    private val catalogKeys: Flow<List<String>> = iolite.observeCatalogs()
        .map { catalogs -> catalogs.mapNotNull { it.manifestPubkey } }
        .distinctUntilChanged()

    /**
     * The stacks of every active catalog, shipped in their bundles and read locally. The shelf keeps the ones
     * that name a catalog app, then rotates that whole set; paging only reveals more of the same order.
     */
    private val stacks: Flow<QueryState<List<StackRecord>>> = catalogKeys
        .flatMapLatest { keys ->
            if (keys.isEmpty()) flowOf(QueryState(emptyList<StackRecord>()))
            else iolite.query(Query.catalogStacks(keys, SHELF_READ))
        }

    /** Null until the first local read of these stacks' apps lands; both are local, so that read is never "loading". */
    private val stackApps: Flow<Map<String, AppRecord>?> = stacks
        .map { state -> state.items.flatMap(StackRecord::appIds).toSet() }
        .distinctUntilChanged()
        .flatMapLatest { ids -> iolite.observeAppsById(ids).map<Map<String, AppRecord>, Map<String, AppRecord>?> { it }.onStart { emit(null) } }

    /**
     * Stacks and developers for the submitted query, read locally with the same minimum length as apps.
     * A repository URL matches only the repository, so it finds neither.
     */
    private val matches: Flow<SearchMatches> = combine(submittedQuery, catalogKeys) { submitted, keys -> normalizeSearchQuery(submitted) to keys }
        .distinctUntilChanged()
        .flatMapLatest { (query, keys) ->
            if (query.length < MIN_SEARCH_LENGTH || repositoryQuery(query) != null) {
                flowOf(SearchMatches())
            } else {
                combine(
                    iolite.observeStackMatches(keys, query, STACK_MATCHES),
                    iolite.observeDevelopers(query, DEVELOPER_MATCHES),
                    ::Pair,
                ).flatMapLatest { (stacks, developers) ->
                    iolite.observeAppsById(stacks.flatMap(StackRecord::appIds).toSet()).map { apps ->
                        SearchMatches(stacks.filter { stack -> stack.appIds.any(apps::containsKey) }, apps, developers)
                    }
                }
            }
        }

    /**
     * Comments on any app, and zaps: on the latest releases by `a`, and paid to a catalog key by `p`
     * (most receipts carry no `a`). The relays are asked for more than a page, since entries on apps the
     * catalog does not show are dropped, as the web page's Activity does.
     */
    private val comments: Flow<ActivityPage> =
        combine(
            iolite.observeApps(AppFilter(limit = ACTIVITY_APPS))
                .map { apps -> apps.map { AppCoordinate(it.eventPubkey, it.appId) } }
                .distinctUntilChanged(),
            catalogKeys,
            activityLimit,
        ) { anchors, keys, limit -> Triple(anchors, keys, limit) }
            .flatMapLatest { (anchors, keys, limit) ->
                val read = maxOf(ACTIVITY_READ, limit * ACTIVITY_READ_FACTOR)
                val query = Query.activity(
                    zapAnchors = anchors,
                    limit = read,
                    recipients = keys + AppConfig.LEGACY_CATALOG_PUBKEY,
                    lists = keys,
                )
                iolite.query(query, AppConfig.activityQuery).map { Triple(limit, read, it) }
            }
            .flatMapLatest { (limit, read, state) ->
                val record = state.items
                iolite.observeAppsById(record.appIds).map { apps ->
                    val shown = record.items(apps)
                    ActivityPage(
                        QueryState(shown.take(limit), state.relays, state.settled),
                        canLoadMore = shown.size > limit || record.comments.size >= read || record.zaps.size >= read,
                    )
                }
            }
            .onStart { emit(ActivityPage(QueryState(emptyList()), canLoadMore = false)) }

    /**
     * The stacks their curators published or changed last: public, one per curator, and naming at least one
     * catalog app, newest first. The web page's Activity does the same with six.
     */
    private val changedStacks: Flow<Pair<List<StackRecord>, Map<String, AppRecord>>> =
        iolite.query(Query.socialStacks(AppConfig.platforms, ACTIVITY_STACKS_READ), AppConfig.activityStacksQuery)
            .map { state -> state.items.filter { it.apps.isNotEmpty() } }
            .distinctUntilChanged()
            .flatMapLatest { stacks ->
                iolite.observeAppsById(stacks.flatMap(StackRecord::appIds).toSet()).map { apps ->
                    stacks.filter { stack -> stack.appIds.any(apps::containsKey) }.distinctBy(StackRecord::pubkey).take(ACTIVITY_STACKS) to apps
                }
            }
            .onStart { emit(emptyList<StackRecord>() to emptyMap()) }

    /** The newest release of each app, as entries, newest first. Read locally. */
    private val releases: Flow<List<ActivityItem>> = activityLimit.flatMapLatest { limit ->
        iolite.observeApps(AppFilter(limit = limit)).map { apps -> apps.map(AppRecord::releaseActivity).sortedByDescending { it.createdAt } }
    }

    /**
     * Releases, comments, zaps, and changed stacks as one list, newest first, narrowed by the filter. A stack
     * takes a place in the page like any entry. Releases show at once; the social entries join as they land.
     */
    private val activity: Flow<ActivityPage> = combine(comments, activityLimit, changedStacks, releases, activityFilter) { page, limit, (stacks, apps), releases, filter ->
        val social = page.state.items + stacks.map(StackRecord::activity)
        val pool = when (filter) {
            ActivityFilter.All -> social + releases
            ActivityFilter.Releases -> releases
            ActivityFilter.Social -> social
        }.sortedByDescending { it.createdAt }
        ActivityPage(
            QueryState(pool.take(limit)),
            canLoadMore = when (filter) {
                ActivityFilter.Releases -> releases.size >= limit
                ActivityFilter.Social -> page.canLoadMore
                ActivityFilter.All -> page.canLoadMore || releases.size >= limit || pool.size > limit
            },
            stackApps = apps,
        )
    }

    private val profiles: Flow<Map<String, ProfileRecord>> = combine(
        search.map { it.results.orEmpty() }.authorProfiles(iolite),
        matches.map { it.developers.toSet() }.distinctUntilChanged().flatMapLatest(iolite::observeProfiles),
        combine(stacks, activity) { stackState, activityState ->
            stackState.items.map { it.pubkey }.toSet() + activityState.state.items.map { it.pubkey }
        }.distinctUntilChanged().flatMapLatest(iolite::observeProfiles),
    ) { authors, developers, people -> people + developers + authors }

    val uiState: StateFlow<HomeUiState> = combine(
        combine(combine(searchQuery, submittedQuery, search, ::Triple), matches, ::Pair),
        combine(stacks, stackApps, combine(stackLimit, shelfHour, ::Pair), ::Triple),
        combine(profiles, activity, ::Pair),
        activityFilter,
        catalogKeys.map { it.toSet() + AppConfig.LEGACY_CATALOG_PUBKEY },
    ) { (searched, found), (stackState, stackAppsOrNull, paging), (profiles, activity), filter, catalogs ->
        val (query, submitted, snapshot) = searched
        val (stackCap, hour) = paging
        // Not yet joined: show every stack rather than wait, since both reads are local.
        // Joined: keep only stacks that list a catalog app, as always.
        val stackApps = stackAppsOrNull ?: emptyMap()
        val eligible = if (stackAppsOrNull == null) stackState.items else stackState.items.filter { stack -> stack.appIds.any(stackApps::containsKey) }
        val ordered = shelfOrder(eligible, hour, StackRecord::eventId)
        HomeUiState(
            searchQuery = query,
            submittedQuery = submitted,
            searchResults = snapshot.results,
            searchFacts = snapshot.facts,
            searchStacks = found.stacks,
            searchStackApps = found.stackApps,
            searchDevelopers = found.developers,
            stacks = ordered.take(stackCap),
            stackApps = stackApps,
            stacksCanLoadMore = ordered.size > stackCap,
            activity = activity.state.items,
            activityStackApps = activity.stackApps,
            activityCanLoadMore = activity.canLoadMore,
            activityFilter = filter,
            profiles = profiles,
            catalogKeys = catalogs,
            ready = stackAppsOrNull != null,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        HomeUiState(searchQuery = searchQuery.value, submittedQuery = submittedQuery.value),
    )

    fun onSearchQueryChanged(value: String) {
        savedStateHandle[SEARCH_QUERY_KEY] = value
    }

    fun submitSearch() = openSearch(searchQuery.value)

    /** Shows [query] in the search field and runs it. A link and the keyboard both land here. */
    fun openSearch(query: String) {
        val trimmed = query.trim()
        savedStateHandle[SEARCH_QUERY_KEY] = trimmed
        savedStateHandle[SUBMITTED_QUERY_KEY] = trimmed
    }

    fun clearSearch() {
        savedStateHandle[SEARCH_QUERY_KEY] = ""
        savedStateHandle[SUBMITTED_QUERY_KEY] = ""
    }

    /** Removes a chip: the filter is unchosen. */
    fun removeFilter(fact: SearchFact) {
        chosenFilters.update { it - fact }
    }

    fun toggleFilter(fact: SearchFact) {
        chosenFilters.update { if (fact in it) it - fact else it + fact }
    }

    fun loadMoreActivity() {
        if (uiState.value.activityCanLoadMore) activityLimit.update { it + ACTIVITY_PAGE }
    }

    fun loadMoreStacks() {
        if (uiState.value.stacksCanLoadMore) stackLimit.update { it + STACK_PAGE }
    }

    fun setActivityFilter(filter: ActivityFilter) {
        activityFilter.value = filter
        activityLimit.value = ACTIVITY_PAGE
    }

    companion object {
        private const val SEARCH_QUERY_KEY = "searchQuery"
        private const val SUBMITTED_QUERY_KEY = "submittedQuery"
        private const val MIN_SEARCH_LENGTH = 2
        /** The relay's cap on a NIP-50 request; the phone shows as many. */
        private const val SEARCH_LIMIT = 20
        /** Stacks and developers listed under the apps of a search. */
        private const val STACK_MATCHES = 8
        private const val DEVELOPER_MATCHES = 8
        private const val STACK_PAGE = 20
        /** Catalog stacks read for the shelf before it rotates. The web shelf reads the same number. */
        private const val SHELF_READ = 200
        /** The shelf order changes once an hour, with the web shelf, and holds still in between. */
        private const val SHELF_ROTATE_MS = 60L * 60L * 1000L
        /** Activity entries per page; the next page waits for a tap on Load more. */
        private const val ACTIVITY_PAGE = 50
        /** Latest releases whose zaps the activity feed asks for. */
        private const val ACTIVITY_APPS = 100
        /** Comments and zaps asked of each relay, at least; the web page asks 60. */
        private const val ACTIVITY_READ = 60
        /** Candidates read per entry shown, so a page still fills when some are on apps the catalog hides. */
        private const val ACTIVITY_READ_FACTOR = 3
        /** Stacks the activity feed shows, one per curator, out of the newest it reads. */
        private const val ACTIVITY_STACKS = 6
        private const val ACTIVITY_STACKS_READ = 30

        fun factory(iolite: Iolite, encodeQuery: (String) -> ByteArray?): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(iolite, createSavedStateHandle(), encodeQuery) }
        }
    }
}

internal const val STOP_TIMEOUT_MILLIS = 5_000L

internal class ActivityPage(
    val state: QueryState<List<ActivityItem>>,
    val canLoadMore: Boolean,
    val stackApps: Map<String, AppRecord> = emptyMap(),
)

private data class SearchInput(val submitted: String, val chosen: Set<SearchFact>)

/** What a submitted query finds besides apps. */
private class SearchMatches(
    val stacks: List<StackRecord> = emptyList(),
    val stackApps: Map<String, AppRecord> = emptyMap(),
    val developers: List<String> = emptyList(),
)

/** [results] is null until a submitted query is long enough to run. */
private data class SearchSnapshot(
    val results: List<AppRecord>? = null,
    val facts: List<SearchFact> = emptyList(),
)

/**
 * The home shelf's order for one hour, the same rotation as the web shelf: event id order, then a seeded
 * Fisher-Yates. The same hour and the same ids give the same order, so loading another page does not move
 * the cards already on screen.
 */
internal fun <T> shelfOrder(items: List<T>, hour: Int, id: (T) -> String): List<T> {
    var state = hour.toUInt()
    fun next(): Double {
        state += 0x6d2b79f5u
        var t = state
        t = (t xor (t shr 15)) * (t or 1u)
        t = t xor (t + ((t xor (t shr 7)) * (t or 61u)))
        return (t xor (t shr 14)).toDouble() / 4294967296.0
    }
    val out = items.sortedBy(id).toMutableList()
    for (i in out.lastIndex downTo 1) {
        val swap = out[i]
        val j = (next() * (i + 1)).toInt()
        out[i] = out[j]
        out[j] = swap
    }
    return out
}
