package dev.zapstore.app.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.key
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.zapstore.iolite.SearchFact
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.CoroutineStart
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.zapstore.app.BuildConfig
import dev.zapstore.app.R
import dev.zapstore.app.ZapAction
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapDanger
import dev.zapstore.app.ZapDangerFill
import dev.zapstore.app.ZapOnAction
import dev.zapstore.app.ZapOnDangerFill
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapSurface4
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3
import dev.zapstore.app.ZapVerified
import dev.zapstore.app.ZapWarning
import dev.zapstore.app.largeFont
import dev.zapstore.app.catalog.AvailableUpdate
import dev.zapstore.app.catalog.InstalledApp
import dev.zapstore.app.catalog.ManualReason
import dev.zapstore.app.components.AppIcon
import dev.zapstore.app.components.AppRow
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.GroupCount
import dev.zapstore.app.components.GroupHeader
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapSearchField
import dev.zapstore.app.install.InstallTask
import dev.zapstore.app.install.TaskPhase
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.ProfileRecord
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * My apps as one list of sections: installs in progress, updates Zapstore installs alone, updates
 * Android asks about (including ones another store owned), apps that are current, apps another
 * store keeps current, packages the catalog lacks, and ignored apps. A submitted search puts its
 * results in the same panel as Home's, above them. Row actions live behind a swipe to the left: Cancel
 * while an install is in progress, Ignore on catalog apps, Uninstall on packages the catalog lacks,
 * Resume on ignored apps.
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
    onInstall: (AppRecord) -> Unit = {},
    onCancelInstall: (String) -> Unit = {},
    onReinstall: (AppRecord) -> Unit = {},
    onSearchQueryChanged: (String) -> Unit = {},
    onSearchSubmitted: () -> Unit = {},
    onSearchCleared: () -> Unit = {},
    onRemoveFilter: (SearchFact) -> Unit = {},
    onToggleFilter: (SearchFact) -> Unit = {},
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    if (filtersOpen) {
        FilterSheet(active = state.searchFacts, onToggle = onToggleFilter, onDismiss = { filtersOpen = false })
    }
    val listState = rememberLazyListState()
    // The first Back empties the search; only the next one leaves the screen.
    BackHandler(enabled = state.searchQuery.isNotEmpty() || state.searchResults != null, onBack = onSearchCleared)
    // Results are inserted above the sections, which the list would otherwise stay anchored to.
    // Skip the first run: coming back to this tab must keep the restored position.
    var scrollKey by rememberSaveable { mutableStateOf<Pair<String, Boolean>?>(null) }
    val queryKey = state.submittedQuery to (state.searchResults == null)
    LaunchedEffect(queryKey) {
        if (scrollKey != null && scrollKey != queryKey) listState.scrollToItem(0)
        scrollKey = queryKey
    }
    val searchFocusRequester = remember { FocusRequester() }
    val rowActions = RowActions(onAppClick, onUpdateOne, onPause, onIgnoreUpdates, onInstall, onCancelInstall, onReinstall)

    CompositionLocalProvider(LocalOpenSwipe provides remember { mutableStateOf<String?>(null) }) {
        Column(modifier = modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = ZapSpacing.space4, end = ZapSpacing.space2, top = ZapSpacing.space2, bottom = ZapSpacing.space2),
            ) {
                Text(
                    stringResource(R.string.my_apps),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (BuildConfig.DEBUG) {
                    ZapButton(
                        text = stringResource(R.string.sync_now),
                        onClick = onSync,
                        style = ZapButtonStyle.Primary,
                        loading = state.status.syncing,
                        modifier = Modifier.testTag("syncCatalog"),
                    )
                }
            }
            state.status.error?.let {
                StatusText(it, Modifier.padding(horizontal = ZapSpacing.space4).testTag("updatesError"))
            }
            AppSearchField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChanged,
                onSearch = onSearchSubmitted,
                onClear = {
                    onSearchCleared()
                    searchFocusRequester.requestFocus()
                },
                placeholder = stringResource(R.string.search_my_apps),
                hasResults = state.searchResults != null,
                clearTag = "clearMyAppsSearch",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZapSpacing.space4, vertical = ZapSpacing.space2)
                    .focusRequester(searchFocusRequester)
                    .testTag("myAppsSearch"),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).testTag("myAppsList"),
                contentPadding = PaddingValues(start = ZapSpacing.space4, end = ZapSpacing.space4, bottom = ZapSpacing.space8),
            ) {
                // Nothing until the local read lands; a spinner for a database read is only a flash.
                if (!state.loaded) return@LazyColumn
                state.searchResults?.let { results ->
                    searchSection(state, results, rowActions, onUninstallInstalled, onRemoveFilter) { filtersOpen = true }
                }
                installingSection(state, rowActions, onCancelInstall)
                updatesSection(state, rowActions, onUpdateAll)
                installedSection(state, onAppClick, onIgnoreUpdates, onUninstall)
                otherStoresSection(state, rowActions)
                notInCatalogSection(state, onUninstallInstalled)
                ignoredSection(state, onAppClick, onResumeUpdates)
                catalogEpochsItem(state.catalogEpochs)
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

/** The check, then "All up to date". */
@Composable
private fun UpToDateRow() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZapRadius.md))
            .background(ZapSurface1)
            .padding(ZapSpacing.space4)
            .testTag("noUpdates"),
    ) {
        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = ZapVerified, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.all_up_to_date), color = ZapText2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CatalogUpdatedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = ZapText3,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.75.sp, lineHeight = 14.75.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.testTag("catalogStatus"),
    )
}

