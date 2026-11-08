package dev.zapstore.app.screens

import androidx.activity.compose.BackHandler
import dev.zapstore.app.ZapRadius
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.HorizontalDivider
import dev.zapstore.app.components.ZapMotion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapTitle3Style
import dev.zapstore.app.components.SettingsGroup
import dev.zapstore.app.components.SwitchRow
import dev.zapstore.app.components.ZapChip
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapButton
import dev.zapstore.iolite.SearchFact
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText3
import dev.zapstore.app.components.AppRow
import dev.zapstore.app.components.LocalInstalledVersions
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.GroupHeader
import dev.zapstore.app.components.DeveloperResultRow
import dev.zapstore.app.components.StackResultRow
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.components.ZapSearchField
import dev.zapstore.app.facts.label
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.StackRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun HomeScreen(
    state: HomeUiState,
    onSearchQueryChanged: (String) -> Unit,
    onSearchSubmitted: () -> Unit,
    onSearchCleared: () -> Unit,
    onAppClick: (AppRecord) -> Unit,
    onStackClick: (StackRecord) -> Unit,
    modifier: Modifier = Modifier,
    /** A comment in Recent activity: opens its app at that comment. */
    onCommentClick: (AppRecord, String) -> Unit = { app, _ -> onAppClick(app) },
    onDeveloperClick: (String) -> Unit = {},
    onActivityFilter: (ActivityFilter) -> Unit = {},
    onLoadMoreStacks: () -> Unit = {},
    onLoadMoreActivity: () -> Unit = {},
    onSeeAllStacks: () -> Unit = {},
    onRemoveFilter: (SearchFact) -> Unit = {},
    onToggleFilter: (SearchFact) -> Unit = {},
    reselected: Flow<String> = emptyFlow(),
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var hideInstalled by rememberSaveable { mutableStateOf(false) }
    if (filtersOpen) {
        FilterSheet(
            active = state.searchFacts,
            onToggle = onToggleFilter,
            onDismiss = { filtersOpen = false },
            hideInstalled = hideInstalled,
            onToggleHideInstalled = { hideInstalled = !hideInstalled },
        )
    }
    val listState = rememberLazyListState()
    val stackShelf = rememberLazyGridState()
    val searchFocusRequester = remember { FocusRequester() }
    // The first Back empties the search; only the next one leaves the app.
    BackHandler(enabled = state.searchQuery.isNotEmpty() || state.searchResults != null, onBack = onSearchCleared)
    LoadMoreNearEnd(
        {
            val info = stackShelf.layoutInfo
            info.visibleItemsInfo.maxOfOrNull { it.index } to info.totalItemsCount
        },
        state.stacksCanLoadMore,
        onLoadMoreStacks,
    )
    // Activity pages in as the list nears its end, a page at a time.
    LoadMoreNearEnd(
        { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount },
        state.activityCanLoadMore,
        onLoadMoreActivity,
    )
    // Results are inserted above the stacks, which the list would otherwise stay anchored to.
    // Skip the first run: re-entering Home from the back stack must keep the restored position.
    var scrollKey by rememberSaveable { mutableStateOf<Pair<String?, Boolean>?>(null) }
    val queryKey = state.submittedQuery to (state.searchResults == null)
    LaunchedEffect(queryKey) {
        if (scrollKey != null && scrollKey != queryKey) listState.scrollToItem(0)
        scrollKey = queryKey
    }
    LaunchedEffect(reselected) {
        reselected.collect { route -> if (route == Routes.HOME) listState.animateScrollToItem(0) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AppSearchField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChanged,
            onSearch = onSearchSubmitted,
            onClear = {
                onSearchCleared()
                searchFocusRequester.requestFocus()
            },
            placeholder = stringResource(R.string.search_apps),
            hasResults = state.searchResults != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ZapSpacing.space4, end = ZapSpacing.space4, top = ZapSpacing.space1, bottom = ZapSpacing.space2)
                .focusRequester(searchFocusRequester)
                .testTag("searchField"),
        )
        val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
        val hairline by animateFloatAsState(if (scrolled) 1f else 0f, ZapMotion.fast(), label = "searchHairline")
        HorizontalDivider(color = ZapLine, modifier = Modifier.graphicsLayer { alpha = hairline })

        Box(Modifier.weight(1f)) {
            val activityEmptyText = stringResource(R.string.activity_empty)
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("homeList"),
                contentPadding = PaddingValues(start = ZapSpacing.space4, end = ZapSpacing.space4, bottom = ZapSpacing.space8),
                state = listState,
            ) {
                state.searchResults?.let { results ->
                    searchResults(state, results, onAppClick, onStackClick, onDeveloperClick, onRemoveFilter, hideInstalled, { hideInstalled = false }) { filtersOpen = true }
                }

                item(key = "stacks", contentType = "stacks") {
                    if (state.stacks.isEmpty()) {
                        EmptyRow(
                            stringResource(R.string.stacks_empty),
                            modifier = Modifier.padding(top = ZapSpacing.space2).testTag("stacksEmpty"),
                        )
                    } else {
                        Column {
                            GroupHeader(
                                stringResource(R.string.curated_stacks),
                                action = stringResource(R.string.see_all),
                                onAction = onSeeAllStacks,
                                actionTag = "seeAllStacks",
                            )
                            StacksShelf(
                                stacks = state.stacks,
                                apps = state.stackApps,
                                onStackClick = onStackClick,
                                gridState = stackShelf,
                                // The list already pads 16 dp, so the first card starts where the header does.
                                horizontalPadding = 0.dp,
                                modifier = Modifier.padding(top = ZapSpacing.space2),
                            )
                        }
                    }
                }

                item(key = "activityHeader", contentType = "header") {
                    GroupHeader(
                        stringResource(R.string.recent_activity),
                        Modifier.padding(top = ZapSpacing.space4),
                        trailing = { SocialToggle(state.activityFilter, onActivityFilter) },
                    )
                }
                activityRows(
                    items = state.activity,
                    loading = false,
                    canLoadMore = state.activityCanLoadMore,
                    profiles = state.profiles,
                    stackApps = state.activityStackApps,
                    emptyText = activityEmptyText,
                    onOpen = { item ->
                        item.stack?.let(onStackClick)
                            ?: item.app?.let { app -> if (item.comment != null) onCommentClick(app, item.eventId) else onAppClick(app) }
                    },
                    catalogKeys = state.catalogKeys,
                )
            }
        }
    }
}

