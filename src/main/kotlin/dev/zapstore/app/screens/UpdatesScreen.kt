package dev.zapstore.app.screens

import dev.zapstore.app.ZapSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.IconButton
import dev.zapstore.app.ZapVerified
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.components.ZapSegmented
import dev.zapstore.app.ZapDanger
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3
import dev.zapstore.app.catalog.AvailableUpdate
import dev.zapstore.app.catalog.InstalledApp
import dev.zapstore.app.components.AppIcon
import dev.zapstore.app.components.AppRow
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.GroupHeader
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.ZapSearchField
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.ProfileRecord
import java.text.DateFormat
import java.util.Date

/** New lists pending updates; Installed lists everything else on the device. */
enum class MyAppsTab { New, Installed }

/**
 * My apps in two tabs. New: silent updates, manual updates (Android asks), updates another
 * store owns, and ignored apps. Installed: what Zapstore keeps current, what another store keeps
 * current, and packages the catalog lacks. A submitted search replaces both.
 */
@Composable
fun UpdatesScreen(
    state: UpdatesUiState,
    onSync: () -> Unit,
    onAppClick: (AppRecord) -> Unit,
    modifier: Modifier = Modifier,
    onUninstall: (AppRecord) -> Unit = {},
    onUninstallInstalled: (packageId: String, name: String) -> Unit = { _, _ -> },
    onUpdateAll: (UpdateGroup) -> Unit = {},
    onUpdateOne: (AvailableUpdate) -> Unit = {},
    onConfirmUpdateAll: () -> Unit = {},
    onDismissUpdateAll: () -> Unit = {},
    onResumeUpdates: (String) -> Unit = {},
    onSearchQueryChanged: (String) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(MyAppsTab.New) }
    val progressId = state.updateAll.currentId.takeIf { state.updateAll.running }
    val updates = state.updates.filter { it.app.appId != progressId }
    val manual = state.manualUpdates.filter { it.app.appId != progressId }
    val progress = (state.updates + state.manualUpdates).firstOrNull { it.app.appId == progressId }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ZapSpacing.space4, end = ZapSpacing.space2, top = ZapSpacing.space2, bottom = ZapSpacing.space2),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.my_apps), style = MaterialTheme.typography.headlineSmall)
                StatusText(
                    when {
                        state.status.syncing -> stringResource(R.string.syncing)
                        state.status.lastSyncedAtMillis != null -> stringResource(
                            R.string.last_synced,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(state.status.lastSyncedAtMillis)),
                        )
                        else -> ""
                    },
                )
            }
            ZapButton(
                text = stringResource(R.string.sync_now),
                onClick = onSync,
                style = ZapButtonStyle.Primary,
                loading = state.status.syncing,
                modifier = Modifier.testTag("syncCatalog"),
            )
        }
        state.status.error?.let {
            StatusText(it, Modifier.padding(horizontal = ZapSpacing.space4).testTag("updatesError"))
        }
        ZapSearchField(
            value = query,
            onValueChange = {
                query = it
                if (it.isEmpty()) onSearchQueryChanged("")
            },
            onSearch = { onSearchQueryChanged(query) },
            placeholder = stringResource(R.string.search_my_apps),
            trailingIcon = {
                if (query.isNotEmpty() || state.searchResults != null) {
                    val clearDescription = stringResource(R.string.clear_search)
                    IconButton(
                        onClick = {
                            query = ""
                            onSearchQueryChanged("")
                        },
                        modifier = Modifier
                            .size(ZapSize.control)
                            .semantics { contentDescription = clearDescription }
                            .testTag("clearMyAppsSearch"),
                    ) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = ZapText3, modifier = Modifier.size(16.dp))
                    }
                }
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = ZapText3,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZapSpacing.space4, vertical = ZapSpacing.space2)
                .testTag("myAppsSearch"),
        )
        if (state.searchResults == null) {
            ZapSegmented(
                options = MyAppsTab.entries,
                selected = tab,
                onSelect = { tab = it },
                label = { stringResource(if (it == MyAppsTab.New) R.string.tab_new else R.string.tab_installed) },
                count = { if (it == MyAppsTab.New) state.newCount else state.installedCount },
                tag = { "myAppsTab:${it.name}" },
                modifier = Modifier.padding(horizontal = ZapSpacing.space4, vertical = ZapSpacing.space1),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).testTag("myAppsList"),
            contentPadding = PaddingValues(start = ZapSpacing.space4, end = ZapSpacing.space4, bottom = ZapSpacing.space8),
        ) {
            if (!state.loaded) {
                item(contentType = "loading") { LoadingIndicator(Modifier.padding(vertical = ZapSpacing.space6)) }
                return@LazyColumn
            }
            state.searchResults?.let { results ->
                searchSection(state, results, onAppClick, onUninstallInstalled)
                return@LazyColumn
            }
            when (tab) {
                MyAppsTab.New -> {
                    updatesSection(state, updates, manual, progress, onAppClick, onUpdateAll, onUpdateOne)
                    ignoredSection(state, onAppClick, onResumeUpdates)
                }
                MyAppsTab.Installed -> {
                    installedSection(state, onAppClick)
                    otherStoresSection(state, onAppClick)
                    notInCatalogSection(state, onUninstallInstalled)
                }
            }
        }
    }
    if (state.updateAll.confirm) {
        UpdateAllDialog(
            ready = state.updateAll.ready,
            group = state.updateAll.group,
            onConfirm = onConfirmUpdateAll,
            onDismiss = onDismissUpdateAll,
        )
    }
}

