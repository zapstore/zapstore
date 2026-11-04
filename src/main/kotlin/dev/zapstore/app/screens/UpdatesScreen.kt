package dev.zapstore.app.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zapstore.app.BuildConfig
import dev.zapstore.app.R
import dev.zapstore.app.Toast
import dev.zapstore.app.ToastKind
import dev.zapstore.app.Toasts
import dev.zapstore.app.ZapAction
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapDangerFill
import dev.zapstore.app.ZapOnAction
import dev.zapstore.app.ZapOnDangerFill
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapSurface4
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3
import dev.zapstore.app.ZapVerified
import dev.zapstore.app.ZapWarning
import dev.zapstore.app.ZapWarningTint
import dev.zapstore.app.catalog.AvailableUpdate
import dev.zapstore.app.catalog.InstalledApp
import dev.zapstore.app.catalog.ManualReason
import dev.zapstore.app.components.AppIcon
import dev.zapstore.app.components.AppRow
import dev.zapstore.app.components.AppRowStyle
import dev.zapstore.app.components.EmptyRow
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
 * My apps as one list of sections: updates Zapstore installs alone, updates Android asks about
 * (including ones another store owned), apps that are current, apps another store keeps current,
 * packages the catalog lacks, and ignored apps. A submitted search replaces them all.
 * Row actions live behind a swipe to the left: Ignore on catalog apps, Uninstall on packages the
 * catalog lacks, Resume on ignored apps.
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
                CatalogStatus(state)
            }
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
            // Nothing until the local read lands; a spinner for a database read is only a flash.
            if (!state.loaded) return@LazyColumn
            state.searchResults?.let { results ->
                searchSection(state, results, onAppClick, onUninstallInstalled)
                return@LazyColumn
            }
            updatesSection(state, rowActions, onUpdateAll)
            installedSection(state, onAppClick, onIgnoreUpdates)
            otherStoresSection(state, onAppClick, onIgnoreUpdates)
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

/** `Updated 5 minutes ago` beside a catalog epoch pill for each catalog, or Syncing while a pull runs. */
@Composable
private fun CatalogStatus(state: UpdatesUiState) {
    val status = when {
        state.status.syncing -> stringResource(R.string.syncing)
        state.status.lastSyncedAtMillis != null -> updatedText(state.status.lastSyncedAtMillis)
        else -> null
    }
    val epochs = state.catalogEpochs.filter { it > 0 }
    if (status == null && epochs.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        status?.let { StatusText(it, Modifier.testTag("catalogStatus")) }
        epochs.forEach { EpochPill(it) }
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
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp, fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
private fun updatedText(syncedAtMillis: Long): String {
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
        minutes < 1 -> stringResource(R.string.updated_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.updated_minutes_ago, minutes, minutes)
        hours < 48 -> pluralStringResource(R.plurals.updated_hours_ago, hours, hours)
        else -> stringResource(
            R.string.updated_at,
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
                count = updates.size.takeIf { it > 0 },
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
            GroupHeader(
                stringResource(R.string.updates_section_manual),
                Modifier.testTag("sectionManual"),
                count = manual.size + storeUpdates.size,
                action = stringResource(R.string.update_all).takeIf { idle(manual + storeUpdates.mapNotNull { it.entry.update }) > 1 },
                onAction = { onUpdateAll(UpdateGroup.Manual) },
                actionTag = "updateAllManual",
            )
        }
    }
    items(manual, key = { "manual:${it.app.appId}" }, contentType = { "update" }) { update ->
        UpdateRow(update, state.tasks[update.app.appId], actions, why = state.manualReasons[update.app.appId]?.let(UpdateWhy::Manual))
    }
    items(storeUpdates, key = { "storeUpdate:${it.entry.app.appId}" }, contentType = { "update" }) { item ->
        val update = item.entry.update ?: return@items
        UpdateRow(update, state.tasks[update.app.appId], actions, why = UpdateWhy.Store(item.storeLabel))
    }
}

/** Why an update in the manual section needs Android's dialog. */
private sealed interface UpdateWhy {
    data class Manual(val reason: ManualReason) : UpdateWhy
    data class Store(val name: String) : UpdateWhy
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
            style = AppRowStyle.Manage,
            author = state.profiles.authorOf(app),
            modifier = Modifier.testTag("mine:${app.appId}"),
        )
    }
    items(state.otherSearchResults, key = { "mineOther:${it.packageId}" }, contentType = { "other" }) { installed ->
        OtherInstalledRow(installed) { onUninstallInstalled(installed.packageId, installed.label) }
    }
}

