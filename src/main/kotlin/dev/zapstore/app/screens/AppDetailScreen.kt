package dev.zapstore.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
fun AppDetailScreen(
    state: AppDetailUiState,
    onOpenUrl: (String) -> Unit,
    repository: CatalogRepository? = null,
    onProfileClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedScreenshot by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ZapBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 36.dp),
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

        item {
            val authorName = rememberProfileDisplayName(app.event.pubKey, repository)
            Row(verticalAlignment = Alignment.Top) {
                AppIcon(
                    title = app.name,
                    iconUrl = app.iconUrl,
                    modifier = Modifier.size(84.dp),
                )
                Spacer(modifier.width(16.dp))
                Column(modifier.weight(1f)) {
                    AppNameWithByline(
                        name = app.name,
                        authorName = authorName,
                        nameStyle = MaterialTheme.typography.displaySmall,
                        onAuthorClick = { onProfileClick(app.event.pubKey) },
                        authorTestTag = "profile:${app.event.pubKey}",
                    )
                    state.release?.let { release ->
                        Spacer(modifier.height(10.dp))
                        VersionPill(version = release.version)
                    }
                }
            }
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
            .clip(RoundedCornerShape(12.dp))
            .background(ZapSurface)
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
            .clip(RoundedCornerShape(8.dp))
            .background(ZapSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        StatusText(stringResource(R.string.version))
        Spacer(Modifier.width(6.dp))
        Text(
            text = release.version,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
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
            .clip(RoundedCornerShape(12.dp))
            .background(ZapSurface)
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