/** One `EPOCH n` pill per catalog, the last row of the list. */
private fun LazyListScope.catalogEpochsItem(epochs: List<Long>) {
    item(key = "epochs", contentType = "epochs") { CatalogEpochs(epochs) }
}

@Composable
private fun CatalogEpochs(epochs: List<Long>) {
    val shown = epochs.filter { it > 0 }
    if (shown.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
        // The list already pads its sides, so the first pill starts where the rows do.
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ZapSpacing.space2, bottom = ZapSpacing.space4),
    ) {
        shown.forEach { EpochPill(it) }
    }
}

/** Gray pill reading `EPOCH 24`. */
@Composable
private fun EpochPill(epoch: Long) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minHeight = 16.dp)
            .clip(RoundedCornerShape(ZapRadius.full))
            .background(ZapSurface3)
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .testTag("catalogEpoch"),
    ) {
        Text(
            stringResource(R.string.catalog_epoch_pill, epoch),
            color = ZapText2,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 12.75.sp, fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
private fun checkedText(syncedAtMillis: Long): String {
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
        minutes < 1 -> stringResource(R.string.checked_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.checked_minutes_ago, minutes, minutes)
        hours < 48 -> pluralStringResource(R.plurals.checked_hours_ago, hours, hours)
        else -> stringResource(
            R.string.checked_at,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(syncedAtMillis)),
        )
    }
}

/** What an update row can do; [onIgnore] takes the app id. */
private data class RowActions(
    val onAppClick: (AppRecord) -> Unit,
    val onUpdate: (AvailableUpdate) -> Unit,
    val onPause: (String) -> Unit,
    val onIgnore: (String) -> Unit,
    val onInstall: (AppRecord) -> Unit,
    val onCancel: (String) -> Unit,
    val onReinstall: (AppRecord) -> Unit,
)

/** Installs Zapstore is working on or that wait for a tap, so a first install can be followed here too. */
private fun LazyListScope.installingSection(
    state: UpdatesUiState,
    actions: RowActions,
    onCancel: (String) -> Unit,
) {
    if (state.installing.isEmpty()) return
    item(key = "sectionInstalling", contentType = "header") {
        GroupHeader(stringResource(R.string.updates_section_installing), Modifier.testTag("sectionInstalling"), count = state.installing.size)
    }
    items(state.installing, key = { "installing:${it.appId}" }, contentType = { "update" }) { app ->
        val task = state.tasks[app.appId]
        SwipeActions(
            key = "installing:${app.appId}",
            actions = buildList {
                add(ignoreAction(app.appId, actions.onIgnore))
                if (task != null && (task.cancellable || task.phase == TaskPhase.Failed)) add(cancelAction(app.appId, onCancel))
            },
        ) {
            val idle = stringResource(R.string.install)
            InstallRow(
                app = app,
                button = { modifier -> UpdateButton(app, task, idle, { actions.onInstall(app) }, actions, modifier) },
            ) { trailing ->
                AppRow(
                    app = app,
                    onClick = { actions.onAppClick(app) },
                    onDevice = true,
                    showAuthor = false,
                    modifier = Modifier.testTag("installing:${app.appId}"),
                    trailing = trailing,
                )
            }
        }
    }
}

