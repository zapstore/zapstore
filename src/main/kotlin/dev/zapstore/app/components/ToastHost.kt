package dev.zapstore.app.components

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.Toast
import dev.zapstore.app.ToastKind
import dev.zapstore.app.Toasts
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The latest [Toasts] message, centered under the status bar. It gets its own window, opened
 * after any sheet, so it shows above it; the window takes no focus and lets touches through.
 * A newer message replaces the current one. A sticky one waits for its close button.
 */
@Composable
fun ToastHost() {
    var current by remember { mutableStateOf<Toast?>(null) }
    var shown by remember { mutableStateOf<Toast?>(null) }
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) {
        Toasts.flow.collectLatest { toast ->
            current = toast
            shown = toast
            if (toast.sticky) return@collectLatest
            delay(if (toast.action != null) ACTION_MS else SHORT_MS)
            current = null
        }
    }
    visible.targetState = current != null
    val top = with(LocalDensity.current) { ZapSpacing.space2.roundToPx() }
    val toast = shown ?: return
    if (!visible.currentState && !visible.targetState) return
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.run {
                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                val width = if (toast.wide) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
                setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                attributes = attributes.apply {
                    y = top
                    windowAnimations = 0
                }
            }
        }
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically(ZapMotion.base()) { -it } + fadeIn(ZapMotion.base()),
            exit = slideOutVertically(ZapMotion.fast()) { -it } + fadeOut(ZapMotion.fast()),
        ) {
            ToastCard(toast) { current = null }
        }
    }
}

@Composable
private fun ToastCard(toast: Toast, onDone: () -> Unit) {
    val style = toast.kind.style()
    val close = stringResource(R.string.close)
    Surface(
        shape = RoundedCornerShape(TOAST_RADIUS),
        color = style.background,
        contentColor = style.content,
        shadowElevation = 12.dp,
        modifier = Modifier
            .padding(horizontal = ZapSpacing.space4)
            .widthIn(max = 520.dp)
            .then(if (toast.wide) Modifier.fillMaxWidth() else Modifier)
            .semantics { liveRegion = if (toast.kind == ToastKind.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite }
            .testTag("toast"),
    ) {
        Column(
            modifier = Modifier.padding(
                start = ZapSpacing.space4,
                end = if (toast.sticky) ZapSpacing.space1 else ZapSpacing.space4,
                top = if (toast.sticky) ZapSpacing.space1 else ZapSpacing.space3,
                bottom = if (toast.action != null) ZapSpacing.space1 else ZapSpacing.space3,
            ),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
                // Level with the message's first line, which the close button centers on when there is one.
                val lead = if (toast.sticky) 12.dp else 0.dp
                Icon(
                    painterResource(style.icon),
                    contentDescription = null,
                    tint = style.accent,
                    modifier = Modifier.padding(top = lead + 1.dp).size(22.dp),
                )
                Text(
                    toast.message,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = MESSAGE_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = lead).weight(1f, fill = toast.wide).testTag("toast:${toast.kind.name}"),
                )
                if (toast.sticky) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(ZapSize.touchTarget)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onDone)
                            .semantics { contentDescription = close }
                            .testTag("toastClose"),
                    ) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = style.content, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (toast.actionLabel != null && toast.action != null) {
                Text(
                    toast.actionLabel,
                    color = style.accent,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = if (toast.sticky) ZapSpacing.space2 else 0.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) {
                            toast.action.invoke()
                            if (!toast.sticky) onDone()
                        }
                        .padding(horizontal = ZapSpacing.space3, vertical = ZapSpacing.space3)
                        .testTag("toastAction"),
                )
            }
        }
    }
}

/** A message with a button or a close mark spans the screen; a bare one is a centered pill. */
private val Toast.wide: Boolean get() = sticky || action != null

private data class ToastStyle(val background: Color, val content: Color, val accent: Color, val icon: Int)

private fun ToastKind.style(): ToastStyle = when (this) {
    ToastKind.Success -> ToastStyle(Color(0xFF17432A), Color(0xFFE6F4EA), Color(0xFF6FD99A), R.drawable.ic_check)
    ToastKind.Info -> ToastStyle(Color(0xFF26262B), Color(0xFFECECEE), Color(0xFF7DB1F0), R.drawable.ic_info)
    ToastKind.Warning -> ToastStyle(Color(0xFF4F3D07), Color(0xFFFFF4D1), Color(0xFFF5C84C), R.drawable.ic_alert)
    ToastKind.Error -> ToastStyle(Color(0xFF561818), Color(0xFFFFE4E3), Color(0xFFFF8F8C), R.drawable.ic_alert)
}

private val TOAST_RADIUS = 20.dp
/** Copy carries the whole message; the card stops short of covering the screen. */
private const val MESSAGE_LINES = 8
private const val SHORT_MS = 4_000L
private const val ACTION_MS = 8_000L
