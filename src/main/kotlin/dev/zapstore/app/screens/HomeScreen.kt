package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapTextTertiary
import dev.zapstore.app.components.AppCard
import dev.zapstore.app.components.LoadMoreWhenNearEnd
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.StackCard
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.ZapSearchField
import dev.zapstore.app.facts.label
import dev.zapstore.app.components.appList
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.StackRecord

@Composable
fun HomeScreen(
    state: HomeUiState,
    onSearchQueryChanged: (String) -> Unit,
    onSearchSubmitted: () -> Unit,
    onSearchCleared: () -> Unit,
    onAppClick: (AppRecord) -> Unit,
    onStackClick: (StackRecord) -> Unit,
    modifier: Modifier = Modifier,
    onProfileClick: (String) -> Unit = {},
    onUpdatesClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    updateCount: Int = 0,
    onLoadMore: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val searchFocusRequester = remember { FocusRequester() }
    val latestReleases = stringResource(R.string.latest_releases)
    val noReleases = stringResource(R.string.no_releases)
    LoadMoreWhenNearEnd(listState, state.feed, onLoadMore)
    LaunchedEffect(state.submittedQuery) {
        listState.scrollToItem(0)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZapCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                    if (state.searchQuery.isNotEmpty()) {
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
                    .weight(1f)
                    .focusRequester(searchFocusRequester)
                    .testTag("searchField"),
            )
            Box(contentAlignment = Alignment.TopEnd) {
                IconButton(onClick = onUpdatesClick, modifier = Modifier.testTag("updatesButton")) {
                    Icon(
                        painter = painterResource(R.drawable.ic_update),
                        contentDescription = stringResource(R.string.updates),
                        modifier = Modifier.size(24.dp),
                    )
                }
                if (updateCount > 0) {
                    Text(
                        text = updateCount.toString(),
                        color = MaterialTheme.colorScheme.onError,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .padding(top = 4.dp, end = 2.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                            .testTag("updatesBadge"),
                    )
                }
            }
            IconButton(onClick = onSettingsClick, modifier = Modifier.testTag("settingsButton")) {
                Icon(
                    painter = painterResource(R.drawable.ic_more),
                    contentDescription = stringResource(R.string.settings),
                    tint = ZapTextTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .testTag("homeList"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            state = listState,
        ) {
            state.searchResults?.let { results ->
                if (state.searchFacts.isNotEmpty()) {
                    item {
                        StatusText(
                            value = state.searchFacts.joinToString(" · ") { it.label() },
                            modifier = Modifier.testTag("searchFacts"),
                        )
                    }
                }
                item {
                    StatusText(
                        value = stringResource(R.string.search_results, results.size, state.searchDurationMillis ?: 0L),
                        modifier = Modifier.testTag("searchStatus"),
                    )
                }
                items(results, key = { "search:${it.appId}" }) { app ->
                    AppCard(
                        app = app,
                        author = app.authorPubkey?.let(state.profiles::get),
                        onClick = { onAppClick(app) },
                        onAuthorClick = onProfileClick,
                        modifier = Modifier.testTag("searchResult:${app.appId}"),
                    )
                }
            }

            if (state.stacks.isNotEmpty() || state.stacksLoading || state.stacksError != null) {
                item {
                    when {
                        state.stacks.isNotEmpty() -> LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(end = 8.dp),
                        ) {
                            items(state.stacks, key = { it.eventId }) { stack ->
                                StackCard(
                                    stack = stack,
                                    apps = state.stackApps,
                                    onClick = { onStackClick(stack) },
                                    modifier = Modifier.testTag("stack:${stack.identifier}"),
                                )
                            }
                        }
                        state.stacksLoading -> LoadingIndicator(Modifier.padding(vertical = 12.dp))
                        state.stacksError != null -> StatusText(state.stacksError)
                    }
                }
            }

            appList(
                state = state.feed,
                title = latestReleases,
                emptyMessage = noReleases,
                onAppClick = onAppClick,
                onAuthorClick = onProfileClick,
            )
        }
    }
}
