package dev.zapstore.app.screens

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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.zapstore.app.CdnImageVariant
import dev.zapstore.app.MarkdownText
import dev.zapstore.app.R
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapSurface2
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapDanger
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.app.ZapVerified
import dev.zapstore.app.ZapWarning
import dev.zapstore.app.cdnImageUrl
import dev.zapstore.app.components.AppIcon
import dev.zapstore.app.components.AppIdentityRow
import dev.zapstore.app.components.AuthorByline
import dev.zapstore.app.components.InfoRow
import dev.zapstore.app.components.ProfileImage
import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.app.components.ParagraphSkeleton
import dev.zapstore.app.components.SkeletonBlock
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.StatusText
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.ProfileRecord
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

private val CompactIconSize = 26.dp
private val CompactHeaderHeight = 32.dp

@Composable
fun AppDetailScreen(
    state: AppDetailUiState,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    onProfileClick: (String) -> Unit = {},
    onRetryComments: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
) {
    var selectedScreenshot by remember { mutableStateOf<Int?>(null) }
    var showDeveloperDescription by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val showCompactHeader by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val app = state.app
    val comments = state.comments
    val threaded = remember(comments.threads) { comments.threads.flattenComments() }

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
                item { if (state.loaded) NotFoundCard() else AppDetailSkeleton() }
                return@LazyColumn
            }

            item {
                AppIdentityRow(
                    name = app.name,
                    iconUrl = app.iconUrl,
                    iconFile = app.iconFile,
                    version = app.version,
                    authorPubkey = app.authorPubkey,
                    author = state.author,
                    onAuthorClick = onProfileClick,
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
                                    .clickable { selectedScreenshot = app.screenshots.indexOf(url) },
                            )
                        }
                    }
                }
            }

            if (app.about.isNotBlank()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MarkdownText(
                            value = app.about,
                            color = ZapTextSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                            onOpenUrl = onOpenUrl,
                        )
                        if (app.description.isNotBlank()) {
                            TextButton(
                                onClick = { showDeveloperDescription = true },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.testTag("developerDescription"),
                            ) {
                                Text(
                                    text = stringResource(R.string.see_developer_description),
                                    color = ZapActionText,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            } else if (app.description.isNotBlank()) {
                item {
                    MarkdownText(
                        value = app.description,
                        color = ZapTextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        onOpenUrl = onOpenUrl,
                        collapsible = true,
                    )
                }
            }

            if (app.security.isNotBlank() || app.factRows.isNotEmpty()) {
                item { SectionDivider(stringResource(R.string.privacy_and_security)) }
                if (app.factRows.isNotEmpty()) {
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("facts"),
                        ) {
                            app.factRows.forEach { fact ->
                                FactPill(key = fact.key, yes = fact.yes)
                            }
                        }
                    }
                }
                if (app.securityWarnings.isNotBlank()) {
                    item {
                        Text(
                            text = app.securityWarnings,
                            color = ZapWarning,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (app.securityBody.isNotBlank()) {
                    item {
                        MarkdownText(
                            value = app.securityBody,
                            color = ZapTextSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                            onOpenUrl = onOpenUrl,
                        )
                    }
                }
            }

            if (state.zaps.loading || state.zaps.count > 0 || state.zaps.error != null) {
                item { ZapSummaryCard(state.zaps) }
            }

            item { SectionDivider(stringResource(R.string.latest_release)) }
            item { VersionRow(app, onOpenUrl) }

            item {
                AppInfoCard(
                    app = app,
                    author = state.author,
                    onOpenUrl = onOpenUrl,
                    onProfileClick = onProfileClick,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            item { SectionDivider(stringResource(R.string.comments)) }
            when {
                comments.loading && threaded.isEmpty() -> item {
                    LoadingIndicator(Modifier.padding(vertical = 12.dp).testTag("commentsLoading"))
                }
                threaded.isEmpty() -> item {
                    CommentsEmpty(
                        error = comments.error,
                        onRetry = onRetryComments,
                        onSettings = onSettingsClick,
                    )
                }
                else -> items(threaded, key = { it.comment.eventId }) { entry ->
                    CommentRow(
                        comment = entry.comment,
                        author = comments.profiles[entry.comment.pubkey],
                        depth = entry.depth,
                        onOpenUrl = onOpenUrl,
                        onAuthorClick = onProfileClick,
                    )
                }
            }
        }

        if (showCompactHeader && app != null) {
            CompactAppHeader(
                app = app,
                author = state.author,
                onProfileClick = onProfileClick,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }

    if (app != null) {
        selectedScreenshot?.let { initialPage ->
            ScreenshotCarousel(
                screenshots = app.screenshots,
                initialPage = initialPage,
                appName = app.name,
                onDismiss = { selectedScreenshot = null },
            )
        }
        if (showDeveloperDescription && app.description.isNotBlank()) {
            MarkdownDialog(
                title = stringResource(R.string.developer_description),
                markdown = app.description,
                onOpenUrl = onOpenUrl,
                onDismiss = { showDeveloperDescription = false },
            )
        }
    }
}

@Composable
private fun NotFoundCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ZapSurface2),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = stringResource(R.string.app_not_found), color = ZapTextSecondary)
    }
}

/** Yes is the good outcome for these. Every other fact is an antifeature or sensitive access. */
private val desirableFactKeys = setOf(
    "open_source",
    "e2ee",
    "offline_capable",
    "self_hostable",
)

/** Ordinary device access. Presence is not a warning and absence is not a virtue. */
private val neutralFactKeys = setOf("camera", "location")

internal enum class FactTone { Positive, Negative, Neutral }

internal fun factTone(key: String, yes: Boolean): FactTone {
    if (key in neutralFactKeys) return FactTone.Neutral
    val positive = if (key in desirableFactKeys) yes else !yes
    return if (positive) FactTone.Positive else FactTone.Negative
}

internal fun factIsPositive(key: String, yes: Boolean): Boolean =
    factTone(key, yes) == FactTone.Positive

@Composable
private fun FactPill(key: String, yes: Boolean) {
    val label = factLabel(key)
    val text = if (yes) label else stringResource(R.string.fact_no, factAbsentBody(label))
    val fill = when (factTone(key, yes)) {
        FactTone.Positive -> ZapVerified
        FactTone.Negative -> ZapDanger
        FactTone.Neutral -> ZapTextSecondary
    }
    Text(
        text = text,
        color = ZapCanvas,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(ZapRadius.xs))
            .background(fill)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("fact_$key"),
    )
}

