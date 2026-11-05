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

/** App rows with a group header, empty state, and load-more footer. */
fun LazyListScope.appList(
    state: AppListState,
    emptyMessage: String,
    onAppClick: (AppRecord) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    showAuthor: Boolean = true,
    trailing: (@Composable (AppRecord) -> Unit)? = null,
) {
    title?.let { item(contentType = "header") { GroupHeader(it, modifier.padding(top = 8.dp)) } }
    when {
        state.apps.isEmpty() && state.loading -> Unit
        state.apps.isEmpty() -> item { StatusText(state.error ?: emptyMessage, modifier) }
        else -> {
            state.error?.let { item { StatusText(it, modifier) } }
            // A stack can name one app twice, by two coordinates; a lazy key may appear once.
            items(state.apps.distinctBy(AppRecord::appId), key = { "app:${it.appId}" }, contentType = { "app" }) { app ->
                AppRow(
                    app = app,
                    onClick = { onAppClick(app) },
                    author = app.authorPubkey?.let(state.profiles::get),
                    showAuthor = showAuthor,
                    modifier = modifier.then(listItemMotion()).testTag("app:${app.appId}"),
                    trailing = trailing?.let { content -> { content(app) } },
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