private fun LazyListScope.updatesSection(
    state: UpdatesUiState,
    updates: List<AvailableUpdate>,
    manual: List<AvailableUpdate>,
    progress: AvailableUpdate?,
    onAppClick: (AppRecord) -> Unit,
    onUpdateAll: (UpdateGroup) -> Unit,
    onUpdateOne: (AvailableUpdate) -> Unit,
) {
    val storeUpdates = state.otherStores.filter { it.entry.update != null }
    if (progress != null) {
        item(key = "sectionProgress", contentType = "header") {
            GroupHeader(stringResource(R.string.updates_section_progress), Modifier.testTag("sectionProgress"))
        }
        item(key = "progress:${progress.app.appId}", contentType = "update") {
            UpdateRow(progress, state.updateAll, onAppClick, onUpdateOne)
        }
    }
    if (updates.isNotEmpty() || (manual.isEmpty() && storeUpdates.isEmpty() && progress == null)) {
        item(key = "sectionUpdates", contentType = "header") {
            GroupHeader(
                stringResource(R.string.updates_section_updates),
                Modifier.testTag("sectionUpdates"),
                action = stringResource(R.string.update_all).takeIf { !state.updateAll.running && updates.isNotEmpty() },
                onAction = { onUpdateAll(UpdateGroup.Automatic) },
                actionTag = "updateAll",
            )
        }
    }
    if (updates.isEmpty() && manual.isEmpty() && storeUpdates.isEmpty() && progress == null) {
        item(key = "noUpdates", contentType = "empty") {
            if (state.status.syncing) {
                EmptyRow(stringResource(R.string.syncing), modifier = Modifier.testTag("noUpdates"))
            } else {
                EmptyRow(
                    stringResource(R.string.all_up_to_date),
                    icon = R.drawable.ic_check,
                    iconTint = ZapVerified,
                    modifier = Modifier.testTag("noUpdates"),
                )
            }
        }
        return
    }
    items(updates, key = { "update:${it.app.appId}" }, contentType = { "update" }) { update ->
        UpdateRow(update, state.updateAll, onAppClick, onUpdateOne)
    }
    if (manual.isNotEmpty() || storeUpdates.isNotEmpty()) {
        item(key = "sectionManual", contentType = "header") {
            Column(Modifier.testTag("sectionManual")) {
                GroupHeader(
                    stringResource(R.string.updates_section_manual),
                    action = stringResource(R.string.update_all).takeIf { !state.updateAll.running && manual.isNotEmpty() },
                    onAction = { onUpdateAll(UpdateGroup.Manual) },
                    actionTag = "updateAllManual",
                )
                StatusText(stringResource(R.string.updates_manual_hint))
            }
        }
    }
    items(manual, key = { "manual:${it.app.appId}" }, contentType = { "update" }) { update ->
        UpdateRow(update, state.updateAll, onAppClick, onUpdateOne)
    }
    items(storeUpdates, key = { "storeUpdate:${it.entry.app.appId}" }, contentType = { "store" }) { item ->
        val app = item.entry.app
        AppRow(
            app = app,
            onClick = { onAppClick(app) },
            supporting = stringResource(R.string.store_update_available, item.storeLabel, app.version),
            tileSize = 44.dp,
            modifier = Modifier.testTag("store:${app.appId}"),
            trailing = {
                ZapButton(
                    text = stringResource(R.string.update_app),
                    onClick = { onAppClick(app) },
                    shape = UpdateButtonShape,
                    modifier = Modifier.testTag("storeUpdate:${app.appId}"),
                )
            },
        )
    }
}