/**
 * Submitted results above the rest of Home: filter chips, then apps, stacks and developers in one list of
 * the same row, or what to relax. Filters narrow apps only.
 */
private fun LazyListScope.searchResults(
    state: HomeUiState,
    results: List<AppRecord>,
    onAppClick: (AppRecord) -> Unit,
    onStackClick: (StackRecord) -> Unit,
    onDeveloperClick: (String) -> Unit,
    onRemoveFilter: (SearchFact) -> Unit,
    hideInstalled: Boolean,
    onShowInstalled: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    item(key = "searchPanel", contentType = "searchPanel") {
        val installedIds = LocalInstalledVersions.current.keys
        val shown = if (hideInstalled) results.filter { it.appId !in installedIds } else results
        SearchPanel(
            facts = state.searchFacts,
            shown = shown,
            hasOthers = state.searchStacks.isNotEmpty() || state.searchDevelopers.isNotEmpty(),
            onRemoveFilter = onRemoveFilter,
            onOpenFilters = onOpenFilters,
            hideInstalled = hideInstalled,
            onShowInstalled = onShowInstalled,
        ) {
            shown.forEach { app ->
                key("search:${app.appId}") {
                    AppRow(
                        app = app,
                        onClick = { onAppClick(app) },
                        author = app.authorPubkey?.let(state.profiles::get),
                        modifier = Modifier.testTag("searchResult:${app.appId}"),
                    )
                }
            }
            state.searchStacks.forEach { stack ->
                key("searchStack:${stack.eventId}") {
                    StackResultRow(
                        stack = stack,
                        apps = state.searchStackApps,
                        onClick = { onStackClick(stack) },
                        modifier = Modifier.testTag("searchStack:${stack.identifier}"),
                    )
                }
            }
            state.searchDevelopers.forEach { pubkey ->
                key("searchDeveloper:$pubkey") {
                    DeveloperResultRow(
                        pubkey = pubkey,
                        profile = state.profiles[pubkey],
                        onClick = { onDeveloperClick(pubkey) },
                        modifier = Modifier.testTag("searchDeveloper:$pubkey"),
                    )
                }
            }
        }
    }
}

/** Mixes comments, zaps and stack updates in with the releases: a checked chip once on, a plain "+" before. */
@Composable
private fun SocialToggle(filter: ActivityFilter, onSelect: (ActivityFilter) -> Unit) {
    val social = filter == ActivityFilter.All
    ZapChip(
        text = stringResource(R.string.activity_filter_social),
        onClick = { onSelect(if (social) ActivityFilter.Releases else ActivityFilter.All) },
        selected = social,
        leadingIcon = if (social) R.drawable.ic_check else R.drawable.ic_plus,
        modifier = Modifier.testTag("activitySocialToggle"),
    )
}
