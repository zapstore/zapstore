package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import dev.zapstore.app.R
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.app.components.EvidenceText
import dev.zapstore.app.components.SectionLabel
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.transport.NetworkMode
import dev.zapstore.app.transport.TorRuntimeState

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onNetworkModeChange: (NetworkMode) -> Unit,
    onSync: () -> Unit,
    onWipe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZapCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(text = stringResource(R.string.settings), style = MaterialTheme.typography.displaySmall)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.network))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = state.networkMode == NetworkMode.TorOnly,
                    onClick = { onNetworkModeChange(NetworkMode.TorOnly) },
                    label = { Text(stringResource(R.string.network_tor)) },
                    modifier = Modifier.testTag("networkTor"),
                )
                FilterChip(
                    selected = state.networkMode == NetworkMode.DirectOnly,
                    onClick = { onNetworkModeChange(NetworkMode.DirectOnly) },
                    label = { Text(stringResource(R.string.network_direct)) },
                    modifier = Modifier.testTag("networkDirect"),
                )
                FilterChip(
                    selected = state.networkMode == NetworkMode.TorWithDirectFallback,
                    onClick = { onNetworkModeChange(NetworkMode.TorWithDirectFallback) },
                    label = { Text(stringResource(R.string.network_tor_fallback)) },
                    modifier = Modifier.testTag("networkTorFallback"),
                )
            }
            StatusText(
                networkStatusText(state.networkMode, state.torState),
                Modifier.testTag("networkStatus"),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.catalog))
            EvidenceText(state.catalogUrl, color = ZapTextSecondary)
            StatusText(stringResource(R.string.catalog_epoch, state.catalogEpoch))
            StatusText(stringResource(R.string.catalog_apps, state.appCount))
            state.status.lastSyncedAtMillis?.let { syncedAt ->
                StatusText(lastSyncRelativeText(syncedAt), Modifier.testTag("lastSyncAgo"))
            }
            if (state.status.lastSyncNotModified) {
                StatusText(
                    stringResource(R.string.last_sync_not_modified),
                    Modifier.testTag("lastSyncNotModified"),
                )
            } else {
                state.status.lastSyncBytes?.let { bytes ->
                    StatusText(
                        stringResource(R.string.last_sync_size, Formatter.formatFileSize(context, bytes)),
                        Modifier.testTag("lastSyncSize"),
                    )
                }
            }
            state.status.lastSyncDurationMillis?.let { durationMs ->
                StatusText(
                    stringResource(
                        R.string.last_sync_duration,
                        String.format(Locale.getDefault(), "%.2f", durationMs / 1000.0),
                    ),
                    Modifier.testTag("lastSyncDuration"),
                )
            }
            EvidenceText(
                stringResource(
                    R.string.local_storage,
                    Formatter.formatFileSize(context, state.localStorageBytes),
                ),
                color = ZapTextSecondary,
                modifier = Modifier.testTag("localStorage"),
            )
            state.status.error?.let { StatusText(it, Modifier.testTag("catalogError")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onSync,
                    enabled = !state.status.syncing,
                    modifier = Modifier.testTag("syncCatalog"),
                ) {
                    Text(stringResource(if (state.status.syncing) R.string.syncing else R.string.sync_now))
                }
                OutlinedButton(
                    onClick = onWipe,
                    enabled = !state.status.syncing,
                    modifier = Modifier.testTag("wipeLocalDb"),
                ) {
                    Text(stringResource(R.string.wipe_local_db))
                }
            }
        }
    }
}

@Composable
private fun lastSyncRelativeText(syncedAtMillis: Long): String {
    var now by remember(syncedAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(syncedAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val elapsed = (now - syncedAtMillis).coerceAtLeast(0)
    val minutes = (elapsed / 60_000L).toInt()
    val hours = (elapsed / 3_600_000L).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.last_sync_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.last_sync_minutes_ago, minutes, minutes)
        hours < 48 -> pluralStringResource(R.plurals.last_sync_hours_ago, hours, hours)
        else -> stringResource(
            R.string.last_synced,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(syncedAtMillis)),
        )
    }
}

@Composable
private fun networkStatusText(mode: NetworkMode, torState: TorRuntimeState): String = when (mode) {
    NetworkMode.DirectOnly -> stringResource(R.string.network_direct_warning)
    NetworkMode.TorOnly -> when (torState) {
        TorRuntimeState.Bootstrapping -> stringResource(R.string.tor_bootstrapping)
        TorRuntimeState.Ready -> stringResource(R.string.tor_ready)
        TorRuntimeState.Dormant -> stringResource(R.string.tor_dormant)
        TorRuntimeState.Failed -> stringResource(R.string.tor_failed)
        TorRuntimeState.Stopped -> stringResource(R.string.tor_stopped)
    }
    NetworkMode.TorWithDirectFallback -> when (torState) {
        TorRuntimeState.Bootstrapping -> stringResource(R.string.tor_bootstrapping)
        TorRuntimeState.Ready -> stringResource(R.string.tor_ready)
        TorRuntimeState.Dormant -> stringResource(R.string.tor_dormant)
        TorRuntimeState.Failed, TorRuntimeState.Stopped -> stringResource(R.string.tor_failed_fallback)
    }
}
