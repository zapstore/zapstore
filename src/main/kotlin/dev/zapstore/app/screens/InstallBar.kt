package dev.zapstore.app.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.Toast
import dev.zapstore.app.ToastKind
import dev.zapstore.app.Toasts
import dev.zapstore.app.ZapAction
import dev.zapstore.app.ZapDangerFill
import dev.zapstore.app.ZapOnAction
import dev.zapstore.app.ZapOnDangerFill
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapButtonShape
import dev.zapstore.app.ZapSurface4
import dev.zapstore.app.ZapText
import dev.zapstore.iolite.AppRecord
import java.util.Locale

/** What the bottom bar can ask the page to do. */
data class InstallActions(
    val onInstall: () -> Unit = {},
    val onPause: () -> Unit = {},
    /** Ends the install at any stage and removes what it downloaded. */
    val onCancel: () -> Unit = {},
    /** Resumes a paused download, or shows Android's waiting dialog again. */
    val onStart: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onOpen: () -> Unit = {},
    val onUninstall: () -> Unit = {},
    /** Asks, then uninstalls the copy signed by another key and installs the listed one. */
    val onReinstall: () -> Unit = {},
)

/**
 * The app page's fixed bottom bar: one wide button whose label follows the installer — queued,
 * download (tap to pause, tap again to resume), verify, install, Android's dialog waiting for the
 * user (tap to show it again), and failures — with Open beside an update and Uninstall beside any
 * installed app at rest; while an install is in progress a cancel X takes Uninstall's place. No background of its own.
 */
@Composable
fun InstallBar(app: AppRecord, install: InstallUi, actions: InstallActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val button = install.button(
        onError = { error ->
            Toasts.show(
                Toast(error, ToastKind.Error, context.getString(R.string.copy)) {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(app.name, error))
                },
            )
        },
        actions = actions,
    ) ?: return
    val atRest = install.phase == InstallPhase.Idle && (install.installed || install.updating)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp).testTag("installBar"),
    ) {
        Box(Modifier.weight(1f).height(BUTTON_HEIGHT)) {
            when (button) {
                is BarButton.Simple -> SimpleButton(button)
                is BarButton.Progress -> ProgressButton(button)
            }
        }
        if (atRest) {
            if (install.updating) {
                BarIconButton(R.drawable.ic_external, stringResource(R.string.open_app), ZapAction, ZapOnAction, actions.onOpen, "openApp")
            }
            BarIconButton(R.drawable.ic_trash, stringResource(R.string.uninstall), ZapDangerFill, ZapOnDangerFill, actions.onUninstall, "uninstallIcon")
        } else if (install.cancellable) {
            BarIconButton(R.drawable.ic_close, stringResource(R.string.install_cancel), ZapDangerFill, ZapOnDangerFill, actions.onCancel, "cancelInstall")
        }
    }
}

private enum class Tone { Action, Error }

private sealed interface BarButton {
    data class Simple(
        val text: String,
        val onClick: (() -> Unit)?,
        val tone: Tone = Tone.Action,
        val spinner: Boolean = false,
        val tag: String = "installButton",
    ) : BarButton

    data class Progress(val text: String, val fraction: Float, val paused: Boolean, val onClick: () -> Unit) : BarButton
}

@Composable
private fun InstallUi.button(
    onError: (String) -> Unit,
    actions: InstallActions,
): BarButton? {
    val installedHere = installed || updating
    return when (phase) {
        InstallPhase.Queued -> BarButton.Simple(stringResource(R.string.install_queued), null, spinner = true)
        InstallPhase.Downloading -> BarButton.Progress(progressText(), fraction, paused = false, onClick = actions.onPause)
        InstallPhase.Paused ->
            BarButton.Progress(stringResource(R.string.install_paused, progressText()), fraction, paused = true, onClick = actions.onStart)
        InstallPhase.Verifying -> BarButton.Simple(stringResource(R.string.install_verifying), null, spinner = true)
        InstallPhase.Installing -> BarButton.Simple(
            stringResource(
                when {
                    silent && installedHere -> R.string.install_updating
                    silent -> R.string.install_installing
                    installedHere -> R.string.install_requesting_update
                    else -> R.string.install_requesting
                },
            ),
            null,
            spinner = true,
        )
        InstallPhase.Confirm -> BarButton.Simple(
            stringResource(if (installedHere) R.string.confirm_update else R.string.install_notification),
            actions.onStart,
            tag = "confirmInstall",
        )
        InstallPhase.Failed -> if (needsReinstall) {
            BarButton.Simple(stringResource(R.string.reinstall), actions.onReinstall, Tone.Error, tag = "reinstall")
        } else {
            val error = error.orEmpty()
            BarButton.Simple(
                stringResource(R.string.install_error_details),
                {
                    onError(error)
                    actions.onDismissError()
                },
                Tone.Error,
            )
        }
        InstallPhase.Idle -> when {
            installed -> BarButton.Simple(stringResource(R.string.open_app), actions.onOpen, tag = "openApp")
            offer -> BarButton.Simple(stringResource(if (updating) R.string.update_app else R.string.install), actions.onInstall, tag = "installApp")
            else -> null
        }
    }
}

private val InstallUi.fraction: Float
    get() = total?.takeIf { it > 0 }?.let { (received.toFloat() / it).coerceIn(0f, 1f) } ?: 0f

@Composable
private fun InstallUi.progressText(): String {
    val size = total?.takeIf { it > 0 } ?: return stringResource(R.string.downloading)
    val percent = (received * 100 / size).toInt()
    val megabytes = String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
    return stringResource(R.string.install_progress, percent, megabytes)
}

@Composable
private fun SimpleButton(button: BarButton.Simple) {
    val (background, content) = when (button.tone) {
        Tone.Action -> ZapAction to ZapOnAction
        Tone.Error -> ZapDangerFill to ZapOnDangerFill
    }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(BUTTON_SHAPE)
            .background(background)
            .then(button.onClick?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier)
            .padding(horizontal = 16.dp)
            .testTag(button.tag),
    ) {
        Text(
            button.text,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (button.spinner) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ProgressButton(button: BarButton.Progress) {
    val base = if (button.paused) ZapSurface4 else ZapAction
    val fill = Color.Black.copy(alpha = if (button.paused) 0.35f else 0.22f).compositeOver(base)
    val content = if (button.paused) ZapText.copy(alpha = 0.85f) else ZapOnAction
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(BUTTON_SHAPE)
            .background(base)
            .clickable(role = Role.Button, onClick = button.onClick)
            .testTag(if (button.paused) "resumeDownload" else "pauseDownload"),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(button.fraction)
                .clip(BUTTON_SHAPE)
                .background(fill),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            if (button.paused) {
                Icon(painterResource(R.drawable.ic_pause), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(button.text, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BarIconButton(icon: Int, label: String, background: Color, content: Color, onClick: () -> Unit, tag: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(BUTTON_HEIGHT)
            .clip(BUTTON_SHAPE)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
    }
}

/** Room the list leaves under its last item so the bar never covers it. */
val INSTALL_BAR_SPACE = 56.dp

private val BUTTON_HEIGHT = ZapSize.button
private val BUTTON_SHAPE = ZapButtonShape
