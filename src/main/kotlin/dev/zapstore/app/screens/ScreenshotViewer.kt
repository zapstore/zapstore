package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import dev.zapstore.app.CdnImageVariant
import dev.zapstore.app.R
import dev.zapstore.app.ZapText3
import dev.zapstore.app.cdnImageUrl

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** The strip's small image and the viewer's placeholder share this key, so the viewer opens on what is already drawn. */
private fun smallShotKey(url: String) = "screenshot:" + cdnImageUrl(url, CdnImageVariant.ThumbnailSmall)

/** The small screenshot for the strip, cached under [smallShotKey]. */
@Composable
internal fun rememberStripShot(url: String): ImageRequest {
    val context = LocalPlatformContext.current
    return remember(url) {
        ImageRequest.Builder(context)
            .data(cdnImageUrl(url, CdnImageVariant.ThumbnailSmall))
            .memoryCacheKey(smallShotKey(url))
            .build()
    }
}

/**
 * Screenshots full screen. Each page opens on the strip's cached small image and swaps in the large one when
 * it arrives, and the original once zoomed. The image fills the width with no side gap; pinch or double-tap zooms, and while zoomed a drag
 * pans instead of changing page.
 */
@Composable
internal fun ScreenshotCarousel(screenshots: List<String>, initialPage: Int, appName: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialPage.coerceIn(0, screenshots.lastIndex),
            pageCount = { screenshots.size },
        )
        var zoomed by remember { mutableStateOf(false) }
        val context = LocalPlatformContext.current
        LaunchedEffect(pagerState.currentPage) { zoomed = false }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("screenshotViewer")) {
            HorizontalPager(state = pagerState, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                val url = screenshots[page]
                // Zooming in swaps the large variant for the original so text stays sharp.
                val sharp = zoomed && page == pagerState.currentPage
                val request = remember(url, sharp) {
                    ImageRequest.Builder(context)
                        .data(if (sharp) url else cdnImageUrl(url, CdnImageVariant.ThumbnailLarge))
                        .placeholderMemoryCacheKey(MemoryCache.Key(smallShotKey(url)))
                        .build()
                }
                ZoomBox(active = page == pagerState.currentPage, onZoomChange = { zoomed = it }) {
                    AsyncImage(
                        model = request,
                        contentDescription = stringResource(R.string.screenshot_description, appName),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close), tint = Color.White)
            }
            Text(
                "${pagerState.currentPage + 1}/${screenshots.size}",
                color = ZapText3,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
            )
        }
    }
}

/**
 * Pinch and double-tap zoom. One finger at normal size is left alone so the pager can take the swipe;
 * once zoomed, one finger pans, kept inside the image.
 */
@Composable
private fun ZoomBox(active: Boolean, onZoomChange: (Boolean) -> Unit, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        val next = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                        scale = next
                        offset = Offset.Zero
                        onZoomChange(next > 1f)
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.changes.size > 1 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val maxX = size.width * (scale - 1f) / 2f
                            val maxY = size.height * (scale - 1f) / 2f
                            offset = if (scale <= 1f) Offset.Zero else Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                            onZoomChange(scale > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    ) { content() }
}