private fun LazyListScope.installedSection(
    state: UpdatesUiState,
    onAppClick: (AppRecord) -> Unit,
    onIgnore: (String) -> Unit,
) {
    if (state.installedApps.isEmpty()) return
    item(key = "sectionInstalled", contentType = "header") {
        GroupHeader(stringResource(R.string.updates_section_installed), Modifier.testTag("sectionInstalled"), count = state.installedApps.size)
    }
    items(state.installedApps, key = { "installed:${it.appId}" }, contentType = { "installed" }) { app ->
        IgnoreSwipe(app.appId, onIgnore) {
            AppRow(
                app = app,
                onClick = { onAppClick(app) },
                style = AppRowStyle.Manage,
                author = state.profiles.authorOf(app),
                modifier = Modifier.testTag("installed:${app.appId}"),
            )
        }
    }
}

private fun LazyListScope.otherStoresSection(
    state: UpdatesUiState,
    onAppClick: (AppRecord) -> Unit,
    onIgnore: (String) -> Unit,
) {
    val current = state.otherStores.filter { it.entry.update == null }
    if (current.isEmpty()) return
    item(key = "sectionStores", contentType = "header") {
        GroupHeader(stringResource(R.string.updates_section_stores), Modifier.testTag("sectionStores"), count = current.size)
    }
    items(current, key = { "store:${it.entry.app.appId}" }, contentType = { "store" }) { item ->
        val entry = item.entry
        IgnoreSwipe(entry.app.appId, onIgnore) {
            AppRow(
                app = entry.app,
                onClick = { onAppClick(entry.app) },
                style = AppRowStyle.Manage,
                author = state.profiles.authorOf(entry.app),
                note = if (entry.signedDifferently) stringResource(R.string.store_signed_differently, item.storeLabel) else item.storeLabel,
                modifier = Modifier.testTag("store:${entry.app.appId}"),
            )
        }
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
        SwipeAction(
            label = stringResource(R.string.resume),
            icon = R.drawable.ic_update,
            background = ZapAction,
            foreground = ZapOnAction,
            onAction = { onResumeUpdates(id) },
            modifier = Modifier.testTag("resume:$id"),
        ) {
            AppRow(
                app = ignored.app,
                onClick = { onAppClick(ignored.app) },
                style = AppRowStyle.Manage,
                author = state.profiles.authorOf(ignored.app),
                note = ignored.installer?.let { stringResource(R.string.installed_by, it) }
                    ?: stringResource(R.string.installed_by_unknown),
                modifier = Modifier.testTag("ignored:$id"),
            )
        }
    }
}

/**
 * Swipe left to reveal [label] and run [onAction]; the row springs back afterwards, and a row that
 * leaves its section with the action simply goes. Screen readers get the same action by name.
 */
@Composable
private fun SwipeAction(
    label: String,
    icon: Int,
    background: Color,
    foreground: Color,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = enabled,
        onDismiss = {
            onAction()
            scope.launch { state.reset() }
        },
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = ZapSpacing.space1)
                        .clip(RoundedCornerShape(ZapRadius.md))
                        .background(background)
                        .padding(horizontal = ZapSpacing.space4),
                ) {
                    Icon(painterResource(icon), contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp))
                    Text(label, color = foreground, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        },
        modifier = modifier.semantics {
            if (enabled) customActions = listOf(CustomAccessibilityAction(label) { onAction(); true })
        },
    ) {
        Box(Modifier.background(ZapCanvas)) { content() }
    }
}

@Composable
private fun IgnoreSwipe(appId: String, onIgnore: (String) -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    SwipeAction(
        label = stringResource(R.string.ignore),
        icon = R.drawable.ic_close,
        background = ZapWarningTint,
        foreground = ZapWarning,
        onAction = { onIgnore(appId) },
        modifier = Modifier.testTag("ignore:$appId"),
        enabled = enabled,
        content = content,
    )
}

/**
 * Tile, name and the `installed → listed` pill. The fixed-width button carries the whole install:
 * no second line. Swipe left to ignore, except while Zapstore is working on it.
 */
