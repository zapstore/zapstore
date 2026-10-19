package dev.zapstore.app.screens

import dev.zapstore.app.SyncInterval
import dev.zapstore.app.components.ZapTextLink
import dev.zapstore.app.components.shortDisplayName
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.zapstore.app.R
import dev.zapstore.app.UpdateInterval
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.app.components.EvidenceText
import dev.zapstore.app.components.GroupHeader
import dev.zapstore.app.AppConfig
import dev.zapstore.app.BuildConfig
import dev.zapstore.app.components.SettingsGroup
import dev.zapstore.app.components.SettingsRow
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.SwitchRow
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapSegmented
import dev.zapstore.app.transport.NetworkMode
import dev.zapstore.app.transport.TorRuntimeState
import dev.zapstore.iolite.toNpub
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onNetworkModeChange: (NetworkMode) -> Unit,
    onSync: () -> Unit,
    onWipe: () -> Unit,
    modifier: Modifier = Modifier,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onUpdateIntervalChange: (UpdateInterval) -> Unit = {},
    onSyncIntervalChange: (SyncInterval) -> Unit = {},
    onAnyNetworkChange: (Boolean) -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onBugReportsChange: (Boolean) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onBackUpKey: () -> Unit = {},
    onRestoreFromSigner: () -> Unit = {},
    onCopyKey: () -> Unit = {},
    onOpenPaste: () -> Unit = {},
    onClosePaste: () -> Unit = {},
    onSubmitPaste: (String) -> Unit = {},
    onConfirmReplace: () -> Unit = {},
    onDismissReplace: () -> Unit = {},
    onOpenWallet: () -> Unit = {},
    onCloseWallet: () -> Unit = {},
    onConnectWallet: (String) -> Unit = {},
    onDisconnectWallet: () -> Unit = {},
    onRemoveTrustedSigner: (String) -> Unit = {},
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = ZapSpacing.space4, end = ZapSpacing.space4, top = ZapSpacing.space2, bottom = ZapSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
    ) {
        Text(text = stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)

        GroupHeader(stringResource(R.string.account), Modifier.padding(top = ZapSpacing.space4))
        SettingsGroup(Modifier.testTag("account")) {
            val account = state.account
            if (account == null) {
                SettingsRow(
                    title = stringResource(R.string.sign_in_with_signer),
                    subtitle = stringResource(R.string.account_explainer),
                    onClick = if (state.signingIn) null else onSignIn,
                    modifier = Modifier.testTag("signIn"),
                )
            } else {
                SettingsRow(
                    title = account.pubkey.toNpub().let { "${it.take(12)}…${it.takeLast(6)}" },
                    subtitle = stringResource(R.string.signed_in_with, account.signerPackage),
                )
                HorizontalDivider(color = ZapLine)
                SettingsRow(
                    title = stringResource(R.string.sign_out),
                    onClick = onSignOut,
                    trailing = null,
                    modifier = Modifier.testTag("signOut"),
                )
            }
        }
        state.accountError?.let { StatusText(it) }

        GroupHeader(stringResource(R.string.wallet), Modifier.padding(top = ZapSpacing.space4))
        SettingsGroup(Modifier.testTag("wallet")) {
            if (state.wallet.connected) {
                SettingsRow(
                    title = stringResource(R.string.wallet),
                    subtitle = stringResource(R.string.wallet_connected),
                    trailing = null,
                    modifier = Modifier.testTag("walletConnected"),
                )
                HorizontalDivider(color = ZapLine)
                SettingsRow(
                    title = stringResource(R.string.wallet_disconnect),
                    onClick = onDisconnectWallet,
                    trailing = null,
                    modifier = Modifier.testTag("disconnectWallet"),
                )
            } else {
                SettingsRow(
                    title = stringResource(R.string.wallet_connect),
                    subtitle = stringResource(R.string.wallet_none),
                    onClick = onOpenWallet,
                    modifier = Modifier.testTag("connectWallet"),
                )
            }
        }

        GroupHeader(stringResource(R.string.device_key), Modifier.padding(top = ZapSpacing.space4))
        StatusText(stringResource(R.string.device_key_explainer))
        SettingsGroup(Modifier.testTag("deviceKey")) {
            val key = state.key
            SettingsRow(
                title = key.npub.let { if (it.length > 20) "${it.take(12)}…${it.takeLast(6)}" else it },
                subtitle = stringResource(R.string.device_key_subtitle),
                trailing = null,
            )
            if (state.account != null) {
                HorizontalDivider(color = ZapLine)
                SettingsRow(
                    title = stringResource(R.string.back_up_key),
                    subtitle = stringResource(R.string.back_up_key_hint),
                    onClick = if (key.busy) null else onBackUpKey,
                    modifier = Modifier.testTag("backUpKey"),
                )
                HorizontalDivider(color = ZapLine)
                SettingsRow(
                    title = stringResource(R.string.restore_from_signer),
                    onClick = if (key.busy) null else onRestoreFromSigner,
                    modifier = Modifier.testTag("restoreFromSigner"),
                )
            }
            HorizontalDivider(color = ZapLine)
            SettingsRow(
                title = stringResource(R.string.copy_key),
                subtitle = stringResource(R.string.copy_key_hint),
                onClick = onCopyKey,
                trailing = null,
                modifier = Modifier.testTag("copyKey"),
            )
            HorizontalDivider(color = ZapLine)
            SettingsRow(
                title = stringResource(R.string.paste_key),
                onClick = onOpenPaste,
                modifier = Modifier.testTag("pasteKey"),
            )
        }
        when {
            state.key.busy -> StatusText(stringResource(R.string.key_working), Modifier.testTag("keyStatus"))
            state.key.message != null -> StatusText(state.key.message, Modifier.testTag("keyStatus"))
            state.account == null -> StatusText(stringResource(R.string.key_sign_in_to_back_up))
        }

        GroupHeader(stringResource(R.string.network), Modifier.padding(top = ZapSpacing.space4))
        ZapSegmented(
            options = listOf(NetworkMode.TorOnly, NetworkMode.DirectOnly),
            selected = state.networkMode,
            onSelect = onNetworkModeChange,
            label = { stringResource(if (it == NetworkMode.TorOnly) R.string.network_tor else R.string.network_direct) },
            tag = { if (it == NetworkMode.TorOnly) "networkTor" else "networkDirect" },
        )
        StatusText(networkStatusText(state.networkMode, state.torState), Modifier.testTag("networkStatus"))

        GroupHeader(stringResource(R.string.updates), Modifier.padding(top = ZapSpacing.space4))
        Text(stringResource(R.string.background_updates), style = MaterialTheme.typography.titleSmall)
        ZapSegmented(
            options = UpdateInterval.entries,
            selected = state.deviceState.backgroundUpdateInterval,
            onSelect = onUpdateIntervalChange,
            label = { stringResource(it.labelRes()) },
            tag = { "updateInterval:${it.name}" },
        )
        StatusText(stringResource(R.string.background_updates_hint))
        SettingsGroup {
            SwitchRow(
                title = stringResource(R.string.background_any_network),
                subtitle = stringResource(R.string.background_any_network_hint),
                checked = state.deviceState.backgroundAutoUpdatesAnyNetwork,
                onCheckedChange = onAnyNetworkChange,
                enabled = state.deviceState.backgroundAutoUpdates,
                modifier = Modifier.testTag("backgroundAnyNetwork"),
            )
        }

        val trusted = state.deviceState.trustedSigners
        if (trusted.isNotEmpty()) {
            GroupHeader(stringResource(R.string.trusted_developers), Modifier.padding(top = ZapSpacing.space4))
            StatusText(stringResource(R.string.trusted_developers_hint))
            SettingsGroup(Modifier.testTag("trustedSigners")) {
                trusted.forEachIndexed { index, pubkey ->
                    if (index > 0) HorizontalDivider(color = ZapLine)
                    val npub = pubkey.toNpub()
                    SettingsRow(
                        title = shortDisplayName(pubkey, state.trustedProfiles[pubkey]),
                        subtitle = "${npub.take(12)}…${npub.takeLast(6)}",
                        trailing = {
                            ZapTextLink(
                                stringResource(R.string.remove),
                                { onRemoveTrustedSigner(pubkey) },
                                Modifier.testTag("untrust:$pubkey"),
                            )
                        },
                        modifier = Modifier.testTag("trusted:$pubkey"),
                    )
                }
            }
        }

        GroupHeader(stringResource(R.string.catalog), Modifier.padding(top = ZapSpacing.space4))
        Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1)) {
            EvidenceText(state.catalogUrl, color = ZapTextSecondary)
            StatusText(stringResource(R.string.catalog_apps, state.appCount))
            StatusText(stringResource(R.string.catalog_epoch, state.catalogEpoch))
            state.status.lastSyncedAtMillis?.let { syncedAt ->
                StatusText(lastSyncRelativeText(syncedAt), Modifier.testTag("lastSyncAgo"))
            }
            if (state.status.lastSyncNotModified) {
                StatusText(stringResource(R.string.last_sync_not_modified), Modifier.testTag("lastSyncNotModified"))
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
                    stringResource(R.string.last_sync_duration, String.format(Locale.getDefault(), "%.2f", durationMs / 1000.0)),
                    Modifier.testTag("lastSyncDuration"),
                )
            }
            EvidenceText(
                stringResource(R.string.local_storage, Formatter.formatFileSize(context, state.localStorageBytes)),
                color = ZapTextSecondary,
                modifier = Modifier.testTag("localStorage"),
            )
            state.status.error?.let { StatusText(it, Modifier.testTag("catalogError")) }
        }
        Text(stringResource(R.string.auto_sync), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = ZapSpacing.space2))
        ZapSegmented(
            options = SyncInterval.entries,
            selected = state.deviceState.syncInterval,
            onSelect = onSyncIntervalChange,
            label = { stringResource(it.labelRes()) },
            tag = { "syncInterval:${it.name}" },
        )
        StatusText(stringResource(R.string.auto_sync_hint))
        Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2), modifier = Modifier.padding(top = ZapSpacing.space2)) {
            ZapButton(
                text = stringResource(R.string.sync_now),
                onClick = onSync,
                style = ZapButtonStyle.Tonal,
                loading = state.status.syncing,
                modifier = Modifier.testTag("syncCatalog"),
            )
            ZapButton(
                text = stringResource(R.string.clear_local_storage),
                onClick = onWipe,
                style = ZapButtonStyle.Danger,
                enabled = !state.status.syncing,
                modifier = Modifier.testTag("clearLocalStorage"),
            )
        }

        GroupHeader(stringResource(R.string.about), Modifier.padding(top = ZapSpacing.space4))
        SettingsGroup(Modifier.testTag("about")) {
            SettingsRow(
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                trailing = null,
                modifier = Modifier.testTag("appVersion"),
            )
            HorizontalDivider(color = ZapLine)
            SettingsRow(
                title = stringResource(R.string.source_code),
                subtitle = AppConfig.SOURCE_URL.removePrefix("https://"),
                onClick = { onOpenUrl(AppConfig.SOURCE_URL) },
                modifier = Modifier.testTag("sourceCode"),
            )
            HorizontalDivider(color = ZapLine)
            SettingsRow(
                title = stringResource(R.string.diagnostics),
                subtitle = stringResource(R.string.diagnostics_hint),
                onClick = onOpenDiagnostics,
                modifier = Modifier.testTag("openDiagnostics"),
            )
            HorizontalDivider(color = ZapLine)
            SwitchRow(
                title = stringResource(R.string.bug_reports),
                subtitle = stringResource(R.string.bug_reports_hint),
                checked = state.deviceState.bugReports,
                onCheckedChange = onBugReportsChange,
                modifier = Modifier.testTag("bugReports"),
            )
        }
    }
    if (state.key.pasting) PasteKeySheet(state.key.pasteError, onSubmitPaste, onClosePaste)
    if (state.wallet.pasting) {
        PasteSecretSheet(
            title = stringResource(R.string.wallet_paste_title),
            body = stringResource(R.string.wallet_paste_body),
            placeholder = "nostr+walletconnect://…",
            tag = "walletPaste",
            error = state.wallet.pasteError,
            onSubmit = onConnectWallet,
            onDismiss = onCloseWallet,
        )
    }
    if (state.key.confirmReplace) ReplaceKeySheet(onConfirmReplace, onDismissReplace)
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
}

private fun SyncInterval.labelRes(): Int = when (this) {
    SyncInterval.FiveMinutes -> R.string.interval_5m
    SyncInterval.FifteenMinutes -> R.string.interval_15m
    SyncInterval.Hourly -> R.string.interval_1h
    SyncInterval.SixHours -> R.string.interval_6h
    SyncInterval.TwelveHours -> R.string.interval_12h
    SyncInterval.Daily -> R.string.interval_24h
}

private fun UpdateInterval.labelRes(): Int = when (this) {
    UpdateInterval.Never -> R.string.interval_never
    UpdateInterval.Every6Hours -> R.string.interval_6h
    UpdateInterval.Every12Hours -> R.string.interval_12h
    UpdateInterval.Daily -> R.string.interval_daily
    UpdateInterval.Weekly -> R.string.interval_weekly
}
