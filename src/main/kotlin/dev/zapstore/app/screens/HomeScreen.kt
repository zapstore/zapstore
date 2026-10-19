package dev.zapstore.app.screens

import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import dev.zapstore.app.components.AuthorByline
import dev.zapstore.app.ZapTextPrimary
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapTextSecondary
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
import androidx.compose.foundation.lazy.LazyRow
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
import dev.zapstore.app.ZapTextTertiary
import dev.zapstore.app.components.AppRow
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.GroupHeader
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.StackShelfCard
import dev.zapstore.app.components.AppShelfTile
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
    onLoadMore: () -> Unit = {},
    onLoadMoreStacks: () -> Unit = {},
    onLoadMoreActivity: () -> Unit = {},
    onSeeAllStacks: () -> Unit = {},
    onRemoveFilter: (SearchFact) -> Unit = {},
    onToggleFilter: (SearchFact) -> Unit = {},
    reselected: Flow<String> = emptyFlow(),
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    if (filtersOpen) {
        FilterSheet(active = state.searchFacts, onToggle = onToggleFilter, onDismiss = { filtersOpen = false })
    }
    val listState = rememberLazyListState()
    val stackShelf = rememberLazyListState()
    val releaseShelf = rememberLazyGridState()
    val searchFocusRequester = remember { FocusRequester() }
    LoadMoreNearEnd({ stackShelf.layoutInfo.visibleItemsInfo.lastOrNull()?.index to stackShelf.layoutInfo.totalItemsCount }, state.stacksCanLoadMore, onLoadMoreStacks)
    LoadMoreNearEnd(
        { releaseShelf.layoutInfo.visibleItemsInfo.lastOrNull()?.index to releaseShelf.layoutInfo.totalItemsCount },
        state.feed.canLoadMore && !state.feed.loadingMore,
        onLoadMore,
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
                    tint = ZapTextTertiary,
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
                            tint = ZapTextTertiary,
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
            val shelfWidth = (maxWidth - ZapSpacing.space4 * 2) * SHELF_FRACTION
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("homeList"),
                contentPadding = PaddingValues(start = ZapSpacing.space4, end = ZapSpacing.space4, bottom = ZapSpacing.space8),
                state = listState,
            ) {
                state.searchResults?.let { results -> searchResults(state, results, onAppClick, onRemoveFilter) { filtersOpen = true } }

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
                        state.stacks.isNotEmpty() -> LazyRow(
                            state = stackShelf,
                            horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space4),
                            modifier = Modifier.testTag("stackShelf"),
                        ) {
                            items(state.stacks, key = { it.eventId }) { stack ->
                                StackShelfCard(
                                    stack = stack,
                                    apps = state.stackApps,
                                    author = state.profiles[stack.pubkey],
                                    onClick = { onStackClick(stack) },
                                    modifier = Modifier.width(shelfWidth).testTag("stack:${stack.identifier}"),
                                )
                            }
                        }
                        state.stacksLoading -> LoadingIndicator(Modifier.padding(vertical = ZapSpacing.space3))
                        else -> StatusText(state.stacksError ?: stringResource(R.string.no_stacks))
                    }
                }

                item(key = "latestHeader", contentType = "header") {
                    GroupHeader(stringResource(R.string.latest_releases), Modifier.padding(top = ZapSpacing.space4))
                }
                val feed = state.feed
                item(key = "latest", contentType = "shelf") {
                    when {
                        feed.apps.isEmpty() && feed.loading -> LoadingIndicator(Modifier.padding(vertical = ZapSpacing.space3))
                        feed.apps.isEmpty() -> EmptyRow(feed.error ?: stringResource(R.string.no_releases))
                        else -> LazyHorizontalGrid(
                            rows = GridCells.Fixed(SHELF_ROWS),
                            state = releaseShelf,
                            horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space4),
                            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1),
                            modifier = Modifier.fillMaxWidth().height(SHELF_HEIGHT).testTag("releaseShelf"),
                        ) {
                            gridItems(feed.apps, key = { "app:${it.appId}" }) { app ->
                                AppShelfTile(app, onClick = { onAppClick(app) }, modifier = Modifier.width(shelfWidth).testTag("app:${app.appId}"))
                            }
                            if (feed.loadingMore) {
                                item(key = "app-list-loading") {
                                    LoadingIndicator(Modifier.testTag("appListLoading").padding(ZapSpacing.space4))
                                }
                            }
                        }
                    }
                }

                item(key = "activityHeader", contentType = "header") {
                    GroupHeader(stringResource(R.string.activity), Modifier.padding(top = ZapSpacing.space4))
                }
                when {
                    state.activity.isEmpty() && state.activityLoading -> item(key = "activityLoading", contentType = "loading") {
                        LoadingIndicator(Modifier.padding(vertical = ZapSpacing.space3))
                    }
                    state.activity.isEmpty() -> item(key = "noActivity", contentType = "empty") {
                        EmptyRow(stringResource(R.string.no_activity))
                    }
                    else -> {
                        itemsIndexed(state.activity, key = { _, item -> "activity:${item.eventId}" }, contentType = { _, _ -> "activity" }) { index, item ->
                            if (index > 0) HorizontalDivider(color = ZapLine, modifier = Modifier.padding(start = ACTIVITY_TEXT_INSET))
                            ActivityRow(item, state.profiles[item.pubkey], onClick = { onAppClick(item.app) })
                        }
                        if (state.activityCanLoadMore) {
                            item(key = "activityMore", contentType = "loading") {
                                LoadingIndicator(Modifier.padding(vertical = ZapSpacing.space4).testTag("activityLoadingMore"))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Submitted results above the rest of Home: filter chips, matches, or what to relax. */
private fun LazyListScope.searchResults(
    state: HomeUiState,
    results: List<AppRecord>,
    onAppClick: (AppRecord) -> Unit,
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
    if (results.isEmpty()) {
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
    item(key = "searchEnd", contentType = "gap") {
        HorizontalDivider(color = ZapLine, modifier = Modifier.padding(top = ZapSpacing.space2, bottom = ZapSpacing.space2))
    }
}

/** "Name commented on App" or "Name zapped App · N sats", the comment, and when. */
/**
 * The app leads (icon, name, time); the second line says who did what, with their picture;
 * a comment follows as the row's content.
 */
@Composable
private fun ActivityRow(item: ActivityItem, profile: ProfileRecord?, onClick: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = ZapSpacing.space3)
            .testTag("activity:${item.eventId}"),
    ) {
        AppIcon(title = item.app.name, iconUrl = item.app.iconUrl, iconFile = item.app.iconFile, size = ACTIVITY_ICON)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                Text(
                    item.app.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(
                        item.createdAt * 1000,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE,
                    ).toString(),
                    color = ZapTextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                AuthorByline(item.pubkey, profile, showBy = false, modifier = Modifier.weight(1f, fill = false))
                if (item.sats != null) {
                    Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, tint = ZapWarning, modifier = Modifier.size(14.dp))
                    Text(
                        pluralStringResource(R.plurals.activity_sats, item.sats.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), NumberFormat.getIntegerInstance().format(item.sats)),
                        color = ZapWarning,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                        maxLines = 1,
                    )
                } else {
                    Text(stringResource(R.string.activity_commented), color = ZapTextTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            item.comment?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = ZapTextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = ZapSpacing.space2),
                )
            }
        }
    }
}