private fun LazyListScope.searchSection(
    state: UpdatesUiState,
    results: List<AppRecord>,
    onAppClick: (AppRecord) -> Unit,
    onUninstallInstalled: (packageId: String, name: String) -> Unit,
) {
    if (results.isEmpty() && state.otherSearchResults.isEmpty()) {
        item(key = "noMatches", contentType = "empty") {
            EmptyRow(stringResource(R.string.my_apps_no_matches), modifier = Modifier.testTag("myAppsNoMatches"))
        }
        return
    }
    items(results, key = { "mine:${it.appId}" }, contentType = { "installed" }) { app ->
        AppRow(
            app = app,
            onClick = { onAppClick(app) },
            author = state.profiles.authorOf(app),
            tileSize = 44.dp,
            modifier = Modifier.testTag("mine:${app.appId}"),
        )
    }
    items(state.otherSearchResults, key = { "mineOther:${it.packageId}" }, contentType = { "other" }) { installed ->
        OtherInstalledRow(installed) { onUninstallInstalled(installed.packageId, installed.label) }
    }
}

private fun LazyListScope.installedSection(state: UpdatesUiState, onAppClick: (AppRecord) -> Unit) {
    if (state.installedApps.isEmpty()) return
    item(key = "sectionInstalled", contentType = "header") {
        GroupHeader(sectionTitle(R.string.updates_section_installed, state.installedApps.size), Modifier.testTag("sectionInstalled"))
    }
    items(state.installedApps, key = { "installed:${it.appId}" }, contentType = { "installed" }) { app ->
        AppRow(
            app = app,
            onClick = { onAppClick(app) },
            author = state.profiles.authorOf(app),
            tileSize = 44.dp,
            modifier = Modifier.testTag("installed:${app.appId}"),
        )
    }
}

private fun LazyListScope.otherStoresSection(state: UpdatesUiState, onAppClick: (AppRecord) -> Unit) {
    val current = state.otherStores.filter { it.entry.update == null }
    if (current.isEmpty()) return
    item(key = "sectionStores", contentType = "header") {
        Column(Modifier.testTag("sectionStores")) {
            GroupHeader(sectionTitle(R.string.updates_section_stores, current.size))
            StatusText(stringResource(R.string.updates_stores_hint))
        }
    }
    items(current, key = { "store:${it.entry.app.appId}" }, contentType = { "store" }) { item ->
        val entry = item.entry
        AppRow(
            app = entry.app,
            onClick = { onAppClick(entry.app) },
            author = state.profiles.authorOf(entry.app),
            supporting = if (entry.signedDifferently) stringResource(R.string.store_signed_differently, item.storeLabel) else item.storeLabel,
            tileSize = 44.dp,
            modifier = Modifier.testTag("store:${entry.app.appId}"),
        )
    }
}

private fun LazyListScope.notInCatalogSection(
    state: UpdatesUiState,
    onUninstallInstalled: (packageId: String, name: String) -> Unit,
) {
    if (state.otherInstalled.isEmpty()) return
    item(key = "sectionOther", contentType = "header") {
        Column(Modifier.testTag("sectionOther")) {
            GroupHeader(sectionTitle(R.string.updates_section_other, state.otherInstalled.size))
            StatusText(stringResource(R.string.updates_other_hint))
        }
    }
    items(state.otherInstalled, key = { "other:${it.packageId}" }, contentType = { "other" }) { installed ->
        OtherInstalledRow(installed) { onUninstallInstalled(installed.packageId, installed.label) }
    }
}

