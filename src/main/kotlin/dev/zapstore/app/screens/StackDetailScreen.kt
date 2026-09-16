package dev.zapstore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun StackDetailScreen(
    state: StackDetailUiState,
    repository: CatalogRepository? = null,
    onAppClick: (identifier: String, author: String) -> Unit,
    onProfileClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ZapCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val stack = state.stack
        if (stack == null) {
            item {
                if (state.stackLoading) {
                    LoadingIndicator(Modifier.padding(vertical = 20.dp))
                } else {
                    Text(
                        text = stringResource(R.string.stack_not_found),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            state.error?.let { error -> item { StatusText(error) } }
            return@LazyColumn
        }

        item {
            StackCard(
                stack = stack,
                appsByAddress = state.appsByAddress,
                modifier = Modifier.testTag("stack:${stack.event.id}"),
            )
        }
        item {
            SectionTitle(
                value = stringResource(R.string.apps),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        items(
            items = stack.appAddresses.mapNotNull(state.appsByAddress::get),
            key = { it.address },
        ) { app ->
            AppCard(
                app = app,
                release = state.releasesByAddress[app.address],
                onClick = { onAppClick(app.identifier, app.event.pubKey) },
                onProfileClick = { onProfileClick(app.event.pubKey) },
                repository = repository,
                modifier = Modifier.testTag("stackApp:${app.address}"),
            )
        }
        if (state.appsByAddress.isEmpty()) {
            item {
                if (state.appsLoading) {
                    LoadingIndicator(Modifier.padding(vertical = 12.dp))
                } else {
                    StatusText(stringResource(R.string.no_stack_apps))
                }
            }
        }
        state.error?.let { error -> item { StatusText(error) } }
    }
}