private val ACTIVITY_ICON = 44.dp
private val ACTIVITY_TEXT_INSET = ACTIVITY_ICON + ZapSpacing.space3

/** Calls [onLoadMore] once the last visible item of a shelf is within a few of the end. */
@Composable
private fun LoadMoreNearEnd(position: () -> Pair<Int?, Int>, canLoadMore: Boolean, onLoadMore: () -> Unit) {
    // The item count rejoins the key so a page that lands still near the end asks for the next.
    val nearEndAt by remember {
        derivedStateOf {
            val (last, total) = position()
            if (last != null && total > 0 && last >= total - SHELF_PREFETCH) total else null
        }
    }
    LaunchedEffect(nearEndAt, canLoadMore) { if (nearEndAt != null && canLoadMore) onLoadMore() }
}

private const val SHELF_FRACTION = 0.88f
private const val SHELF_ROWS = 4
private const val SHELF_PREFETCH = 6
private val SHELF_HEIGHT = 64.dp * SHELF_ROWS + ZapSpacing.space1 * (SHELF_ROWS - 1)

@Composable
private fun FilterSheet(active: List<SearchFact>, onToggle: (SearchFact) -> Unit, onDismiss: () -> Unit) {
    ZapSheet(onDismiss = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
            modifier = Modifier.padding(start = ZapSpacing.space4, end = ZapSpacing.space4, bottom = ZapSpacing.space6),
        ) {
            Text(stringResource(R.string.filters), style = ZapTitle3Style)
            Text(stringResource(R.string.filters_hint), color = ZapTextSecondary, style = MaterialTheme.typography.bodyMedium)
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