private fun LazyListScope.updatesSection(
    state: UpdatesUiState,
    actions: RowActions,
    onUpdateAll: (UpdateGroup) -> Unit,
) {
    val updates = state.updates
    val manual = state.manualUpdates
    val idle = { list: List<AvailableUpdate> -> list.count { state.tasks[it.app.appId]?.busy != true } }
    if (updates.isNotEmpty() || manual.isEmpty()) {
        item(key = "sectionUpdates", contentType = "header") {
            GroupHeader(
                stringResource(R.string.updates_section_updates),
                Modifier.testTag("sectionUpdates"),
                count = updates.size.takeIf { it > 0 },
                action = stringResource(R.string.update_all).takeIf { idle(updates) > 1 },
                onAction = { onUpdateAll(UpdateGroup.Automatic) },
                actionTag = "updateAll",
            )
        }
    }
    if (updates.isEmpty() && manual.isEmpty()) {
        item(key = "noUpdates", contentType = "empty") {
            if (state.status.syncing) {
                EmptyRow(stringResource(R.string.syncing), modifier = Modifier.testTag("noUpdates"))
            } else {
                UpToDateRow()
            }
        }
        checkedItem(state)
        return
    }
    items(updates, key = { "update:${it.app.appId}" }, contentType = { "update" }) { update ->
        UpdateRow(update, state.tasks[update.app.appId], actions)
    }
    if (manual.isNotEmpty()) {
        item(key = "sectionManual", contentType = "header") {
            GroupHeader(
                stringResource(R.string.updates_section_manual),
                Modifier.testTag("sectionManual"),
                count = manual.size,
                action = stringResource(R.string.update_all).takeIf { idle(manual) > 1 },
                onAction = { onUpdateAll(UpdateGroup.Manual) },
                actionTag = "updateAllManual",
            )
        }
    }
    // Updates whose installer is unknown come first, as they are; the rest sit under the store that installed them.
    val (named, unnamed) = manual.partition { state.manualStores[it.app.appId] != null }
    items(unnamed, key = { "manual:${it.app.appId}" }, contentType = { "update" }) { update ->
        UpdateRow(update, state.tasks[update.app.appId], actions)
    }
    named.groupBy { state.manualStores.getValue(it.app.appId) }
        .entries
        .sortedBy { it.key.lowercase() }
        .forEach { (store, group) ->
            item(key = "manualStore:$store", contentType = "installerLabel") { InstallerLabel(store) }
            items(group, key = { "manual:${it.app.appId}" }, contentType = { "update" }) { update ->
                UpdateRow(update, state.tasks[update.app.appId], actions)
            }
        }
    checkedItem(state)
}

/** The installer that put the apps below it on the phone: a small label, not a section. */
@Composable
private fun InstallerLabel(name: String) {
    Text(
        stringResource(R.string.installed_by, name),
        color = ZapText3,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = ZapSpacing.space3, bottom = ZapSpacing.space1).testTag("installerLabel"),
    )
}

/** "Checked 5 minutes ago", under the updates, once a catalog check has run. */
private fun LazyListScope.checkedItem(state: UpdatesUiState) {
    val text = if (state.status.syncing) null else state.status.lastSyncedAtMillis
    if (text == null) return
    item(key = "catalogChecked", contentType = "checked") { CatalogChecked(checkedText(text)) }
}

/** A tiny sync mark, then the time of the last catalog check. */
@Composable
private fun CatalogChecked(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space1),
        modifier = Modifier.padding(top = ZapSpacing.space2, bottom = ZapSpacing.space1),
    ) {
        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = ZapText3, modifier = Modifier.size(12.dp))
        CatalogUpdatedText(text)
    }
}

/**
 * Submitted results above the sections: Home's gray panel with its filter chips, then the rows. An app
 * with an update, or an install under way, carries the same button as in its section. Packages outside the
 * catalog that match stay swipeable, to uninstall.
 */
