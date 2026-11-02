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
import dev.zapstore.app.ZapDanger
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3
import dev.zapstore.app.catalog.AvailableUpdate
import dev.zapstore.app.catalog.InstalledApp
import dev.zapstore.app.install.InstallTask
import dev.zapstore.app.install.TaskPhase
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import dev.zapstore.app.ZapAction
import dev.zapstore.app.ZapOnAction
import dev.zapstore.app.ZapSurface4
import dev.zapstore.app.ZapText
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.remember
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

/**
 * My apps as one list of sections: updates Zapstore installs alone, updates Android asks about
 * (including ones another store owned), apps that are current, apps another store keeps current,
 * packages the catalog lacks, and ignored apps. A submitted search replaces them all.
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
    onIgnoreUpdates: (String) -> Unit = {},
    onPause: (String) -> Unit = {},
    onSearchQueryChanged: (String) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    val rowActions = RowActions(onAppClick, onUpdateOne, onPause, onIgnoreUpdates)

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
            updatesSection(state, rowActions, onUpdateAll)
            installedSection(state, onAppClick)
            otherStoresSection(state, onAppClick)
            notInCatalogSection(state, onUninstallInstalled)
            ignoredSection(state, onAppClick, onResumeUpdates)
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

/** What an update row can do; [onIgnore] takes the app id. */
private data class RowActions(
    val onAppClick: (AppRecord) -> Unit,
    val onUpdate: (AvailableUpdate) -> Unit,
    val onPause: (String) -> Unit,
    val onIgnore: (String) -> Unit,
)

