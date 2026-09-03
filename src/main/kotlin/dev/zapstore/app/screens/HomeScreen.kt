package dev.zapstore.app

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
fun HomeScreen(
    state: HomeUiState,
    repository: CatalogRepository? = null,
    onSearchQueryChanged: (String) -> Unit,
    onSearchSubmitted: () -> Unit,
    onSearchCleared: () -> Unit,
    onNotificationsClick: () -> Unit = {},
    notificationCount: Int = 0,
    onStackClick: (String) -> Unit,
    onAppClick: (identifier: String, author: String) -> Unit,
    onProfileClick: (String) -> Unit = {},
    onLoadMoreReleases: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val searchFocusRequester = remember { FocusRequester() }
    val latestReleases = stringResource(R.string.latest_releases)
    val noReleases = stringResource(R.string.no_releases)
    LoadMoreReleasesWhenNearEnd(
        listState = listState,
        entries = state.releaseFeed.entries,
        loading = state.releaseFeed.loadingMore,
        canLoadMore = state.releaseFeed.canLoadMore,
        onLoadMore = onLoadMoreReleases,
    )
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
                                .semantics { contentDescription = clearDescription },
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
                IconButton(
                    onClick = onNotificationsClick,
                    modifier = Modifier.testTag("notificationsButton"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = stringResource(R.string.notifications),
                    )
                }
                Text(
                    text = notificationCount.toString(),
                    color = MaterialTheme.colorScheme.onError,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .padding(top = 4.dp, end = 2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .testTag("notificationBadge"),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            state = listState,
        ) {
            state.searchMessage?.let { message ->
                item { StatusText(message, Modifier.testTag("searchStatus")) }
            }
            items(state.searchResults, key = { it.address }) { app ->
                AppCard(
                    app = app,
                    onClick = { onAppClick(app.identifier, app.event.pubKey) },
                    onProfileClick = { onProfileClick(app.event.pubKey) },
                    repository = repository,
                    modifier = Modifier.testTag("searchResult:${app.address}"),
                )
            }

            item {
                SectionTitle(
                    value = stringResource(R.string.curated_stacks),
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            item {
                when {
                    state.stacks.isNotEmpty() -> {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(end = 8.dp),
                        ) {
                            items(state.stacks, key = { it.event.id }) { stack ->
                                StackCard(
                                    stack = stack,
                                    appsByAddress = state.stackApps,
                                    onClick = { onStackClick(stack.event.id) },
                                    modifier = Modifier.testTag("stack:${stack.event.id}"),
                                )
                            }
                        }
                    }

                    state.stacksLoading -> LoadingIndicator(Modifier.padding(vertical = 12.dp))
                    state.stacksError != null -> StatusText(state.stacksError)
                    else -> StatusText(stringResource(R.string.no_stacks))
                }
            }

            releaseFeed(
                state = state.releaseFeed,
                title = latestReleases,
                emptyMessage = noReleases,
                repository = repository,
                onAppClick = onAppClick,
                onProfileClick = onProfileClick,
                modifier = Modifier,
            )
        }
    }
}