private fun LazyListScope.ignoredSection(
    state: UpdatesUiState,
    onAppClick: (AppRecord) -> Unit,
    onResumeUpdates: (String) -> Unit,
) {
    if (state.ignored.isEmpty()) return
    item(key = "sectionIgnored", contentType = "header") {
        GroupHeader(sectionTitle(R.string.ignored, state.ignored.size), Modifier.testTag("sectionIgnored"))
    }
    items(state.ignored, key = { "ignored:${it.app.appId}" }, contentType = { "ignored" }) { ignored ->
        AppRow(
            app = ignored.app,
            onClick = { onAppClick(ignored.app) },
            author = state.profiles.authorOf(ignored.app),
            supporting = ignored.installer?.let { stringResource(R.string.installed_by, it) }
                ?: stringResource(R.string.installed_by_unknown),
            tileSize = 44.dp,
            modifier = Modifier.testTag("ignored:${ignored.app.appId}"),
            trailing = {
                ZapButton(
                    text = stringResource(R.string.resume),
                    onClick = { onResumeUpdates(ignored.app.appId) },
                    style = ZapButtonStyle.Tonal,
                    modifier = Modifier.testTag("resume:${ignored.app.appId}"),
                )
            },
        )
    }
}

@Composable
private fun sectionTitle(title: Int, count: Int): String = "${stringResource(title)} · $count"

/** Tile, name and the `installed → listed` pill; Update installs it (a manual one through Android's prompt). */
@Composable
private fun UpdateRow(
    update: AvailableUpdate,
    batch: UpdateAllUi,
    onAppClick: (AppRecord) -> Unit,
    onUpdateOne: (AvailableUpdate) -> Unit,
) {
    val active = batch.running && batch.currentId == update.app.appId
    Column(Modifier.testTag("update:${update.app.appId}")) {
        AppRow(
            app = update.app,
            onClick = { onAppClick(update.app) },
            supporting = null,
            installed = update.installedVersion,
            tileSize = 44.dp,
            showAuthor = false,
            trailing = {
                ZapButton(
                    text = stringResource(R.string.update_app),
                    onClick = { onUpdateOne(update) },
                    enabled = !batch.running,
                    loading = active,
                    shape = UpdateButtonShape,
                    modifier = Modifier.testTag("updateOne:${update.app.appId}"),
                )
            },
        )
        if (active) {
            val readMb = batch.received / 1_000_000.0
            val total = batch.size
            StatusText(
                when {
                    batch.received > 0 && total != null && total > 0 ->
                        stringResource(R.string.download_progress_total, readMb, total / 1_000_000.0)
                    batch.received > 0 -> stringResource(R.string.download_progress, readMb)
                    else -> stringResource(R.string.installing)
                },
                Modifier.padding(start = 56.dp, top = ZapSpacing.space1),
            )
        }
        batch.failures[update.app.appId]?.let { message ->
            StatusText(message, Modifier.padding(start = 56.dp, top = ZapSpacing.space1).testTag("updateFailed:${update.app.appId}"))
        }
    }
}

private val UpdateButtonShape = RoundedCornerShape(ZapRadius.full)

@Composable
private fun UninstallButton(packageId: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag("uninstall:$packageId")) {
        Text(stringResource(R.string.uninstall), color = ZapDanger, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun OtherInstalledRow(installed: InstalledApp, onUninstall: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ZapSpacing.space2)
            .testTag("other:${installed.packageId}"),
    ) {
        AppIcon(title = installed.label, iconUrl = null, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(installed.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(installed.packageId, color = ZapText2, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        UninstallButton(installed.packageId, onUninstall)
    }
}

@Composable
private fun UpdateAllDialog(ready: Int, group: UpdateGroup, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmSheet(
        title = pluralStringResource(R.plurals.update_all_title, ready, ready),
        body = listOf(
            stringResource(if (group == UpdateGroup.Automatic) R.string.update_all_body else R.string.updates_manual_hint),
        ),
        confirm = stringResource(R.string.update_app),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmTag = "updateAllConfirm",
    )
}

private fun Map<String, ProfileRecord>.authorOf(app: AppRecord): ProfileRecord? = app.authorPubkey?.let(::get)
