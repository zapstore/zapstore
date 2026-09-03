package dev.zapstore.app

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

fun LazyListScope.releaseFeed(
    state: ReleaseFeedUiState,
    emptyMessage: String,
    repository: CatalogRepository?,
    onAppClick: (identifier: String, author: String) -> Unit,
    onProfileClick: (String) -> Unit = {},
    modifier: Modifier,
    title: String? = null,
    showAuthor: Boolean = true,
) {
    title?.let {
        item {
            SectionTitle(
                value = it,
                modifier = modifier.padding(top = 4.dp),
            )
        }
    }
    when {
        state.entries.isEmpty() && state.initialLoading -> item {
            LoadingIndicator(modifier.padding(vertical = 12.dp))
        }

        state.entries.isEmpty() -> item {
            StatusText(state.error ?: emptyMessage, modifier)
        }

        else -> {
            state.error?.let { error -> item { StatusText(error, modifier) } }
            items(state.entries, key = { "app:${it.app.event.id}" }) { entry ->
                val app = entry.app
                AppCard(
                    app = app,
                    release = entry.release,
                    onClick = { onAppClick(app.identifier, app.event.pubKey) },
                    onProfileClick = { onProfileClick(app.event.pubKey) },
                    repository = repository,
                    modifier = modifier.testTag("app:${app.event.id}"),
                    showAuthor = showAuthor,
                )
            }
            if (state.loadingMore) {
                item(key = "release-loading") {
                    LoadingIndicator(
                        modifier
                            .testTag("releaseLoading")
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun LoadMoreReleasesWhenNearEnd(
    listState: LazyListState,
    entries: List<ReleaseFeedEntry>,
    loading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
) {
    val prefetchKeys = remember(entries) {
        entries.takeLast(RELEASE_PREFETCH_THRESHOLD)
            .mapTo(mutableSetOf()) { "app:${it.app.event.id}" }
    }
    val nearEnd by remember(listState, prefetchKeys) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.any { it.key in prefetchKeys }
        }
    }
    LaunchedEffect(nearEnd, loading, canLoadMore) {
        if (nearEnd && !loading && canLoadMore) onLoadMore()
    }
}

private const val RELEASE_PREFETCH_THRESHOLD = 3

