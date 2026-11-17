package dev.zapstore.app.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.zapstore.app.R
import dev.zapstore.app.ZapAction
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.catalog.CatalogGate
import dev.zapstore.app.catalog.SyncPhase
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.animationsEnabled
import dev.zapstore.app.transport.NetworkMode
import kotlinx.coroutines.delay

private const val SLOW_LOAD_MS = 15_000L

/**
 * What the app shows while it has no app data: the catalog loading, failing, or missing. It is laid out
 * like an onboarding step, since for a first install it is the step after the last one. It carries only
 * what can fix a first load: how to connect, starting over, and the diagnostics.
 */
@Composable
fun CatalogGateScreen(
    ui: CatalogGateUi,
    onNetworkModeChange: (NetworkMode) -> Unit,
    onRetry: () -> Unit,
    onClearAndRetry: () -> Unit,
    onRestore: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    onContinue: () -> Unit = {},
) {
    val gate = ui.gate
    val none = gate is CatalogGate.NoCatalog
    val failed = gate is CatalogGate.Failed
    // The phone has the catalog the APK came with and is fetching what is newer. Starting over would throw that away.
    val catchUp = (gate as? CatalogGate.Loading)?.catchUp == true || (gate as? CatalogGate.Failed)?.catchUp == true
    // A sync a background job started can be stopped with nothing to replace it, and a slow one can hold the
    // phone for minutes. After a while the way on shows even without a failure.
    val loading = gate is CatalogGate.Loading
    var waitedLong by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        waitedLong = false
        if (loading) {
            delay(SLOW_LOAD_MS)
            waitedLong = true
        }
    }
    val stalled = waitedLong && (gate as? CatalogGate.Loading)?.phase.let { it == SyncPhase.Idle || it == SyncPhase.Connecting }
    Column(
        modifier
            .fillMaxSize()
            .background(ZapCanvas)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = ZapSpacing.space6, vertical = ZapSpacing.space4)
            .testTag("catalogGate"),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space4),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = ZapSpacing.space8, bottom = ZapSpacing.space4),
        ) {
            Text(
                stringResource(
                    when {
                        none -> R.string.gate_none_title
                        catchUp && failed -> R.string.gate_catchup_failed_title
                        catchUp -> R.string.gate_catchup_title
                        failed -> R.string.gate_failed_title
                        else -> R.string.gate_title
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
            StepBody(
                stringResource(
                    when {
                        none -> R.string.gate_none_body
                        catchUp && failed -> R.string.gate_catchup_failed_body
                        catchUp -> R.string.gate_catchup_body
                        failed -> R.string.gate_failed_body
                        else -> R.string.gate_body
                    },
                ),
            )
            when (gate) {
                is CatalogGate.Loading -> Progress(gate, ui.networkMode)
                is CatalogGate.Failed -> StatusText(gate.error, Modifier.testTag("gateError"))
                else -> Unit
            }
            if (none && ui.restoreFailed) {
                StatusText(stringResource(R.string.gate_restore_failed), Modifier.testTag("gateRestoreFailed"))
            }
            // The user's own choice, already selected. Small: it is a way out of a stuck download, not the point.
            NetworkPicker(
                stringResource(R.string.gate_network_label),
                ui.networkMode,
                onNetworkModeChange,
                Modifier.padding(top = ZapSpacing.space2),
                quiet = true,
            )
        }
        when {
            none -> ZapButton(
                text = stringResource(R.string.gate_restore),
                onClick = onRestore,
                loading = ui.restoring,
                modifier = Modifier.fillMaxWidth().testTag("gateRestore"),
            )
            failed || (stalled && !catchUp) -> ZapButton(
                text = stringResource(R.string.retry),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().testTag("gateRetry"),
            )
        }
        if (catchUp && (failed || waitedLong)) {
            ZapButton(
                text = stringResource(R.string.gate_catchup_continue),
                onClick = onContinue,
                style = ZapButtonStyle.Tonal,
                modifier = Modifier.fillMaxWidth().padding(top = ZapSpacing.space1).testTag("gateContinue"),
            )
        }
        if (!none && !catchUp) {
            ZapButton(
                text = stringResource(R.string.gate_clear_retry),
                onClick = onClearAndRetry,
                style = if (failed) ZapButtonStyle.Ghost else ZapButtonStyle.Tonal,
                modifier = Modifier.fillMaxWidth().padding(top = ZapSpacing.space1).testTag("gateClear"),
            )
        }
        ZapButton(
            text = stringResource(R.string.gate_copy_diagnostics),
            onClick = onCopyDiagnostics,
            style = ZapButtonStyle.Ghost,
            modifier = Modifier.fillMaxWidth().padding(top = ZapSpacing.space1).testTag("gateDiagnostics"),
        )
    }
}

/** What the sync is doing, in words, with a bar that measures only when the bundle's length is known. */
@Composable
private fun Progress(gate: CatalogGate.Loading, mode: NetworkMode) {
    val context = LocalContext.current
    val status = when (gate.phase) {
        SyncPhase.Downloading -> stringResource(R.string.gate_downloading)
        SyncPhase.Importing -> stringResource(R.string.gate_importing)
        SyncPhase.Idle, SyncPhase.Connecting ->
            stringResource(if (mode.usesTor) R.string.gate_connecting_tor else R.string.gate_connecting)
    }
    val detail = if (gate.phase == SyncPhase.Downloading && gate.receivedBytes > 0) {
        val received = Formatter.formatShortFileSize(context, gate.receivedBytes)
        if (gate.totalBytes > 0) {
            stringResource(R.string.gate_downloading_progress, received, Formatter.formatShortFileSize(context, gate.totalBytes))
        } else {
            received
        }
    } else {
        null
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2), modifier = Modifier.testTag("gateProgress")) {
        val modifier = Modifier.fillMaxWidth()
        val fraction = gate.fraction
        when {
            fraction != null -> LinearProgressIndicator(progress = { fraction }, modifier = modifier, color = ZapAction, trackColor = ZapSurface3)
            // Reduced motion: a track with nothing moving on it. The words above still say what is happening.
            !animationsEnabled() -> LinearProgressIndicator(progress = { 0f }, modifier = modifier, color = ZapAction, trackColor = ZapSurface3)
            else -> LinearProgressIndicator(modifier = modifier, color = ZapAction, trackColor = ZapSurface3)
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, color = ZapText, modifier = Modifier.testTag("gateStatus"))
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = ZapText2, modifier = Modifier.testTag("gateDetail"))
        }
    }
}
