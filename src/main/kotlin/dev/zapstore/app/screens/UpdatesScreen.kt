package dev.zapstore.app

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
import dev.zapstore.app.catalogsync.CatalogSyncUiState
import java.text.DateFormat
import java.util.Date

@Composable
fun UpdatesScreen(
    state: CatalogSyncUiState,
    onSync: () -> Unit,
    onAppClick: (identifier: String, author: String?) -> Unit = { _, _ -> },
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
        Text(
            text = stringResource(R.string.updates),
            style = MaterialTheme.typography.displaySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                StatusText(stringResource(R.string.catalog_epoch, state.epoch))
                state.lastSyncedAtMillis?.let { synced ->
                    StatusText(
                        stringResource(
                            R.string.last_synced,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(synced)),
                        ),
                    )
                }
            }
            FilledTonalButton(
                onClick = onSync,
                enabled = !state.syncing,
                modifier = Modifier.testTag("syncCatalog"),
            ) {
                Text(stringResource(if (state.syncing) R.string.syncing else R.string.sync_now))
            }
        }
        state.error?.let { StatusText(it, Modifier.testTag("updatesError")) }
        if (state.syncing && state.availableUpdates.isEmpty()) {
            LoadingIndicator()
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (state.availableUpdates.isEmpty() && !state.syncing) {
                item {
                    StatusText(
                        stringResource(R.string.no_updates),
                        Modifier.testTag("noUpdates"),
                    )
                }
            }
            items(state.availableUpdates, key = { it.appId }) { update ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("update:${update.appId}"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(update.name, style = MaterialTheme.typography.titleMedium)
                    EvidenceText(update.appId, color = ZapTextSecondary)
                    StatusText(
                        stringResource(
                            R.string.update_version,
                            update.installedVersion,
                            update.availableVersion,
                        ),
                    )
                }
            }
        }
    }
}