@Composable
private fun UpdateRow(
    update: AvailableUpdate,
    task: InstallTask?,
    actions: RowActions,
    why: UpdateWhy? = null,
) {
    val id = update.app.appId
    val current = task?.takeIf { it.busy || it.phase == TaskPhase.Failed || it.hash.equals(update.app.apkHash, ignoreCase = true) }
    IgnoreSwipe(id, actions.onIgnore, enabled = current?.busy != true) {
        AppRow(
            app = update.app,
            onClick = { actions.onAppClick(update.app) },
            style = AppRowStyle.Manage,
            installed = update.installedVersion,
            showAuthor = false,
            modifier = Modifier.testTag("update:$id"),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    why?.let { WhyButton(id, it) }
                    UpdateButton(update, current, actions)
                }
            },
        )
    }
}

/** The info mark on a manual update. Opens the sheet that says why Android will ask. */
@Composable
private fun WhyButton(appId: String, why: UpdateWhy) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.why_manual)
    IconButton(
        onClick = { open = true },
        modifier = Modifier
            .size(ZapSize.touchTarget)
            .semantics { contentDescription = description }
            .testTag("why:$appId"),
    ) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = ZapText3, modifier = Modifier.size(18.dp))
    }
    if (open) WhySheet(why) { open = false }
}

@Composable
private fun WhySheet(why: UpdateWhy, onDismiss: () -> Unit) {
    val title = when (why) {
        is UpdateWhy.Manual -> when (why.reason) {
            ManualReason.OldAndroid -> stringResource(R.string.manual_reason_old_android, Build.VERSION.RELEASE)
            ManualReason.Sideloaded -> stringResource(R.string.manual_reason_sideloaded)
            ManualReason.Adb -> stringResource(R.string.manual_reason_adb)
            ManualReason.UnknownInstaller -> stringResource(R.string.manual_reason_unknown)
        }
        is UpdateWhy.Store -> stringResource(R.string.installed_by_store, why.name)
    }
    val body = when (why) {
        is UpdateWhy.Manual -> when (why.reason) {
            ManualReason.OldAndroid -> stringResource(R.string.manual_reason_old_android_body)
            ManualReason.Sideloaded -> stringResource(R.string.manual_reason_sideloaded_body)
            ManualReason.Adb -> stringResource(R.string.manual_reason_adb_body)
            ManualReason.UnknownInstaller -> stringResource(R.string.manual_reason_unknown_body)
        }
        is UpdateWhy.Store -> stringResource(R.string.install_takeover_store, why.name)
    }
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5)
                .testTag("whySheet"),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, color = ZapText2, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * One pill per update, always [UpdateButtonWidth] wide, carrying that update's progress: Update,
 * Queued, a fill with the percent while downloading (tap to pause), Paused over the same fill (tap
 * to resume), a spinner while Zapstore verifies or installs, Confirm while Android's dialog waits,
 * and Retry after a failure (tap shows the error and starts again).
 */
@Composable
private fun UpdateButton(update: AvailableUpdate, task: InstallTask?, actions: RowActions) {
    val context = LocalContext.current
    val id = update.app.appId
    val phase = task?.phase
    val working = phase == TaskPhase.Verifying || phase == TaskPhase.Installing
    val transferring = phase == TaskPhase.Downloading || phase == TaskPhase.Queued
    val paused = phase == TaskPhase.Paused
    val failed = phase == TaskPhase.Failed
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
    val error = task?.error ?: stringResource(R.string.install_failed)
    val copy = stringResource(R.string.copy)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(UpdateButtonWidth)
            .height(ZapSize.control)
            .clip(UpdateButtonShape)
            .background(base)
            .clickable(enabled = !working, role = Role.Button) {
                when {
                    transferring -> actions.onPause(id)
                    failed -> {
                        Toasts.show(
                            Toast(error, ToastKind.Error, copy) {
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText(update.app.name, error))
                            },
                        )
                        actions.onUpdate(update)
                    }
                    else -> actions.onUpdate(update)
                }
            }
            .testTag("updateOne:$id"),
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
                phase == TaskPhase.Confirm -> stringResource(R.string.confirm)
                failed -> stringResource(R.string.retry)
                working || transferring -> null
                else -> stringResource(R.string.update_app)
            }
            label?.let { Text(it, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        }
    }
}

private val UpdateButtonShape = RoundedCornerShape(ZapRadius.full)
private val UpdateButtonWidth = 92.dp

@Composable
private fun OtherInstalledRow(installed: InstalledApp, onUninstall: () -> Unit) {
    SwipeAction(
        label = stringResource(R.string.uninstall),
        icon = R.drawable.ic_trash,
        background = ZapDangerFill,
        foreground = ZapOnDangerFill,
        onAction = onUninstall,
        modifier = Modifier.testTag("uninstall:${installed.packageId}"),
    ) {
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
