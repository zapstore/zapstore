package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.components.AppCard
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.StatusText
import dev.zapstore.iolite.AppRecord
import java.text.DateFormat
import java.util.Date

@Composable
fun UpdatesScreen(
    state: UpdatesUiState,
    onSync: () -> Unit,
    onAppClick: (AppRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZapCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.updates), style = MaterialTheme.typography.displaySmall)
                state.status.lastSyncedAtMillis?.let { synced ->
                    StatusText(
                        stringResource(
                            R.string.last_synced,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(synced)),
                        ),
                    )
                }
            }
            FilledTonalButton(
                onClick = onSync,
                enabled = !state.status.syncing,
                modifier = Modifier.testTag("syncCatalog"),
            ) {
                Text(stringResource(if (state.status.syncing) R.string.syncing else R.string.sync_now))
            }
        }
        state.status.error?.let { StatusText(it, Modifier.testTag("updatesError")) }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (state.updates.isEmpty()) {
                item {
                    if (!state.loaded || state.status.syncing) {
                        LoadingIndicator()
                    } else {
                        StatusText(stringResource(R.string.no_updates), Modifier.testTag("noUpdates"))
                    }
                }
            }
            items(state.updates, key = { it.app.appId }) { update ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("update:${update.app.appId}")) {
                    AppCard(app = update.app, author = null, onClick = { onAppClick(update.app) }, showAuthor = false)
                    StatusText(
                        stringResource(R.string.update_version, update.installedVersion, update.app.version),
                        Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}
