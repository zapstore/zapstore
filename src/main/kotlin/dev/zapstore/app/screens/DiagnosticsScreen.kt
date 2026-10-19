package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zapstore.app.AppLog
import dev.zapstore.app.R
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The local log, newest last. Null while it is being read. */
class DiagnosticsViewModel : ViewModel() {
    private val _log = MutableStateFlow<String?>(null)
    val log: StateFlow<String?> = _log

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch { _log.value = withContext(Dispatchers.IO) { readTail() } }
    }

    fun clear() {
        AppLog.clear()
        _log.value = ""
    }

    private fun readTail(): String {
        val text = AppLog.files().joinToString("") { it.readText() }
        return if (text.length <= MAX_CHARS) text else text.substring(text.indexOf('\n', text.length - MAX_CHARS) + 1)
    }

    private companion object {
        /** Keeps the share intent well under the binder transaction limit. */
        const val MAX_CHARS = 200_000
    }
}

@Composable
fun DiagnosticsScreen(
    log: String?,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(log) { if (log != null) scroll.scrollTo(scroll.maxValue) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = ZapSpacing.space4, end = ZapSpacing.space4, top = ZapSpacing.space2, bottom = ZapSpacing.space4),
        verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
    ) {
        Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.diagnostics_hint), color = ZapTextSecondary, style = MaterialTheme.typography.bodyMedium)
        when {
            log == null -> LoadingIndicator()
            log.isBlank() -> EmptyRow(stringResource(R.string.diagnostics_empty))
            else -> SelectionContainer(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ZapRadius.md))
                    .background(ZapSurface1),
            ) {
                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall,
                    color = ZapTextSecondary,
                    modifier = Modifier
                        .verticalScroll(scroll)
                        .horizontalScroll(rememberScrollState())
                        .padding(ZapSpacing.space3)
                        .testTag("diagnosticsLog"),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
            val ready = !log.isNullOrBlank()
            ZapButton(
                text = stringResource(R.string.share_logs),
                onClick = { log?.let(onShare) },
                enabled = ready,
                modifier = Modifier.testTag("shareLogs"),
            )
            ZapButton(
                text = stringResource(R.string.copy),
                onClick = { log?.let(onCopy) },
                style = ZapButtonStyle.Tonal,
                enabled = ready,
                modifier = Modifier.testTag("copyLogs"),
            )
            ZapButton(
                text = stringResource(R.string.clear_logs),
                onClick = onClear,
                style = ZapButtonStyle.Danger,
                enabled = ready,
                modifier = Modifier.testTag("clearLogs"),
            )
        }
    }
}
