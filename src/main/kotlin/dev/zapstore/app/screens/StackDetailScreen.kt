package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.StackCard
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.appList
import dev.zapstore.iolite.AppRecord

@Composable
fun StackDetailScreen(
    state: StackDetailUiState,
    onAppClick: (AppRecord) -> Unit,
    modifier: Modifier = Modifier,
    onProfileClick: (String) -> Unit = {},
) {
    val appsTitle = stringResource(R.string.apps)
    val noApps = stringResource(R.string.no_stack_apps)
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
                    Text(text = stringResource(R.string.stack_not_found), style = MaterialTheme.typography.headlineSmall)
                }
            }
            state.error?.let { item { StatusText(it) } }
            return@LazyColumn
        }

        item { StackCard(stack = stack, apps = state.appsById, modifier = Modifier.testTag("stack:${stack.identifier}")) }
        appList(
            state = state.apps,
            title = appsTitle,
            emptyMessage = noApps,
            onAppClick = onAppClick,
            onAuthorClick = onProfileClick,
        )
        state.error?.let { item { StatusText(it) } }
    }
}