@Composable
private fun factLabel(key: String): String = when (key) {
    "google_services" -> stringResource(R.string.fact_google_services)
    "open_source" -> stringResource(R.string.fact_open_source)
    "e2ee" -> "E2EE"
    else -> key.replace('_', ' ').replaceFirstChar { it.titlecase() }
}

/** Keeps brands and acronyms; lowercases a sentence-style label after "No". */
internal fun factAbsentBody(label: String): String {
    val letters = label.filter { it.isLetter() }
    val acronym = letters.isNotEmpty() && letters.all { it.isUpperCase() }
    val keepCase = acronym || label.startsWith("Google ") || label.startsWith("Firebase ")
    return if (keepCase) label else label.replaceFirstChar { it.lowercase() }
}

@Composable
private fun SectionDivider(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = ZapLine)
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = ZapLine)
    }
}

@Composable
private fun AppDetailSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonBlock(74.dp, 74.dp, 16.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBlock(width = 190.dp, height = 22.dp, cornerRadius = 6.dp)
                SkeletonBlock(width = 140.dp, height = 16.dp, cornerRadius = 6.dp)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(4) { SkeletonBlock(width = 120.dp, height = 200.dp, cornerRadius = 16.dp) }
        }
        ParagraphSkeleton()
        SkeletonBlock(width = 220.dp, height = 32.dp, cornerRadius = 8.dp)
        ParagraphSkeleton()
    }
}