private fun LazyListScope.searchSection(
    state: UpdatesUiState,
    results: List<AppRecord>,
    actions: RowActions,
    onUninstallInstalled: (packageId: String, name: String) -> Unit,
    onRemoveFilter: (SearchFact) -> Unit,
    onOpenFilters: () -> Unit,
) {
    item(key = "searchPanel", contentType = "searchPanel") {
        val updates = remember(state.updates, state.manualUpdates) { (state.updates + state.manualUpdates).associateBy { it.app.appId } }
        SearchPanel(
            facts = state.searchFacts,
            shown = results,
            hasOthers = state.otherSearchResults.isNotEmpty(),
            onRemoveFilter = onRemoveFilter,
            onOpenFilters = onOpenFilters,
        ) {
            results.forEach { app ->
                key("mine:${app.appId}") {
                    val id = app.appId
                    val task = state.tasks[id]
                    val update = updates[id]
                    val installing = state.installing.any { it.appId == id }
                    val author = state.profiles.authorOf(app)
                    if (update != null || installing) {
                        val idle = stringResource(if (update != null) R.string.update_app else R.string.install)
                        val current = task?.takeIf { it.busy || it.phase == TaskPhase.Failed || it.phase == TaskPhase.Done || it.hash.equals(app.apkHash, ignoreCase = true) }
                        InstallRow(
                            app = app,
                            button = { modifier ->
                                UpdateButton(app, current, idle, { if (update != null) actions.onUpdate(update) else actions.onInstall(app) }, actions, modifier)
                            },
                        ) { trailing ->
                            AppRow(
                                app = app,
                                onClick = { actions.onAppClick(app) },
                                author = author,
                                trailing = trailing,
                                modifier = Modifier.testTag("mine:$id"),
                            )
                        }
                    } else {
                        AppRow(
                            app = app,
                            onClick = { actions.onAppClick(app) },
                            author = author,
                            modifier = Modifier.testTag("mine:$id"),
                        )
                    }
                }
            }
            state.otherSearchResults.forEach { installed ->
                key("mineOther:${installed.packageId}") {
                    OtherInstalledRow(installed) { onUninstallInstalled(installed.packageId, installed.label) }
                }
            }
        }
    }
}

private fun LazyListScope.installedSection(
    state: UpdatesUiState,
    onAppClick: (AppRecord) -> Unit,
    onIgnore: (String) -> Unit,
    onUninstall: (AppRecord) -> Unit,
) {
    if (state.installedApps.isEmpty()) return
    item(key = "sectionInstalled", contentType = "header") {
        GroupHeader(stringResource(R.string.updates_section_installed), Modifier.testTag("sectionInstalled"), count = state.installedApps.size)
    }
    items(state.installedApps, key = { "installed:${it.appId}" }, contentType = { "installed" }) { app ->
        val swipe = listOf(ignoreAction(app.appId, onIgnore), uninstallAction(app, onUninstall))
        SwipeActions("installed:${app.appId}", swipe) {
            AppRow(
                app = app,
                onClick = { onAppClick(app) },
                onDevice = true,
                author = state.profiles.authorOf(app),
                modifier = Modifier.testTag("installed:${app.appId}"),
            )
        }
    }
}

/** Apps another store keeps current with a key the catalog does not list, one sublist per store. */
private fun LazyListScope.otherStoresSection(state: UpdatesUiState, actions: RowActions) {
    val current = state.otherStores
    if (current.isEmpty()) return
    item(key = "sectionStores", contentType = "header") {
        GroupHeader(stringResource(R.string.updates_section_stores), Modifier.testTag("sectionStores"), count = current.size)
    }
    current.groupBy { it.entry.store }
        .entries
        .sortedWith(compareBy({ it.value.first().storeLabel.lowercase() }, { it.key.lowercase() }))
        .forEachIndexed { index, (id, apps) ->
            item(key = "storeGroup:$id", contentType = "storeHeader") {
                StoreGroupHeader(
                    name = apps.first().storeLabel,
                    appId = id,
                    count = apps.size,
                    modifier = Modifier
                        .padding(top = if (index == 0) 0.dp else ZapSpacing.space3)
                        .testTag("storeGroup:$id"),
                )
            }
            items(apps, key = { "store:${it.entry.app.appId}" }, contentType = { "store" }) { item ->
                val entry = item.entry
                val id = entry.app.appId
                SwipeActions("store:$id", listOf(ignoreAction(id, actions.onIgnore))) {
                    AppRow(
                        app = entry.app,
                        onClick = { actions.onAppClick(entry.app) },
                        onDevice = true,
                        author = state.profiles.authorOf(entry.app),
                        note = if (entry.signedDifferently) stringResource(R.string.store_signed_differently) else null,
                        modifier = Modifier.testTag("store:$id"),
                    )
                }
            }
        }
}

