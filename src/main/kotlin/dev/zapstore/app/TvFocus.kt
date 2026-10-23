package dev.zapstore.app

import android.content.pm.PackageManager
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.ripple
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

/**
 * Android TV support for a UI driven by a remote's D-pad rather than touch.
 *
 * Material's focus state is a faint tint meant for a keyboard user at arm's
 * length; from a sofa it is invisible, so a remote user cannot tell which
 * button OK will press. On a leanback device every clickable gets a solid ring
 * when focused, and Material components a much stronger focus overlay.
 * Phones and tablets are unchanged.
 */
val LocalIsTv = staticCompositionLocalOf { false }

/** Whether this device is a TV (leanback) — a remote, not a touchscreen. */
@Composable
fun rememberIsTv(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}

/** Provides [LocalIsTv] and, on a TV, the focus ring and focus overlay. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvideTvFocus(content: @Composable () -> Unit) {
    val tv = rememberIsTv()
    if (!tv) {
        CompositionLocalProvider(LocalIsTv provides false, content = content)
        return
    }
    val ring = ZapActionText
    CompositionLocalProvider(
        LocalIsTv provides true,
        // Plain clickables (app and stack cards, rows) draw through
        // LocalIndication: a ring around them, on top of the usual ripple.
        LocalIndication provides FocusRingIndication(ring),
        // Material components (buttons, chips, switches) draw their own
        // ripple; raise its focused overlay from ~10% to clearly visible.
        LocalRippleConfiguration provides RippleConfiguration(
            rippleAlpha = RippleAlpha(
                draggedAlpha = 0.16f,
                focusedAlpha = 0.45f,
                hoveredAlpha = 0.08f,
                pressedAlpha = 0.24f,
            ),
        ),
        content = content,
    )
}

/**
 * On a TV, move focus to the first focusable element once [key] settles — a
 * dialog or screen that opens with nothing focused costs a remote user a
 * wasted key press, and they cannot see where focus went. No-op on touch.
 */
@Composable
fun TvAutoFocus(key: Any?) {
    if (!LocalIsTv.current) return
    val focusManager = LocalFocusManager.current
    LaunchedEffect(key) {
        awaitFrame()
        focusManager.moveFocus(FocusDirection.Next)
    }
}

/**
 * On a TV, focus this element once it first shows — a dialog's primary button, or the
 * first control of a screen, so OK acts without first hunting for focus. Once per
 * screen: coming back to it does not pull focus again. No-op on touch.
 */
fun Modifier.tvInitialFocus(): Modifier = composed {
    val requester = remember { FocusRequester() }
    var done by rememberSaveable { mutableStateOf(false) }
    if (LocalIsTv.current && !done) {
        LaunchedEffect(requester) {
            awaitFrame()
            if (runCatching { requester.requestFocus() }.isSuccess) done = true
        }
    }
    focusRequester(requester)
}

/** What a [TvScreen] remembers: the key of the element that last had focus. */
class TvFocusMemory internal constructor(private val saved: MutableState<String?>) {
    internal val requesters = mutableMapOf<String, FocusRequester>()
    internal var key: String?
        get() = saved.value
        set(value) { saved.value = value }
}

val LocalTvFocusMemory = staticCompositionLocalOf<TvFocusMemory?> { null }

/**
 * A screen on a TV. Focus moves into it once it shows, so the first press acts on the
 * screen rather than the rail; on return it goes back to the element that had it (the
 * app card that was opened, via [tvRememberFocus]). Content that is still loading gets
 * up to two seconds to produce something focusable. Plain [Box] on touch.
 */
@Composable
fun TvScreen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (!LocalIsTv.current) {
        Box(modifier) { content() }
        return
    }
    val saved = rememberSaveable { mutableStateOf<String?>(null) }
    val memory = remember { TvFocusMemory(saved) }
    val requester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(FOCUS_ATTEMPTS) { attempt ->
            awaitFrame()
            if (hasFocus) return@LaunchedEffect
            // Prefer the remembered element; after half the attempts settle for any.
            val remembered = memory.key?.let(memory.requesters::get)
            val target = remembered ?: requester.takeIf { memory.key == null || attempt >= FOCUS_ATTEMPTS / 2 }
            target?.let { runCatching { it.requestFocus() } }
            delay(FOCUS_RETRY_MS)
        }
    }
    Box(
        modifier
            .focusRequester(requester)
            .onFocusChanged { hasFocus = it.hasFocus }
            .focusGroup(),
    ) {
        CompositionLocalProvider(LocalTvFocusMemory provides memory, content = content)
    }
}

/** Inside a [TvScreen]: remember this element under [key] when it is focused, for return. */
fun Modifier.tvRememberFocus(key: String): Modifier = composed {
    val memory = LocalTvFocusMemory.current ?: return@composed this
    val requester = remember { FocusRequester() }
    DisposableEffect(memory, key) {
        memory.requesters[key] = requester
        onDispose { memory.requesters.remove(key, requester) }
    }
    focusRequester(requester).onFocusChanged { if (it.isFocused) memory.key = key }
}

private const val FOCUS_ATTEMPTS = 20
private const val FOCUS_RETRY_MS = 100L

private data class FocusRingIndication(val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusRingNode(interactionSource, color)
}

private class FocusRingNode(
    private val source: InteractionSource,
    private val color: Color,
) : DelegatingNode(), DrawModifierNode {
    private var focused = 0

    init {
        delegate(ripple().create(source))
    }

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect {
                when (it) {
                    is FocusInteraction.Focus -> focused++
                    is FocusInteraction.Unfocus -> focused = (focused - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused == 0) return
        // Inside the edge: cards clip to their shape before `clickable`, so a ring
        // drawn outside the bounds would be cut away.
        val width = 3.dp.toPx()
        val half = width / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(half, half),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius((ZapRadius.lg.toPx() - half).coerceAtLeast(0f)),
            style = Stroke(width),
        )
    }
}
