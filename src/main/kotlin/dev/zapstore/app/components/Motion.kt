package dev.zapstore.app.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset

/** DESIGN.md motion: fast for state, base for movement, slow for sheets and progress. No bounce. */
object ZapMotion {
    const val FAST = 140
    const val BASE = 220
    const val SLOW = 360
    val EaseOut = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val Expo = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    fun <T> fast(): FiniteAnimationSpec<T> = tween(FAST, easing = EaseOut)
    fun <T> base(): FiniteAnimationSpec<T> = tween(BASE, easing = Expo)
}

/** Android's "Remove animations" sets the animator scale to 0; scroll-linked effects stop with it. */
fun Context.animationsEnabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.animationsEnabled() }
}

/** Pressed things sink slightly. A transform, so layout never moves. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.98f): Modifier {
    if (!animationsEnabled()) return this
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, ZapMotion.fast(), label = "pressScale")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Rows fade in and glide to their new place when a list changes, as search results do while typing. */
@Composable
fun LazyItemScope.listItemMotion(): Modifier =
    if (animationsEnabled()) {
        Modifier.animateItem(
            fadeInSpec = ZapMotion.base(),
            placementSpec = ZapMotion.base<IntOffset>(),
            fadeOutSpec = ZapMotion.fast(),
        )
    } else {
        Modifier
    }

/**
 * The first list item drifts up at [factor] of the scroll speed and fades as it leaves.
 * State is read in the draw phase, so scrolling does not recompose.
 */
@Composable
fun Modifier.scrollParallax(state: LazyListState, index: Int = 0, factor: Float = 0.35f): Modifier {
    if (!animationsEnabled()) return this
    return graphicsLayer {
        if (state.firstVisibleItemIndex != index) return@graphicsLayer
        val offset = state.firstVisibleItemScrollOffset.toFloat()
        translationY = offset * factor
        alpha = (1f - offset / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }
}

/**
 * Window parallax for an image inside a clipped frame in a horizontal list: the image drifts
 * against the scroll by a fraction of its distance from the viewport center.
 */
@Composable
fun Modifier.windowParallax(state: LazyListState, key: Any, depth: Float = 0.08f): Modifier {
    if (!animationsEnabled()) return this
    return graphicsLayer {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@graphicsLayer
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        val itemCenter = item.offset + item.size / 2f
        val scale = 1f + depth * 2
        scaleX = scale
        scaleY = scale
        val room = size.width * depth
        translationX = (-(itemCenter - viewportCenter) * depth).coerceIn(-room, room)
    }
}
