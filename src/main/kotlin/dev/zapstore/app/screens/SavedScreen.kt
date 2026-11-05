package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.zapstore.app.R
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.components.AppRow
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.StackFace
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapIconButton
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.Iolite
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** The device's saved apps, a private stack: ids in saved order and the catalog listings among them. */
data class SavedApps(val ids: List<String> = emptyList(), val apps: Map<String, AppRecord> = emptyMap()) {
    val listed: List<AppRecord> get() = ids.mapNotNull(apps::get)
}

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun rememberSavedApps(iolite: Iolite, saved: StateFlow<List<String>>): State<SavedApps> =
    remember(iolite, saved) {
        saved.flatMapLatest { ids -> iolite.observeAppsById(ids).map { SavedApps(ids, it) } }
    }.collectAsState(SavedApps(saved.value))

/** Saved, the same card as a stack, pinned first on the Profile shelf. */
@Composable
internal fun SavedStackCard(saved: SavedApps, onClick: () -> Unit, modifier: Modifier = Modifier, flushStart: Boolean = false) {
    StackFace(
        name = stringResource(R.string.saved),
        appIds = saved.ids,
        apps = saved.apps,
        onClick = onClick,
        flushStart = flushStart,
        modifier = modifier.testTag("savedStack"),
    )
}

/** The saved apps as a list, each with a button that unsaves it. */
@Composable
fun SavedScreen(
    saved: SavedApps,
    onAppClick: (AppRecord) -> Unit,
    onUnsave: (AppRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(ZapCanvas).testTag("savedList"),
        contentPadding = PaddingValues(start = ZapSpacing.space4, end = ZapSpacing.space4, top = ZapSpacing.space2, bottom = ZapSpacing.space8),
    ) {
        item(key = "title") {
            Text(stringResource(R.string.saved), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = ZapSpacing.space2))
        }
        val listed = saved.listed
        if (listed.isEmpty()) {
            item(key = "empty") { EmptyRow(stringResource(R.string.saved_empty)) }
        }
        items(listed, key = { it.appId }) { app ->
            AppRow(
                app = app,
                onClick = { onAppClick(app) },
                modifier = Modifier.testTag("saved:${app.appId}"),
                trailing = {
                    ZapIconButton(
                        icon = R.drawable.ic_close,
                        contentDescription = stringResource(R.string.unsave),
                        onClick = { onUnsave(app) },
                        style = ZapButtonStyle.Ghost,
                        modifier = Modifier.testTag("unsave:${app.appId}"),
                    )
                },
            )
        }
    }
}
