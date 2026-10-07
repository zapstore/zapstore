package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.zapstore.app.R
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapDanger
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.app.catalog.AvailableUpdate
import dev.zapstore.app.catalog.InstalledApp
import dev.zapstore.app.components.AppCard
import dev.zapstore.app.components.AppIcon
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
    onUninstall: (AppRecord) -> Unit = {},
    onUninstallInstalled: (packageId: String, name: String) -> Unit = { _, _ -> },
    onUpdateAll: () -> Unit = {},
    onConfirmUpdateAll: () -> Unit = {},
    onDismissUpdateAll: () -> Unit = {},
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
        if (state.updateAll.ready > 0 || state.updateAll.running) {
            FilledTonalButton(
                onClick = onUpdateAll,
                enabled = !state.updateAll.running,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("updateAll"),
            ) {
                val batch = state.updateAll
                Text(
                    if (batch.running && batch.currentName != null) {
                        stringResource(R.string.update_all_progress, batch.done + 1, batch.total, batch.currentName)
                    } else {
                        stringResource(R.string.update_all)
                    },
                )
            }
        }

        val progressId = state.updateAll.currentId.takeIf { state.updateAll.running }
        val updates = state.updates.filter { it.app.appId != progressId }
        val manual = state.manualUpdates.filter { it.app.appId != progressId }
        val progress = (state.updates + state.manualUpdates).firstOrNull { it.app.appId == progressId }
        val empty = updates.isEmpty() && manual.isEmpty() && progress == null &&
            state.installedApps.isEmpty() && state.otherInstalled.isEmpty()

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (empty) {
                item {
                    if (!state.loaded || state.status.syncing) {
                        LoadingIndicator()
                    } else {
                        StatusText(stringResource(R.string.no_updates), Modifier.testTag("noUpdates"))
                    }
                }
            }
            if (progress != null) {
                item { SectionHeader(R.string.updates_section_progress, 1, tag = "sectionProgress") }
                item(key = "progress:${progress.app.appId}") {
                    UpdateRow(progress, state.updateAll, onAppClick, onUninstall)
                }
            }
            if (updates.isNotEmpty()) {
                item { SectionHeader(R.string.updates_section_updates, updates.size, tag = "sectionUpdates") }
                items(updates, key = { "update:${it.app.appId}" }) { update ->
                    UpdateRow(update, state.updateAll, onAppClick, onUninstall)
                }
            }
            if (manual.isNotEmpty()) {
                item {
                    SectionHeader(
                        R.string.updates_section_manual,
                        manual.size,
                        hint = R.string.updates_manual_hint,
                        tag = "sectionManual",
                    )
                }
                items(manual, key = { "manual:${it.app.appId}" }) { update ->
                    UpdateRow(update, state.updateAll, onAppClick, onUninstall)
                }
            }
            if (state.installedApps.isNotEmpty()) {
                item {
                    SectionHeader(R.string.updates_section_installed, state.installedApps.size, tag = "sectionInstalled")
                }
                items(state.installedApps, key = { "installed:${it.appId}" }) { app ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("installed:${app.appId}")) {
                        AppCard(app = app, author = null, onClick = { onAppClick(app) }, showAuthor = false)
                        UninstallButton(app.appId) { onUninstall(app) }
                    }
                }
            }
            if (state.otherInstalled.isNotEmpty()) {
                item {
                    SectionHeader(
                        R.string.updates_section_other,
                        state.otherInstalled.size,
                        hint = R.string.updates_other_hint,
                        tag = "sectionOther",
                    )
                }
                items(state.otherInstalled, key = { "other:${it.packageId}" }) { installed ->
                    OtherInstalledRow(installed) { onUninstallInstalled(installed.packageId, installed.label) }
                }
            }
        }
        if (state.updateAll.confirm) {
            UpdateAllDialog(
                ready = state.updateAll.ready,
                skipped = state.updateAll.skipped,
                onConfirm = onConfirmUpdateAll,
                onDismiss = onDismissUpdateAll,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: Int, count: Int, tag: String, hint: Int? = null) {
    Column(modifier = Modifier.testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = count.toString(),
                color = ZapTextSecondary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (hint != null) StatusText(stringResource(hint))
    }
}

@Composable
private fun UpdateRow(
    update: AvailableUpdate,
    batch: UpdateAllUi,
    onAppClick: (AppRecord) -> Unit,
    onUninstall: (AppRecord) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("update:${update.app.appId}")) {
        AppCard(app = update.app, author = null, onClick = { onAppClick(update.app) }, showAuthor = false)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusText(
                stringResource(R.string.update_version, update.installedVersion, update.app.version),
                Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
            UninstallButton(update.app.appId) { onUninstall(update.app) }
        }
        if (batch.running && batch.currentId == update.app.appId) {
            val readMb = batch.received / 1_000_000.0
            val total = batch.size
            StatusText(
                if (batch.received > 0 && total != null && total > 0) {
                    stringResource(R.string.download_progress_total, readMb, total / 1_000_000.0)
                } else if (batch.received > 0) {
                    stringResource(R.string.download_progress, readMb)
                } else {
                    stringResource(R.string.installing)
                },
                Modifier.padding(horizontal = 4.dp),
            )
        }
        batch.failures[update.app.appId]?.let { message ->
            StatusText(message, Modifier.padding(horizontal = 4.dp).testTag("updateFailed:${update.app.appId}"))
        }
    }
}

@Composable
private fun UninstallButton(packageId: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag("uninstall:$packageId")) {
        Text(stringResource(R.string.uninstall), color = ZapDanger)
    }
}

@Composable
private fun OtherInstalledRow(installed: InstalledApp, onUninstall: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZapRadius.lg))
            .background(ZapSurface1)
            .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.lg))
            .padding(ZapSpacing.space4)
            .testTag("other:${installed.packageId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(title = installed.label, iconUrl = null, size = 48.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(installed.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            StatusText(installed.packageId)
            StatusText(installed.versionName)
        }
        UninstallButton(installed.packageId, onUninstall)
    }
}

@Composable
private fun UpdateAllDialog(
    ready: Int,
    skipped: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ZapRadius.lg))
                .background(ZapSurface1)
                .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.lg))
                .padding(ZapSpacing.space4),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = pluralStringResource(R.plurals.update_all_title, ready, ready),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.update_all_body),
                color = ZapTextSecondary,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (skipped > 0) {
                Text(
                    text = pluralStringResource(R.plurals.update_all_skipped, skipped, skipped),
                    color = ZapTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.install_cancel), color = ZapTextSecondary)
                }
                TextButton(onClick = onConfirm, modifier = Modifier.testTag("updateAllConfirm")) {
                    Text(stringResource(R.string.update_app), color = ZapActionText)
                }
            }
        }
    }
}
