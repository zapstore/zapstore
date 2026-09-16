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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

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
    val app = state.app
    val authorPubkey = app?.let { rememberAppAuthor(it, repository) }
    val c1AuthorPubkey = app?.let { rememberC1Author(it, repository) }
    val authorProfile = authorPubkey?.let { rememberProfile(it, repository) }
    val showCompactHeader by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ZapCanvas),
    ) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (app == null) {
            item {
                if (state.appLoading) {
                    AppDetailSkeleton()
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(ZapSurface2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.app_not_found),
                            color = ZapTextSecondary,
                        )
                    }
                }
            }
            state.error?.let { error -> item { StatusText(error) } }
            return@LazyColumn
        }

        item {
            RegularAppHeader(
                app = app,
                release = state.release,
                repository = repository,
                authorPubkey = authorPubkey,
                authorProfile = authorProfile,
                onProfileClick = { authorPubkey?.let(onProfileClick) },
            )
        }

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
                                .background(ZapSurface3)
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
                    color = ZapTextSecondary,
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
                HorizontalDivider(Modifier.weight(1f), color = ZapLine)
                Text(
                    text = stringResource(R.string.latest_release).uppercase(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                HorizontalDivider(Modifier.weight(1f), color = ZapLine)
            }
        }

        state.release?.let { release ->
            item { VersionRow(release) }
            if (release.notes.isNotBlank()) {
                item {
                    MarkdownText(
                        value = release.notes,
                        color = ZapTextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        onOpenUrl = onOpenUrl,
                        collapsible = true,
                    )
                }
            }
        } ?: run {
            item {
                Box(
                    Modifier
                        .width(220.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ZapSurface2),
                )
            }
            item {
                ReleaseNotesSkeleton()
            }
        }

        item {
            AppInfoCard(
                app = app,
                release = state.release,
                onOpenUrl = onOpenUrl,
                repository = repository,
                authorPubkey = authorPubkey,
                c1AuthorPubkey = c1AuthorPubkey,
                authorProfile = authorProfile,
                onProfileClick = { authorPubkey?.let(onProfileClick) },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        state.error?.let { error -> item { StatusText(error) } }
    }

    if (showCompactHeader) {
        state.app?.let { app ->
            CompactAppHeader(
                app = app,
                repository = repository,
                authorPubkey = authorPubkey,
                authorProfile = authorProfile,
                onProfileClick = { authorPubkey?.let(onProfileClick) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
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
private fun AppDetailSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SkeletonBlock(74.dp, 74.dp, 16.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonBlock(width = 190.dp, height = 22.dp, cornerRadius = 6.dp)
                SkeletonBlock(width = 140.dp, height = 16.dp, cornerRadius = 6.dp)
            }
        }
        SkeletonBlock(
            width = 160.dp,
            height = 18.dp,
            cornerRadius = 6.dp,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(4) {
                SkeletonBlock(
                    width = 120.dp,
                    height = 200.dp,
                    cornerRadius = 16.dp,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(width = Dp.Infinity, height = 16.dp, cornerRadius = 5.dp)
            SkeletonBlock(width = Dp.Infinity, height = 16.dp, cornerRadius = 5.dp)
            SkeletonBlock(width = 180.dp, height = 16.dp, cornerRadius = 5.dp)
        }
        SkeletonBlock(width = 220.dp, height = 32.dp, cornerRadius = 8.dp)
        ReleaseNotesSkeleton()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ZapSurface1)
                .border(1.dp, ZapLine, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            repeat(4) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SkeletonBlock(width = 0.dp, height = 16.dp, cornerRadius = 5.dp, modifier = Modifier.weight(1f))
                    SkeletonBlock(width = 80.dp, height = 16.dp, cornerRadius = 5.dp)
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotesSkeleton() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        SkeletonBlock(width = Dp.Infinity, height = 16.dp, cornerRadius = 5.dp)
        SkeletonBlock(width = Dp.Infinity, height = 16.dp, cornerRadius = 5.dp)
        SkeletonBlock(width = 200.dp, height = 16.dp, cornerRadius = 5.dp)
    }
}

@Composable
private fun SkeletonBlock(
    width: Dp,
    height: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .then(if (width == Dp.Infinity) Modifier.fillMaxWidth() else Modifier.width(width))
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(ZapSurface2),
    )
}

@Composable
private fun RegularAppHeader(
    app: AppInfo,
    release: ReleaseInfo?,
    repository: CatalogRepository?,
    authorPubkey: String?,
    authorProfile: ProfileInfo?,
    onProfileClick: () -> Unit,
) {
    val authorName = authorPubkey?.let { profileDisplayName(authorProfile, it) }
    AppIdentityRow(
        name = app.name,
        iconUrl = app.iconUrl,
        authorName = authorName,
        authorProfile = authorProfile,
        authorPubkey = authorPubkey,
        version = release?.version,
        onAuthorClick = onProfileClick,
    )
}

@Composable
private fun CompactAppHeader(
    app: AppInfo,
    repository: CatalogRepository?,
    authorPubkey: String?,
    authorProfile: ProfileInfo?,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ZapCanvas)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(SmallHeaderHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppIcon(
            title = app.name,
            iconUrl = app.iconUrl,
            size = SmallIconSize,
        )
        Text(
            text = app.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        authorPubkey?.let { pubkey ->
            AppAuthorByline(
                pubkey = pubkey,
                repository = repository,
                profile = authorProfile,
                onClick = onProfileClick,
            )
        }
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
                    painter = painterResource(R.drawable.ic_close),
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
            .background(ZapSurface1)
            .border(1.dp, ZapLine, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.support).uppercase(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = ZapTextSecondary,
        )
        Spacer(Modifier.height(4.dp))
        if (summary.isLoading && summary.zapCount == 0) {
            Box(
                Modifier
                    .width(148.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ZapSurface2),
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
            .clip(RoundedCornerShape(ZapRadius.md))
            .background(ZapSurface2)
            .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.md))
            .padding(horizontal = ZapSpacing.space3, vertical = ZapSpacing.space2),
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
    authorPubkey: String?,
    c1AuthorPubkey: String?,
    authorProfile: ProfileInfo?,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZapRadius.md))
            .background(ZapSurface1)
            .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.md))
            .padding(ZapSpacing.space4),
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
        authorPubkey?.let { pubkey ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            StatusText(stringResource(R.string.author))
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                AppAuthorByline(
                    pubkey = pubkey,
                    repository = repository,
                    profile = authorProfile,
                    showBy = false,
                    onClick = onProfileClick,
                )
            }
        }
        }
        c1AuthorPubkey?.let { pubkey ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            ) {
                StatusText("C1 author")
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    AppAuthorByline(
                        pubkey = pubkey,
                        repository = repository,
                        showBy = false,
                    )
                }
            }
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