/**
 * Store name under Updated by other stores, then its package id on the same line when Android
 * gave the store a name of its own; otherwise the name already is that id.
 */
@Composable
private fun StoreGroupHeader(name: String, appId: String, count: Int, modifier: Modifier = Modifier) {
    val id = appId.takeUnless { it.equals(name, ignoreCase = true) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 32.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            name,
            color = ZapText2,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (id != null) {
            Text(
                id,
                color = ZapText3,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        GroupCount(count)
    }
}

private fun LazyListScope.notInCatalogSection(
    state: UpdatesUiState,
    onUninstallInstalled: (packageId: String, name: String) -> Unit,
) {
    if (state.otherInstalled.isEmpty()) return
    item(key = "sectionOther", contentType = "header") {
        GroupHeader(stringResource(R.string.updates_section_other), Modifier.testTag("sectionOther"), count = state.otherInstalled.size)
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
        GroupHeader(stringResource(R.string.ignored), Modifier.testTag("sectionIgnored"), count = state.ignored.size)
    }
    items(state.ignored, key = { "ignored:${it.app.appId}" }, contentType = { "ignored" }) { ignored ->
        val id = ignored.app.appId
        val resume = RowAction(
            label = stringResource(R.string.resume),
            icon = R.drawable.ic_update,
            background = ZapAction,
            foreground = ZapOnAction,
            tag = "resume:$id",
            onClick = { onResumeUpdates(id) },
        )
        SwipeActions("ignored:$id", listOf(resume)) {
            AppRow(
                app = ignored.app,
                onClick = { onAppClick(ignored.app) },
                onDevice = true,
                author = state.profiles.authorOf(ignored.app),
                note = ignored.installer?.let { stringResource(R.string.installed_by, it) }
                    ?: stringResource(R.string.installed_by_unknown),
                modifier = Modifier.testTag("ignored:$id"),
            )
        }
    }
}

/** One button a swipe uncovers. */
private class RowAction(
    val label: String,
    val icon: Int,
    val background: Color,
    val foreground: Color,
    val tag: String,
    val onClick: () -> Unit,
)

/** The row whose buttons are showing; opening another row closes it. */
private val LocalOpenSwipe = compositionLocalOf<MutableState<String?>> { mutableStateOf(null) }

private val ActionWidth = 84.dp

/** Space between the sliding row and the buttons it uncovers. */
private val SwipeGap = ZapSpacing.space2

// Darker than the tokens' amber and red, which are made for dark surfaces: white text must read on them.
private val SwipeIgnore = Color(0xFF9A6200)
private val SwipeDanger = Color(0xFFB83532)
private const val FLING_VELOCITY = 800f

/**
 * Swipe left and the row slides over to uncover [actions] as buttons; nothing runs until one is
 * tapped. It stays open until a button is tapped, the row is tapped or swiped back, or another row
 * opens. Screen readers get the same actions by name.
 */
@Composable
private fun SwipeActions(
    key: String,
    actions: List<RowAction>,
    modifier: Modifier = Modifier,
    plate: Boolean = false,
    content: @Composable () -> Unit,
) {
    val open = LocalOpenSwipe.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val reveal = with(LocalDensity.current) { (ActionWidth * actions.size + SwipeGap).toPx() }
    val showing by remember { derivedStateOf { offset.value < 0f } }
    val opened = offset.targetValue < 0f

    fun settle(target: Float) {
        open.value = if (target < 0f) key else open.value.takeIf { it != key }
        scope.launch { offset.animateTo(target, tween(durationMillis = 180)) }
    }

    LaunchedEffect(open.value) {
        if (open.value != key && offset.targetValue != 0f) offset.animateTo(0f, tween(durationMillis = 180))
    }
    LaunchedEffect(reveal) {
        if (offset.targetValue < 0f && offset.targetValue != -reveal) offset.animateTo(-reveal)
    }
    val drag = rememberDraggableState { delta ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo((offset.value + delta).coerceIn(-reveal, 0f)) }
    }

    Box(
        modifier = modifier.semantics {
            customActions = actions.map { action -> CustomAccessibilityAction(action.label) { action.onClick(); true } }
        },
    ) {
        if (showing) {
            // Flush with the row's full height, right edge to the buttons' own left edge; only the outer right corners are rounded; the left edge is square.
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.matchParentSize(),
            ) {
                Row(Modifier.fillMaxHeight().clip(RoundedCornerShape(topEnd = ZapRadius.md, bottomEnd = ZapRadius.md))) {
                    actions.forEach { action ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .width(ActionWidth)
                                .fillMaxHeight()
                                .background(action.background)
                                .clickable(role = Role.Button) {
                                    settle(0f)
                                    action.onClick()
                                }
                                .padding(horizontal = ZapSpacing.space1)
                                .testTag(action.tag),
                        ) {
                            Icon(painterResource(action.icon), contentDescription = null, tint = action.foreground, modifier = Modifier.size(20.dp))
                            Text(
                                action.label,
                                color = action.foreground,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.02).em,
                                ),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .draggable(
                    state = drag,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        settle(
                            when {
                                velocity < -FLING_VELOCITY -> -reveal
                                velocity > FLING_VELOCITY -> 0f
                                offset.value < -reveal / 2 -> -reveal
                                else -> 0f
                            },
                        )
                    },
                ),
        ) {
            // A plate is the gray of a stack card, with its corners; the buttons stay hidden behind it.
            Box(if (plate) Modifier.clip(RoundedCornerShape(ZapRadius.md)).background(ZapSurface1) else Modifier.background(ZapCanvas)) { content() }
            if (opened) {
                // An open row swallows taps so one tap closes it instead of opening the app.
                Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null) { settle(0f) })
            }
        }
    }
}

