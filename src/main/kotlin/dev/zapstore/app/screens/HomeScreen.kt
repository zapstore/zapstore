package dev.zapstore.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import dev.zapstore.app.components.AuthorByline
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapWarning
import androidx.compose.foundation.lazy.itemsIndexed
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.app.ZapRadius
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import java.text.NumberFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.HorizontalDivider
import dev.zapstore.app.components.ZapMotion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapTitle3Style
import dev.zapstore.app.components.SettingsGroup
import dev.zapstore.app.components.listItemMotion
import dev.zapstore.app.components.SwitchRow
import dev.zapstore.app.components.ZapChip
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapButton
import dev.zapstore.iolite.SearchFact
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.GroupHeader
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.DeveloperResultRow
import dev.zapstore.app.components.StackResultRow
import dev.zapstore.app.components.StackCard
import dev.zapstore.app.components.StackCardHeight
import dev.zapstore.app.components.StackShelfFraction
import dev.zapstore.app.components.AppIcon
import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.app.components.StatusText
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
    onDeveloperClick: (String) -> Unit = {},
    onActivityFilter: (ActivityFilter) -> Unit = {},
    onLoadMoreStacks: () -> Unit = {},
    onLoadMoreActivity: () -> Unit = {},
    onSeeAllStacks: () -> Unit = {},
    onRemoveFilter: (SearchFact) -> Unit = {},
    onToggleFilter: (SearchFact) -> Unit = {},
    reselected: Flow<String> = emptyFlow(),
) {
    val noActivity = stringResource(R.string.no_activity)
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    if (filtersOpen) {
        FilterSheet(active = state.searchFacts, onToggle = onToggleFilter, onDismiss = { filtersOpen = false })
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
    LoadMoreNearEnd(
        { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount },
        state.activityCanLoadMore,
        onLoadMoreActivity,
    )
    // Results are inserted above the stacks, which the list would otherwise stay anchored to.
    LaunchedEffect(state.submittedQuery, state.searchResults == null) { listState.scrollToItem(0) }
    LaunchedEffect(reselected) {
        reselected.collect { route -> if (route == Routes.HOME) listState.animateScrollToItem(0) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ZapSearchField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChanged,
            onSearch = onSearchSubmitted,
            placeholder = stringResource(R.string.search_apps),
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = ZapText3,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty() || state.searchResults != null) {
                    val clearDescription = stringResource(R.string.clear_search)
                    IconButton(
                        onClick = {
                            onSearchCleared()
                            searchFocusRequester.requestFocus()
                        },
                        modifier = Modifier
                            .size(ZapSize.control)
                            .semantics { contentDescription = clearDescription }
                            .testTag("clearSearch"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            tint = ZapText3,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ZapSpacing.space4, end = ZapSpacing.space4, top = ZapSpacing.space1, bottom = ZapSpacing.space2)
                .focusRequester(searchFocusRequester)
                .testTag("searchField"),
        )
        val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
        val hairline by animateFloatAsState(if (scrolled) 1f else 0f, ZapMotion.fast(), label = "searchHairline")
        HorizontalDivider(color = ZapLine, modifier = Modifier.graphicsLayer { alpha = hairline })

        BoxWithConstraints(Modifier.weight(1f)) {
            val shelfWidth = (maxWidth - ZapSpacing.space4 * 2) * StackShelfFraction
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("homeList"),
                contentPadding = PaddingValues(start = ZapSpacing.space4, end = ZapSpacing.space4, bottom = ZapSpacing.space8),
                state = listState,
            ) {
                state.searchResults?.let { results -> searchResults(state, results, onAppClick, onStackClick, onDeveloperClick, onRemoveFilter) { filtersOpen = true } }

                item(key = "stacksHeader", contentType = "header") {
                    GroupHeader(
                        stringResource(R.string.stacks),
                        action = stringResource(R.string.see_all),
                        onAction = onSeeAllStacks,
                        actionTag = "seeAllStacks",
                    )
                }
                item(key = "stacks", contentType = "stacks") {
                    when {
                        state.stacks.isNotEmpty() -> {
                            val rows = if (state.stacks.size < 2) 1 else 2
                            LazyHorizontalGrid(
                                rows = GridCells.Fixed(rows),
                                state = stackShelf,
                                horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                                verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                                modifier = Modifier
                                    .height(StackCardHeight * rows + if (rows == 2) ZapSpacing.space2 else 0.dp)
                                    .testTag("stackShelf"),
                            ) {
                                gridItems(state.stacks, key = { it.eventId }) { stack ->
                                    StackCard(
                                        stack = stack,
                                        apps = state.stackApps,
                                        onClick = { onStackClick(stack) },
                                        modifier = Modifier.width(shelfWidth).testTag("stack:${stack.identifier}"),
                                    )
                                }
                            }
                        }
                        state.stacksLoading -> Unit
                        else -> StatusText(state.stacksError ?: stringResource(R.string.no_stacks))
                    }
                }

                item(key = "activityHeader", contentType = "header") {
                    GroupHeader(
                        stringResource(R.string.activity),
                        Modifier.padding(top = ZapSpacing.space4),
                        trailing = { ActivityFilterMenu(state.activityFilter, onActivityFilter) },
                    )
                }
                activityRows(
                    items = state.activity,
                    loading = state.activityLoading,
                    canLoadMore = state.activityCanLoadMore,
                    profiles = state.profiles,
                    stackApps = state.activityStackApps,
                    emptyText = noActivity,
                    onOpen = { item -> item.stack?.let(onStackClick) ?: item.app?.let(onAppClick) },
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
    onOpenFilters: () -> Unit,
) {
    item(key = "searchFacts", contentType = "status") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
            modifier = Modifier.padding(bottom = ZapSpacing.space2).testTag("searchFacts"),
        ) {
            state.searchFacts.forEach { fact ->
                val label = fact.label()
                val remove = stringResource(R.string.remove_filter, label)
                ZapChip(
                    text = label,
                    onClick = { onRemoveFilter(fact) },
                    selected = true,
                    trailingIcon = R.drawable.ic_close,
                    modifier = Modifier
                        .semantics { contentDescription = remove }
                        .testTag("filter:${fact.name}"),
                )
            }
            ZapChip(
                text = stringResource(R.string.filters),
                onClick = onOpenFilters,
                trailingIcon = R.drawable.ic_chevron_down,
                modifier = Modifier.testTag("openFilters"),
            )
        }
    }
    val others = state.searchStacks.isNotEmpty() || state.searchDevelopers.isNotEmpty()
    if (results.isEmpty() && (state.searchFacts.isNotEmpty() || !others)) {
        item(key = "noResults", contentType = "empty") {
            val message = if (state.searchFacts.isEmpty()) R.string.no_search_results else R.string.no_filtered_results
            Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
                EmptyRow(stringResource(message), icon = R.drawable.ic_search)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                    verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                ) {
                    state.searchFacts.forEach { fact ->
                        ZapButton(
                            text = stringResource(R.string.remove_filter, fact.label()),
                            onClick = { onRemoveFilter(fact) },
                            style = ZapButtonStyle.Tonal,
                            modifier = Modifier.testTag("relax:${fact.name}"),
                        )
                    }
                }
            }
        }
    }
    items(results, key = { "search:${it.appId}" }, contentType = { "app" }) { app ->
        AppRow(
            app = app,
            onClick = { onAppClick(app) },
            author = app.authorPubkey?.let(state.profiles::get),
            modifier = listItemMotion().testTag("searchResult:${app.appId}"),
        )
    }
    items(state.searchStacks, key = { "searchStack:${it.eventId}" }, contentType = { "stack" }) { stack ->
        StackResultRow(
            stack = stack,
            apps = state.searchStackApps,
            onClick = { onStackClick(stack) },
            modifier = listItemMotion().testTag("searchStack:${stack.identifier}"),
        )
    }
    items(state.searchDevelopers, key = { "searchDeveloper:$it" }, contentType = { "developer" }) { pubkey ->
        DeveloperResultRow(
            pubkey = pubkey,
            profile = state.profiles[pubkey],
            onClick = { onDeveloperClick(pubkey) },
            modifier = listItemMotion().testTag("searchDeveloper:$pubkey"),
        )
    }
    item(key = "searchEnd", contentType = "gap") {
        HorizontalDivider(color = ZapLine, modifier = Modifier.padding(top = ZapSpacing.space2, bottom = ZapSpacing.space2))
    }
}


@Composable
private fun FilterSheet(active: List<SearchFact>, onToggle: (SearchFact) -> Unit, onDismiss: () -> Unit) {
    ZapSheet(onDismiss = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
            modifier = Modifier.padding(start = ZapSpacing.space4, end = ZapSpacing.space4, bottom = ZapSpacing.space6),
        ) {
            Text(stringResource(R.string.filters), style = ZapTitle3Style)
            Text(stringResource(R.string.filters_hint), color = ZapText2, style = MaterialTheme.typography.bodyMedium)
            SettingsGroup(Modifier.padding(top = ZapSpacing.space2)) {
                SearchFact.entries.forEachIndexed { index, fact ->
                    if (index > 0) HorizontalDivider(color = ZapLine)
                    SwitchRow(
                        title = fact.label(),
                        checked = fact in active,
                        onCheckedChange = { onToggle(fact) },
                        modifier = Modifier.testTag("sheetFilter:${fact.name}"),
                    )
                }
            }
        }
    }
}

/** The current Activity filter, at the right of the label. The other two choices sit in the menu. */
@Composable
private fun ActivityFilterMenu(selected: ActivityFilter, onSelect: (ActivityFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space1),
            modifier = Modifier
                .clip(RoundedCornerShape(ZapRadius.full))
                .clickable { open = true }
                .padding(horizontal = ZapSpacing.space2, vertical = ZapSpacing.space1)
                .testTag("activityFilter"),
        ) {
            Text(stringResource(selected.label()), color = ZapText2, style = MaterialTheme.typography.labelLarge)
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = ZapText3, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ActivityFilter.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label())) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    trailingIcon = if (option == selected) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = ZapActionText) }
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("activityFilter:${option.name}"),
                )
            }
        }
    }
}

private fun ActivityFilter.label(): Int = when (this) {
    ActivityFilter.All -> R.string.activity_filter_all
    ActivityFilter.Releases -> R.string.activity_filter_releases
    ActivityFilter.Social -> R.string.activity_filter_social
}
