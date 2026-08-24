package dev.zapstore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

private val BigIconSize = 84.dp
private val SmallIconSize = 26.dp
private val SmallHeaderHeight = 32.dp

@Composable
fun AppDetailScreen(
    state: AppDetailUiState,
    onOpenUrl: (String) -> Unit,
    repository: CatalogRepository? = null,
    onProfileClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedScreenshot by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val smallHeaderHeightPx = with(density) { SmallHeaderHeight.toPx() }
    var bigHeaderHeightPx by remember { mutableIntStateOf(with(density) { BigIconSize.roundToPx() }) }
    // 0f = big header fully expanded, 1f = collapsed into the compact top row
    val headerCollapseProgress by remember {
        derivedStateOf {
            val range = bigHeaderHeightPx - smallHeaderHeightPx
            if (range <= 0f) return@derivedStateOf 0f
            if (listState.firstVisibleItemIndex > 0) return@derivedStateOf 1f
            (listState.firstVisibleItemScrollOffset / range).coerceIn(0f, 1f)
        }
    }
    val bigHeaderHeight = with(density) { bigHeaderHeightPx.toDp() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ZapBackgroundGradient),
    ) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val app = state.app
        if (app == null) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ZapSurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.appLoading) {
                        LoadingIndicator()
                    } else {
                        Text(
                            text = stringResource(R.string.app_not_found),
                            color = ZapMuted,
                        )
                    }
                }
            }
            state.error?.let { error -> item { StatusText(error) } }
            return@LazyColumn
        }

        // Reserves the space occupied by the collapsing header overlay
        item { Spacer(Modifier.height(bigHeaderHeight)) }

        if (app.screenshots.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(app.screenshots.take(5), key = { it }) { url ->
                        AsyncImage(
                            model = cdnImageUrl(url, CdnImageVariant.ThumbnailSmall),
                            contentDescription = stringResource(R.string.screenshot_description, app.name),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(120.dp)
                                .height(200.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(ZapIconBackground)
                                .clickable {
                                    selectedScreenshot = app.screenshots.indexOf(url)
                                },
                        )
                    }
                }
            }
        }

        val description = app.event.content.ifBlank { app.summary }
        if (description.isNotBlank()) {
            item {
                MarkdownText(
                    value = description,
                    color = ZapMuted,
                    style = MaterialTheme.typography.bodyLarge,
                    onOpenUrl = onOpenUrl,
                    collapsible = true,
                )
            }
        }

        if (state.zapSummary.isLoading || state.zapSummary.zapCount > 0 || state.zapSummary.error != null) {
            item { ZapSummaryCard(state.zapSummary) }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                HorizontalDivider(Modifier.weight(1f), color = ZapOutline)
                Text(
                    text = stringResource(R.string.latest_release).uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                HorizontalDivider(Modifier.weight(1f), color = ZapOutline)
            }
        }

        state.release?.let { release ->
            item { VersionRow(release) }
            if (release.notes.isNotBlank()) {
                item {
                    MarkdownText(
                        value = release.notes,
                        color = ZapMuted,
                        style = MaterialTheme.typography.bodyLarge,
                        onOpenUrl = onOpenUrl,
                        collapsible = true,
                    )
                }
            }
        } ?: item {
            Box(
                Modifier
                    .width(220.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ZapSurfaceVariant),
            )
        }

        item {
            AppInfoCard(
                app = app,
                release = state.release,
                onOpenUrl = onOpenUrl,
                repository = repository,
                onProfileClick = { onProfileClick(app.event.pubKey) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        state.error?.let { error -> item { StatusText(error) } }
    }

    state.app?.let { app ->
        CollapsingAppHeader(
            app = app,
            release = state.release,
            authorName = rememberProfileDisplayName(app.event.pubKey, repository),
            progress = headerCollapseProgress,
            bigContentHeight = bigHeaderHeight,
            onBigContentMeasured = { if (headerCollapseProgress == 0f) bigHeaderHeightPx = it },
            onProfileClick = { onProfileClick(app.event.pubKey) },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
    }

    state.app?.let { app ->
        selectedScreenshot?.let { initialPage ->
            ScreenshotCarousel(
                screenshots = app.screenshots,
                initialPage = initialPage,
                appName = app.name,
                onDismiss = { selectedScreenshot = null },
            )
        }
    }
}

@Composable
private fun CollapsingAppHeader(
    app: AppInfo,
    release: ReleaseInfo?,
    authorName: String,
    progress: Float,
    bigContentHeight: Dp,
    onBigContentMeasured: (Int) -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundAlpha = (progress * 1.5f).coerceIn(0f, 1f)
    val secondaryAlpha = (1f - progress * 2f).coerceIn(0f, 1f)
    val nameStyle = MaterialTheme.typography.displaySmall
    val compactNameStyle = MaterialTheme.typography.titleMedium
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ZapBackground.copy(alpha = backgroundAlpha))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 4.dp)
            .height(lerp(bigContentHeight, SmallHeaderHeight, progress))
            .clipToBounds(),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .onSizeChanged { onBigContentMeasured(it.height) }
                .padding(horizontal = 16.dp),
        ) {
            AppIcon(
                title = app.name,
                iconUrl = app.iconUrl,
                modifier = Modifier.size(lerp(BigIconSize, SmallIconSize, progress)),
                cornerRadius = lerp(16.dp, 7.dp, progress),
            )
            Spacer(Modifier.width(lerp(16.dp, 10.dp, progress)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = nameStyle.copy(
                        fontSize = lerp(nameStyle.fontSize, compactNameStyle.fontSize, progress),
                        lineHeight = lerp(nameStyle.lineHeight, compactNameStyle.lineHeight, progress),
                    ),
                    maxLines = if (progress < 0.5f) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.app_by_author, authorName),
                    color = ZapMuted,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = (nameStyle.fontSize.value * 0.72f).sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = InterFontFamily,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .testTag("profile:${app.event.pubKey}")
                        .graphicsLayer { alpha = secondaryAlpha }
                        .clickable(onClick = onProfileClick),
                )
                if (release != null) {
                    Spacer(Modifier.height(10.dp))
                    VersionPill(
                        version = release.version,
                        modifier = Modifier.graphicsLayer { alpha = secondaryAlpha },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = ZapOutline.copy(alpha = progress))
    }
}