private fun LazyListScope.updatesSection(
    state: UpdatesUiState,
    actions: RowActions,
    onUpdateAll: (UpdateGroup) -> Unit,
) {
    val updates = state.updates
    val manual = state.manualUpdates
    val storeUpdates = state.otherStores.filter { it.entry.update != null }
    val idle = { list: List<AvailableUpdate> -> list.count { state.tasks[it.app.appId]?.busy != true } }
    if (updates.isNotEmpty() || (manual.isEmpty() && storeUpdates.isEmpty())) {
        item(key = "sectionUpdates", contentType = "header") {
            GroupHeader(
                stringResource(R.string.updates_section_updates),
                Modifier.testTag("sectionUpdates"),
                action = stringResource(R.string.update_all).takeIf { idle(updates) > 1 },
                onAction = { onUpdateAll(UpdateGroup.Automatic) },
                actionTag = "updateAll",
            )
        }
    }
    if (updates.isEmpty() && manual.isEmpty() && storeUpdates.isEmpty()) {
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
        UpdateRow(update, state.tasks[update.app.appId], actions)
    }
    if (manual.isNotEmpty() || storeUpdates.isNotEmpty()) {
        item(key = "sectionManual", contentType = "header") {
            Column(Modifier.testTag("sectionManual")) {
                GroupHeader(
                    stringResource(R.string.updates_section_manual),
                    action = stringResource(R.string.update_all).takeIf { idle(manual + storeUpdates.mapNotNull { it.entry.update }) > 1 },
                    onAction = { onUpdateAll(UpdateGroup.Manual) },
                    actionTag = "updateAllManual",
                )
                StatusText(stringResource(R.string.updates_manual_hint))
            }
        }
    }
    items(manual, key = { "manual:${it.app.appId}" }, contentType = { "update" }) { update ->
        UpdateRow(update, state.tasks[update.app.appId], actions)
    }
    items(storeUpdates, key = { "storeUpdate:${it.entry.app.appId}" }, contentType = { "update" }) { item ->
        val update = item.entry.update ?: return@items
        UpdateRow(
            update,
            state.tasks[update.app.appId],
            actions,
            supporting = stringResource(R.string.store_update_available, item.storeLabel, update.app.version),
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

/**
 * Tile, name and the `installed → listed` pill. The button follows the install: Update, Pause while
 * downloading, Resume, Confirm while Android's dialog waits, and a spinner while Zapstore works.
 */
@Composable
private fun UpdateRow(
    update: AvailableUpdate,
    task: InstallTask?,
    actions: RowActions,
    supporting: String? = null,
) {
    val id = update.app.appId
    val current = task?.takeIf { it.busy || it.phase == TaskPhase.Failed || it.hash.equals(update.app.apkHash, ignoreCase = true) }
    Column(Modifier.testTag("update:$id")) {
        AppRow(
            app = update.app,
            onClick = { actions.onAppClick(update.app) },
            supporting = supporting,
            installed = update.installedVersion,
            tileSize = 44.dp,
            showAuthor = false,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UpdateButton(update, current, actions)
                    IgnoreMenu(id, actions.onIgnore)
                }
            },
        )
        current?.statusText()?.let { text ->
            StatusText(text, Modifier.padding(start = 56.dp, top = ZapSpacing.space1).testTag("updateStatus:$id"))
        }
    }
}

/**
 * One pill per update, and it carries that update's progress: Update, a fill with the percent while
 * downloading (tap to pause), Paused (tap to resume), Confirm while Android's dialog waits, and a
 * spinner while Zapstore verifies or installs. Many can run at once, each in its own row.
 */
@Composable
private fun UpdateButton(update: AvailableUpdate, task: InstallTask?, actions: RowActions) {
    val id = update.app.appId
    val phase = task?.phase
    val working = phase == TaskPhase.Verifying || phase == TaskPhase.Installing
    val transferring = phase == TaskPhase.Downloading || phase == TaskPhase.Queued
    val paused = phase == TaskPhase.Paused
    val fraction = task?.total?.takeIf { it > 0 }?.let { (task.received.toFloat() / it).coerceIn(0f, 1f) }
    val idle = !transferring && !paused && !working
    val base = if (paused) ZapSurface4 else ZapAction
    val content = if (paused) ZapText else ZapOnAction
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = 84.dp)
            .height(ZapSize.control)
            .clip(UpdateButtonShape)
            .background(base)
            .clickable(enabled = !working, role = Role.Button) {
                when {
                    transferring -> actions.onPause(id)
                    else -> actions.onUpdate(update)
                }
            }
            .testTag("updateOne:$id"),
    ) {
        if (transferring && fraction != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(Color.Black.copy(alpha = 0.22f)),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            if (working || (transferring && fraction == null)) {
                CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            }
            if (paused) Icon(painterResource(R.drawable.ic_pause), contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
            val label = when {
                transferring && fraction != null -> stringResource(R.string.install_progress_percent, (fraction * 100).toInt())
                paused -> stringResource(R.string.paused)
                phase == TaskPhase.Confirm -> stringResource(R.string.confirm)
                idle -> stringResource(R.string.update_app)
                else -> null
            }
            label?.let { Text(it, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        }
    }
}

@Composable
private fun IgnoreMenu(appId: String, onIgnore: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.more_actions)
    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = description }.testTag("updateMenu:$appId"),
        ) {
            Icon(painterResource(R.drawable.ic_more), contentDescription = null, tint = ZapText3, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_ignore_updates)) },
                onClick = {
                    open = false
                    onIgnore(appId)
                },
                modifier = Modifier.testTag("ignore:$appId"),
            )
        }
    }
}

@Composable
private fun InstallTask.statusText(): String? {
    val progress = when {
        received > 0 && (total ?: 0) > 0 -> stringResource(R.string.download_progress_total, received / 1_000_000.0, total!! / 1_000_000.0)
        received > 0 -> stringResource(R.string.download_progress, received / 1_000_000.0)
        else -> null
    }
    return when (phase) {
        TaskPhase.Queued -> stringResource(R.string.install_queued)
        TaskPhase.Downloading -> progress ?: stringResource(R.string.downloading)
        TaskPhase.Paused -> progress
        TaskPhase.Verifying -> stringResource(R.string.install_verifying)
        TaskPhase.Installing -> stringResource(if (silent) R.string.install_updating else R.string.install_requesting_update)
        TaskPhase.Confirm -> stringResource(R.string.install_waiting)
        TaskPhase.Ready -> stringResource(R.string.install_ready)
        TaskPhase.Failed -> error ?: stringResource(R.string.install_failed)
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