@Composable
private fun ignoreAction(appId: String, onIgnore: (String) -> Unit) = RowAction(
    label = stringResource(R.string.action_ignore_updates),
    icon = R.drawable.ic_close,
    background = SwipeIgnore,
    foreground = ZapOnAction,
    tag = "ignore:$appId",
    onClick = { onIgnore(appId) },
)

@Composable
private fun cancelAction(appId: String, onCancel: (String) -> Unit) = RowAction(
    label = stringResource(R.string.install_cancel),
    icon = R.drawable.ic_close,
    background = SwipeDanger,
    foreground = ZapOnAction,
    tag = "cancelInstall:$appId",
    onClick = { onCancel(appId) },
)

/** Installed apps only: removes it from the device, next to Ignore updates. */
@Composable
private fun uninstallAction(app: AppRecord, onUninstall: (AppRecord) -> Unit) = RowAction(
    label = stringResource(R.string.uninstall),
    icon = R.drawable.ic_trash,
    background = SwipeDanger,
    foreground = ZapOnAction,
    tag = "uninstall:${app.appId}",
    onClick = { onUninstall(app) },
)

/**
 * Tile, name and the `installed → listed` pill. The fixed-width button carries the whole install:
 * no second line. Swipe left for Ignore updates, and Cancel while an install is in progress.
 */
@Composable
private fun UpdateRow(
    update: AvailableUpdate,
    task: InstallTask?,
    actions: RowActions,
) {
    val id = update.app.appId
    val current = task?.takeIf { it.busy || it.phase == TaskPhase.Failed || it.phase == TaskPhase.Done || it.hash.equals(update.app.apkHash, ignoreCase = true) }
    val swipe = buildList {
        add(ignoreAction(id, actions.onIgnore))
        // A failed install is the other thing to drop: without it the Retry pill would never go away.
        if (current != null && (current.cancellable || current.phase == TaskPhase.Failed)) add(cancelAction(id, actions.onCancel))
    }
    // Zapstore's own update is pinned first; it sits on the gray of a stack card, inset from the plate's edge.
    val own = id == LocalContext.current.packageName
    SwipeActions(
        "update:$id",
        swipe,
        modifier = if (own) Modifier.padding(bottom = ZapSpacing.space1) else Modifier,
        plate = own,
    ) {
        val idle = stringResource(R.string.update_app)
        InstallRow(
            app = update.app,
            button = { modifier -> UpdateButton(update.app, current, idle, { actions.onUpdate(update) }, actions, modifier) },
            modifier = if (own) Modifier.padding(horizontal = ZapSpacing.space3) else Modifier,
        ) { trailing ->
            AppRow(
                app = update.app,
                onClick = { actions.onAppClick(update.app) },
                onDevice = true,
                pendingFrom = update.installedVersion,
                showAuthor = false,
                modifier = Modifier.testTag("update:$id"),
                trailing = trailing,
            )
        }
    }
}

