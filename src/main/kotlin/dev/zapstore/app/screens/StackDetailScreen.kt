package dev.zapstore.app.screens

import dev.zapstore.app.ZapSize
import dev.zapstore.app.components.ZapTextLink
import dev.zapstore.app.components.ZapIconButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.ZapSpacing
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
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
import dev.zapstore.app.components.StackHead
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.appList
import dev.zapstore.iolite.AppRecord

@Composable
fun StackDetailScreen(
    state: StackDetailUiState,
    onAppClick: (AppRecord) -> Unit,
    modifier: Modifier = Modifier,
    onProfileClick: (String) -> Unit = {},
    onRemoveApp: (String) -> Unit = {},
    onRename: (String) -> Unit = {},
) {
    val appsTitle = stringResource(R.string.apps)
    val noApps = stringResource(R.string.no_stack_apps)
    val removeLabel = stringResource(R.string.remove_from_stack)
    var renaming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.edit.renamed) { renaming = false }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ZapCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 36.dp),
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

        item { StackHead(stack = stack, apps = state.appsById, modifier = Modifier.testTag("stack:${stack.identifier}")) }
        if (state.own) {
            item(key = "rename") {
                if (renaming) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                        ZapTextArea(
                            value = name,
                            onValueChange = { name = it.take(MAX_STACK_NAME_LENGTH) },
                            placeholder = stringResource(R.string.stack_name),
                            maxLines = 1,
                            modifier = Modifier.weight(1f).testTag("stackName"),
                        )
                        ZapButton(
                            text = stringResource(R.string.save),
                            onClick = { onRename(name) },
                            enabled = name.isNotBlank() && state.edit.busy == null,
                            loading = state.edit.busy == StackEditUi.RENAME,
                            modifier = Modifier.height(ZapSize.control).testTag("saveStackName"),
                        )
                    }
                } else {
                    ZapTextLink(
                        stringResource(R.string.rename),
                        {
                            name = stack.name
                            renaming = true
                        },
                        Modifier.testTag("renameStack"),
                    )
                }
            }
        }
        state.edit.error?.let { item { StatusText(it, Modifier.testTag("stackEditError")) } }
        appList(
            state = state.apps,
            title = appsTitle,
            emptyMessage = noApps,
            onAppClick = onAppClick,
            trailing = if (state.own) {
                { app ->
                    if (state.edit.busy == app.appId) {
                        LoadingIndicator(Modifier.size(20.dp))
                    } else {
                        ZapIconButton(
                            icon = R.drawable.ic_close,
                            contentDescription = removeLabel,
                            onClick = { onRemoveApp(app.appId) },
                            style = ZapButtonStyle.Ghost,
                            modifier = Modifier.testTag("removeFromStack:${app.appId}"),
                        )
                    }
                }
            } else {
                null
            },
        )
        state.error?.let { item { StatusText(it) } }
    }
}

private const val MAX_STACK_NAME_LENGTH = 64
