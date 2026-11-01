package dev.zapstore.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText2
import dev.zapstore.app.components.AppListState
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.LoadMoreWhenNearEnd
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.StackTile
import dev.zapstore.app.components.catalogCount
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.StackRecord

/** Everyone's public stacks, newest first. Each row names its curator: these are social, unlike the catalog's. */
@Composable
fun StacksScreen(
    state: StacksUiState,
    onStackClick: (StackRecord) -> Unit,
    modifier: Modifier = Modifier,
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
            state.stacks.isEmpty() && state.loading -> item(contentType = "loading") {
                LoadingIndicator(Modifier.padding(vertical = ZapSpacing.space4))
            }
            state.stacks.isEmpty() -> item(contentType = "empty") {
                EmptyRow(state.error ?: stringResource(R.string.no_stacks))
            }
            else -> items(state.stacks, key = { "${it.pubkey}:${it.identifier}" }, contentType = { "stack" }) { stack ->
                StackRow(stack, state, onClick = { onStackClick(stack) })
            }
        }
        if (state.loadingMore) {
            item(key = "more", contentType = "loading") { LoadingIndicator(Modifier.padding(vertical = ZapSpacing.space3)) }
        }
    }
}

@Composable
private fun StackRow(stack: StackRecord, state: StacksUiState, onClick: () -> Unit) {
    val curator = state.profiles[stack.pubkey]?.let(ProfileRecord::displayNameOrNpub)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZapRadius.md))
            .clickable(onClick = onClick)
            .padding(vertical = ZapSpacing.space2)
            .testTag("stack:${stack.identifier}"),
    ) {
        StackTile(stack.appIds, state.apps, size = 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stack.name.ifBlank { stack.identifier }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val resolved = stack.catalogCount(state.apps)
            val count = pluralStringResource(R.plurals.curated_apps, resolved, resolved)
            Text(
                if (curator != null) stringResource(R.string.stack_by, count, curator) else count,
                color = ZapText2,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (stack.description.isNotBlank()) {
                Text(stack.description, color = ZapText2, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