/**
 * An app [row] and its install [button]: beside the text, or at a large font on a full-width line of
 * its own under it, so the name and versions keep the row. Failures are told in a toast, not here.
 */
@Composable
private fun InstallRow(
    app: AppRecord,
    button: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (trailing: (@Composable () -> Unit)?) -> Unit,
) {
    val large = largeFont()
    Column(modifier) {
        if (large) {
            row(null)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = ZapSpacing.space2)) {
                button(Modifier.weight(1f))
            }
        } else {
            row {
                button(Modifier.width(UpdateButtonWidth))
            }
        }
    }
}

/**
 * One pill per install or update, [UpdateButtonWidth] wide beside a row, carrying its progress:
 * [idleLabel], Queued, a fill with the percent while downloading (tap to pause), Paused over the
 * same fill (tap to resume), a spinner while Zapstore verifies or installs, Confirm while Android's
 * dialog waits, and Retry after a failure. A failure only an uninstall can clear reads Reinstall and
 * asks first. Cancelling is a swipe on the row, not part of the pill.
 */
@Composable
private fun UpdateButton(
    app: AppRecord,
    task: InstallTask?,
    idleLabel: String,
    onStart: () -> Unit,
    actions: RowActions,
    modifier: Modifier = Modifier,
) {
    val id = app.appId
    val onPause = actions.onPause
    val phase = task?.phase
    // Done is the second between Android installing it and the list noticing: still working, never Update.
    val working = phase == TaskPhase.Verifying || phase == TaskPhase.Installing || phase == TaskPhase.Done
    val transferring = phase == TaskPhase.Downloading || phase == TaskPhase.Queued
    val paused = phase == TaskPhase.Paused
    val failed = phase == TaskPhase.Failed
    val reinstall = failed && task?.needsReinstall == true
    val fraction = task?.total?.takeIf { it > 0 }?.let { (task.received.toFloat() / it).coerceIn(0f, 1f) }
    val base = when {
        paused -> ZapSurface4
        failed -> ZapDangerFill
        else -> ZapAction
    }
    val content = when {
        paused -> ZapText
        failed -> ZapOnDangerFill
        else -> ZapOnAction
    }
    Row(
        modifier = modifier
            .height(ZapSize.control)
            .clip(UpdateButtonShape)
            .background(base),
    ) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(enabled = !working, role = Role.Button) {
                when {
                    transferring -> onPause(id)
                    reinstall -> actions.onReinstall(app)
                    else -> onStart()
                }
            }
            .testTag(if (reinstall) "reinstall:$id" else "updateOne:$id"),
    ) {
        if ((transferring || paused) && fraction != null) {
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
            modifier = Modifier.padding(horizontal = ZapSpacing.space2),
        ) {
            if (working || (transferring && fraction == null)) {
                CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
            val label = when {
                phase == TaskPhase.Queued -> stringResource(R.string.queued)
                transferring && fraction != null -> stringResource(R.string.install_progress_percent, (fraction * 100).toInt())
                paused -> stringResource(R.string.paused)
                // Ready: downloaded and verified, only Android's dialog is left, so say what the tap does.
                phase == TaskPhase.Confirm || phase == TaskPhase.Ready -> stringResource(R.string.confirm)
                reinstall -> stringResource(R.string.reinstall)
                failed -> stringResource(R.string.retry)
                working || transferring -> null
                else -> idleLabel
            }
            label?.let { Text(it, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    }
}

private val UpdateButtonShape = RoundedCornerShape(ZapRadius.full)
private val UpdateButtonWidth = 138.dp

@Composable
private fun OtherInstalledRow(installed: InstalledApp, onUninstall: () -> Unit) {
    val uninstall = RowAction(
        label = stringResource(R.string.uninstall),
        icon = R.drawable.ic_trash,
        background = ZapDanger,
        foreground = ZapOnAction,
        tag = "uninstall:${installed.packageId}",
        onClick = onUninstall,
    )
    SwipeActions("other:${installed.packageId}", listOf(uninstall)) {
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
        }
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