@Composable
private fun CompactAppHeader(
    app: AppRecord,
    author: ProfileRecord?,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ZapCanvas)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(CompactHeaderHeight)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppIcon(title = app.name, iconUrl = app.iconUrl, iconFile = app.iconFile, size = CompactIconSize)
        Text(
            text = app.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        app.authorPubkey?.let { pubkey ->
            AuthorByline(pubkey = pubkey, profile = author, onClick = { onProfileClick(pubkey) })
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
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
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
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = cdnImageUrl(screenshots[page], CdnImageVariant.ThumbnailLarge),
                    contentDescription = stringResource(R.string.screenshot_description, appName),
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
private fun ZapSummaryCard(summary: ZapSummary) {
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
        when {
            summary.loading && summary.count == 0 -> SkeletonBlock(width = 148.dp, height = 24.dp, cornerRadius = 6.dp)
            summary.count == 0 -> StatusText(summary.error.orEmpty())
            else -> Text(
                text = stringResource(
                    R.string.zap_summary,
                    NumberFormat.getInstance().format(summary.totalSats),
                    summary.count,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun VersionRow(app: AppRecord, onOpenUrl: (String) -> Unit) {
    val notes = app.releaseNotes
    val hasNotes = notes.isNotBlank()
    var showNotes by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZapRadius.md))
            .background(ZapSurface2)
            .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.md))
            .padding(
                start = ZapSpacing.space3,
                top = if (hasNotes) 0.dp else ZapSpacing.space2,
                end = if (hasNotes) 0.dp else ZapSpacing.space3,
                bottom = if (hasNotes) 0.dp else ZapSpacing.space2,
            ),
    ) {
        StatusText(stringResource(R.string.version))
        Spacer(Modifier.width(6.dp))
        Text(
            text = app.version,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        StatusText("(${formatDate(app.releasedAt)})")
        if (hasNotes) {
            IconButton(
                onClick = { showNotes = true },
                modifier = Modifier.testTag("releaseNotes"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more),
                    contentDescription = stringResource(R.string.release_notes),
                    tint = ZapTextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    if (showNotes) {
        MarkdownDialog(
            title = stringResource(R.string.release_notes),
            markdown = notes,
            onOpenUrl = onOpenUrl,
            onDismiss = { showNotes = false },
        )
    }
}

@Composable
private fun MarkdownDialog(
    title: String,
    markdown: String,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(ZapRadius.lg))
                .background(ZapSurface1)
                .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.lg)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = ZapSpacing.space4, end = 4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.close),
                        tint = ZapTextSecondary,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = ZapSpacing.space4,
                        end = ZapSpacing.space4,
                        bottom = ZapSpacing.space4,
                    ),
            ) {
                MarkdownText(
                    value = markdown,
                    color = ZapTextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    onOpenUrl = onOpenUrl,
                )
            }
        }
    }
}

@Composable
private fun AppInfoCard(
    app: AppRecord,
    author: ProfileRecord?,
    onOpenUrl: (String) -> Unit,
    onProfileClick: (String) -> Unit,
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
        app.website?.let { InfoRow(stringResource(R.string.website), it, link = it, onOpenUrl = onOpenUrl) }
        app.license?.let { InfoRow(stringResource(R.string.license), it) }
        InfoRow(stringResource(R.string.app_id), app.appId)
        app.authorPubkey?.let { pubkey ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            ) {
                StatusText(stringResource(if (app.isVerified) R.string.verified_author else R.string.author))
                Spacer(Modifier.weight(1f))
                AuthorByline(pubkey = pubkey, profile = author, showBy = false, onClick = { onProfileClick(pubkey) })
            }
        }
        InfoRow(stringResource(R.string.release_date), formatDate(app.releasedAt))
    }
}

@Composable
private fun CommentsEmpty(
    error: CommentsError?,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag("commentsEmpty"),
        verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
    ) {
        StatusText(
            stringResource(
                when (error) {
                    CommentsError.RelaysUnreachable -> R.string.comments_error_relays
                    CommentsError.TimedOut -> R.string.comments_error_timeout
                    null -> R.string.no_comments
                },
            ),
        )
        if (error != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                TextButton(onClick = onRetry, modifier = Modifier.testTag("commentsRetry")) {
                    Text(stringResource(R.string.try_again))
                }
                TextButton(onClick = onSettings, modifier = Modifier.testTag("commentsNetworkSettings")) {
                    Text(stringResource(R.string.network_settings))
                }
            }
        }
    }
}

@Composable
private fun CommentRow(
    comment: CommentRecord,
    author: ProfileRecord?,
    onOpenUrl: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    depth: Int = 0,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ZapSpacing.space5 * depth)
            .testTag("commentDepth:${comment.eventId}:$depth"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ZapRadius.md))
                .background(ZapSurface1)
                .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.md))
                .padding(ZapSpacing.space3)
                .testTag("comment:${comment.eventId}"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAuthorClick(comment.pubkey) }
                    .testTag("profile:${comment.pubkey}"),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ZapSurface3),
                ) {
                    ProfileImage(
                        pubkey = comment.pubkey,
                        pictureUrl = author?.picture,
                        profileVersion = author?.eventId,
                        contentDescription = stringResource(R.string.profile_avatar_description, shortDisplayName(comment.pubkey, author)),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    text = shortDisplayName(comment.pubkey, author),
                    color = ZapTextSecondary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusText(formatDate(comment.createdAt))
            }
            MarkdownText(
                value = comment.content,
                color = ZapTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

private fun formatDate(createdAt: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(createdAt * 1_000))
