package dev.zapstore.app.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.zapstore.app.R
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.components.AppListState
import dev.zapstore.app.components.AuthorByline
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.LoadMoreWhenNearEnd
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.StackCard
import dev.zapstore.app.components.catalogCount
import dev.zapstore.iolite.StackRecord

/** Everyone's public stacks, newest first. Each row names its curator: these are social, unlike the catalog's. */
@Composable
fun StacksScreen(
    state: StacksUiState,
    onStackClick: (StackRecord) -> Unit,
    modifier: Modifier = Modifier,
    onProfileClick: (String) -> Unit = {},
    onLoadMore: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    LoadMoreWhenNearEnd(
        listState,
        AppListState(loading = state.loading, loadingMore = state.loadingMore, canLoadMore = state.canLoadMore),
        onLoadMore,
    )
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag("stacksList"),
        contentPadding = PaddingValues(start = ZapSpacing.space4, end = ZapSpacing.space4, top = ZapSpacing.space2, bottom = ZapSpacing.space8),
    ) {
        item(key = "title", contentType = "title") {
            Text(
                stringResource(R.string.stacks),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = ZapSpacing.space3),
            )
        }
        when {
            state.stacks.isEmpty() && state.loading -> Unit
            state.stacks.isEmpty() -> item(contentType = "empty") {
                EmptyRow(state.error ?: stringResource(R.string.no_stacks))
            }
            else -> items(state.stacks, key = { "${it.pubkey}:${it.identifier}" }, contentType = { "stack" }) { stack ->
                StackCard(
                    stack = stack,
                    apps = state.apps,
                    onClick = { onStackClick(stack) },
                    count = stack.catalogCount(state.apps),
                    underName = {
                        AuthorByline(
                            pubkey = stack.pubkey,
                            profile = state.profiles[stack.pubkey],
                            showBy = false,
                            onClick = { onProfileClick(stack.pubkey) },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("stack:${stack.identifier}"),
                )
            }
        }
        if (state.loadingMore) {
            item(key = "more", contentType = "loading") { LoadingIndicator(Modifier.padding(vertical = ZapSpacing.space3)) }
        }
    }
}
