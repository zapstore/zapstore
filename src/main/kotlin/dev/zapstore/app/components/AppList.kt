package dev.zapstore.app.components

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
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.ProfileRecord

/** A page of catalog listings plus what the list needs to render loading and end states. */
data class AppListState(
    val apps: List<AppRecord> = emptyList(),
    val profiles: Map<String, ProfileRecord> = emptyMap(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: String? = null,
)

/** App cards with a title, empty state, and load-more footer. */
fun LazyListScope.appList(
    state: AppListState,
    emptyMessage: String,
    onAppClick: (AppRecord) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    showAuthor: Boolean = true,
    onAuthorClick: ((String) -> Unit)? = null,
) {
    title?.let { item { SectionTitle(it, modifier.padding(top = 4.dp)) } }
    when {
        state.apps.isEmpty() && state.loading -> item { LoadingIndicator(modifier.padding(vertical = 12.dp)) }
        state.apps.isEmpty() -> item { StatusText(state.error ?: emptyMessage, modifier) }
        else -> {
            state.error?.let { item { StatusText(it, modifier) } }
            items(state.apps, key = { "app:${it.appId}" }) { app ->
                AppCard(
                    app = app,
                    author = app.authorPubkey?.let(state.profiles::get),
                    onClick = { onAppClick(app) },
                    onAuthorClick = onAuthorClick,
                    showAuthor = showAuthor,
                    modifier = modifier.testTag("app:${app.appId}"),
                )
            }
            if (state.loadingMore) {
                item(key = "app-list-loading") {
                    LoadingIndicator(modifier.testTag("appListLoading").padding(vertical = 8.dp))
                }
            }
        }
    }
}

/** Calls [onLoadMore] when the list has a real viewport and the last few items are on screen. */
@Composable
fun LoadMoreWhenNearEnd(
    listState: LazyListState,
    state: AppListState,
    onLoadMore: () -> Unit,
) {
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.viewportEndOffset <= info.viewportStartOffset) return@derivedStateOf false
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= info.totalItemsCount - PREFETCH_THRESHOLD
        }
    }
    LaunchedEffect(nearEnd, state.loadingMore, state.canLoadMore) {
        if (nearEnd && !state.loadingMore && state.canLoadMore) onLoadMore()
    }
}

private const val PREFETCH_THRESHOLD = 3