@Composable
private fun ScreenshotCarousel(
    screenshots: List<String>,
    initialPage: Int,
    appName: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialPage.coerceIn(0, screenshots.lastIndex),
            pageCount = { screenshots.size },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                AsyncImage(
                    model = cdnImageUrl(screenshots[page], CdnImageVariant.ThumbnailLarge),
                    contentDescription = stringResource(
                        R.string.screenshot_description,
                        appName,
                    ),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 48.dp),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 12.dp, end = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                )
            }
            Text(
                text = "${pagerState.currentPage + 1}/${screenshots.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
            )
        }
    }
}


@Composable
private fun ZapSummaryCard(summary: ZapSummaryUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ZapSurface.copy(alpha = 0.8f))
            .border(1.dp, ZapOutline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.support).uppercase(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = ZapMuted,
        )
        Spacer(Modifier.height(4.dp))
        if (summary.isLoading && summary.zapCount == 0) {
            Box(
                Modifier
                    .width(148.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ZapSurfaceVariant),
            )
        } else if (summary.zapCount == 0) {
            StatusText(summary.error.orEmpty())
        } else {
            Text(
                text = stringResource(
                    R.string.zap_summary,
                    NumberFormat.getInstance().format(summary.totalSats),
                    summary.zapCount,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}


@Composable
private fun VersionRow(release: ReleaseInfo) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ZapSurfaceVariant)
            .border(1.dp, ZapOutline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        StatusText(stringResource(R.string.version))
        Spacer(Modifier.width(6.dp))
        Text(
            text = release.version,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        StatusText("(${formatDate(release.event.createdAt)})")
    }
}


@Composable
private fun AppInfoCard(
    app: AppInfo,
    release: ReleaseInfo?,
    onOpenUrl: (String) -> Unit,
    repository: CatalogRepository?,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ZapSurface.copy(alpha = 0.8f))
            .border(1.dp, ZapOutline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        InfoRow(
            label = stringResource(R.string.source),
            value = app.repository ?: stringResource(R.string.not_available),
            link = app.repository,
            onOpenUrl = onOpenUrl,
        )
        app.license?.let {
            InfoRow(stringResource(R.string.license), it, onOpenUrl = onOpenUrl)
        }
        InfoRow(stringResource(R.string.app_id), app.identifier, onOpenUrl = onOpenUrl)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            StatusText(stringResource(R.string.author), Modifier.weight(1f))
            ProfileComponent(
                pubkey = app.event.pubKey,
                repository = repository,
                modifier = Modifier.weight(1f),
                onClick = onProfileClick,
            )
        }
        release?.let {
            InfoRow(
                stringResource(R.string.release_date),
                formatDate(it.event.createdAt),
                onOpenUrl = onOpenUrl,
            )
        }
    }
}


private fun formatDate(createdAt: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(createdAt * 1_000))

