package dev.zapstore.app.screens

import androidx.compose.runtime.mutableLongStateOf
import dev.zapstore.app.components.shortDisplayName
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.platform.LocalDensity
import dev.zapstore.app.components.animationsEnabled
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import dev.zapstore.app.CdnImageVariant
import dev.zapstore.app.MarkdownText
import dev.zapstore.app.R
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapDanger
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapText2
import dev.zapstore.app.cdnImageUrl
import dev.zapstore.app.components.AppDetailHead
import dev.zapstore.app.components.ZapMotion
import dev.zapstore.app.components.scrollParallax
import dev.zapstore.app.components.windowParallax
import dev.zapstore.app.components.AppIcon
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.GroupHeader
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.ParagraphSkeleton
import dev.zapstore.app.components.SkeletonBlock
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapIconButton
import dev.zapstore.app.components.ZapProgressButton
import dev.zapstore.app.components.ZapTextLink
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.StackRecord

@Composable
fun AppDetailScreen(
    state: AppDetailUiState,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    onProfileClick: (String) -> Unit = {},
    onRetryComments: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onInstall: () -> Unit = {},
    onConfirmInstall: (alwaysTrust: Boolean) -> Unit = {},
    onDismissInstall: () -> Unit = {},
    onUninstall: () -> Unit = {},
    onOpenApp: () -> Unit = {},
    onRefreshOrigin: () -> Unit = {},
    onPauseInstall: () -> Unit = {},
    onStartInstall: () -> Unit = {},
    onDismissInstallError: () -> Unit = {},
    saved: Boolean = false,
    ignored: Boolean = false,
    onSave: (Boolean) -> Unit = {},
    onIgnore: (Boolean) -> Unit = {},
    onShare: () -> Unit = {},
    onPostComment: (String) -> Unit = {},
    onReport: (type: String, note: String) -> Unit = { _, _ -> },
    onReportClosed: () -> Unit = {},
    onZap: (sats: Long, note: String) -> Unit = { _, _ -> },
    onZapClosed: () -> Unit = {},
    onToggleStack: (StackRecord) -> Unit = {},
    onCreateStack: (String) -> Unit = {},
    onStackClick: (StackRecord) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefreshOrigin()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var selectedScreenshot by remember { mutableStateOf<Int?>(null) }
    var sheet by remember { mutableStateOf<Pair<String, String>?>(null) }
    var actions by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var stacking by remember { mutableStateOf(false) }
    var zapping by remember { mutableStateOf(false) }
    var zapAmount by remember { mutableLongStateOf(ZAP_AMOUNTS[1]) }
    val listState = rememberLazyListState()
    val headCollapse by remember {
        derivedStateOf {
            val head = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "head" }
            when {
                listState.firstVisibleItemIndex > 0 || head == null -> 1f
                else -> (listState.firstVisibleItemScrollOffset / head.size.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            }
        }
    }
    val app = state.app
    val comments = state.comments
    val feed = remember(comments.threads, comments.zaps) { feedEntries(comments.threads, comments.zaps) }
    val developerDescription = stringResource(R.string.developer_description)
    val releaseNotes = stringResource(R.string.release_notes)

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("appDetail"),
            contentPadding = PaddingValues(
                start = ZapSpacing.space4,
                top = ZapSpacing.space4,
                end = ZapSpacing.space4,
                bottom = if (state.install.hasBar) INSTALL_BAR_SPACE + ZapSpacing.space4 else ZapSpacing.space10,
            ),
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space4),
        ) {
            if (app == null) {
                item { if (state.loaded) EmptyRow(stringResource(R.string.app_not_found)) else AppDetailSkeleton() }
                return@LazyColumn
            }

            item(key = "head", contentType = "head") {
                AppDetailHead(
                    name = app.name,
                    iconUrl = app.iconUrl,
                    iconFile = app.iconFile,
                    version = app.version,
                    caption = formatDate(app.releasedAt),
                    authorPubkey = app.authorPubkey,
                    author = state.author,
                    onAuthorClick = onProfileClick,
                    onMore = { actions = true },
                    modifier = Modifier.scrollParallax(listState),
                )
            }
            state.install.otherStore?.let { store ->
                item(key = "otherStore", contentType = "status") {
                    StatusText(stringResource(R.string.installed_by_store, store), Modifier.testTag("installedByStore"))
                }
            }

            if (app.screenshots.isNotEmpty()) {
                item(key = "screenshots", contentType = "screenshots") {
                    val shots = rememberLazyListState()
                    LazyRow(state = shots, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                        items(app.screenshots.take(5), key = { it }) { url ->
                            Box(
                                Modifier
                                    .width(120.dp)
                                    .height(208.dp)
                                    .clip(RoundedCornerShape(ZapRadius.md))
                                    .background(ZapSurface3)
                                    .clickable { selectedScreenshot = app.screenshots.indexOf(url) },
                            ) {
                                AsyncImage(
                                    model = cdnImageUrl(url, CdnImageVariant.ThumbnailSmall),
                                    contentDescription = stringResource(R.string.screenshot_description, app.name),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().windowParallax(shots, url),
                                )
                            }
                        }
                    }
                }
            }

            val about = app.about.ifBlank { app.description }
            if (about.isNotBlank()) {
                item(key = "about", contentType = "text") {
                    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1)) {
                        MarkdownText(
                            value = about,
                            color = ZapText2,
                            style = MaterialTheme.typography.bodyLarge,
                            onOpenUrl = onOpenUrl,
                            collapsible = app.about.isBlank(),
                        )
                        if (app.about.isNotBlank() && app.description.isNotBlank()) {
                            ZapTextLink(
                                stringResource(R.string.see_developer_description),
                                { sheet = developerDescription to app.description },
                                Modifier.testTag("developerDescription"),
                            )
                        }
                    }
                }
            }

            if (state.social.zappable || state.zaps.count > 0) {
                item(key = "zaps", contentType = "zaps") {
                    ZapCard(
                        state.zaps,
                        appName = app.name,
                        onZap = if (state.social.zappable) ({ amount -> zapAmount = amount; zapping = true }) else null,
                    )
                }
            }

            if (app.hasSecurityCard()) {
                item(key = "security", contentType = "card") { SecurityCard(app, onOpenUrl) }
            }
            item(key = "release", contentType = "card") {
                ReleaseCard(
                    app = app,
                    author = state.author,
                    onOpenUrl = onOpenUrl,
                    onProfileClick = onProfileClick,
                    onReleaseNotes = { sheet = releaseNotes to app.releaseNotes },
                )
            }

            if (state.stackedBy.stacks.isNotEmpty()) {
                item(key = "stackedBy", contentType = "stackedBy") {
                    StackedByRow(state.stackedBy, onStackClick, Modifier.padding(vertical = ZapSpacing.space2))
                }
            }

            item(key = "commentsHeader", contentType = "header") {
                Column {
                    HorizontalDivider(color = ZapLine)
                    GroupHeader(stringResource(R.string.comments), Modifier.padding(top = ZapSpacing.space2))
                    if (feed.isNotEmpty()) {
                        FeedTally(feed, state.zaps.totalSats)
                    }
                }
            }
            item(key = "commentComposer", contentType = "composer") {
                CommentComposer(state.social, onPostComment, onSettingsClick)
            }
            when {
                comments.loading && feed.isEmpty() -> item(contentType = "loading") {
                    LoadingIndicator(Modifier.padding(vertical = ZapSpacing.space3).testTag("commentsLoading"))
                }
                feed.isEmpty() -> item(contentType = "empty") {
                    CommentsEmpty(error = comments.error, onRetry = onRetryComments, onSettings = onSettingsClick)
                }
                else -> items(feed, key = FeedEntry::key, contentType = { if (it is FeedEntry.Zap) "zap" else "comment" }) { entry ->
                    when (entry) {
                        is FeedEntry.Comment -> CommentRow(
                            comment = entry.comment,
                            author = comments.profiles[entry.comment.pubkey],
                            depth = entry.depth,
                            onOpenUrl = onOpenUrl,
                            onAuthorClick = onProfileClick,
                        )
                        is FeedEntry.Zap -> ZapEntryRow(
                            zap = entry.zap,
                            author = comments.profiles[entry.zap.pubkey],
                            onOpenUrl = onOpenUrl,
                            onAuthorClick = onProfileClick,
                        )
                    }
                }
            }
        }

        if (app != null) {
            CompactAppHeader(
                app = app,
                collapse = { headCollapse },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            InstallBar(
                app = app,
                install = state.install,
                actions = InstallActions(
                    onInstall = onInstall,
                    onPause = onPauseInstall,
                    onStart = onStartInstall,
                    onDismissError = onDismissInstallError,
                    onOpen = onOpenApp,
                    onUninstall = onUninstall,
                ),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (app != null) {
        selectedScreenshot?.let { initialPage ->
            ScreenshotCarousel(app.screenshots, initialPage, app.name) { selectedScreenshot = null }
        }
        state.install.dialog?.let { plan ->
            InstallSheet(
                name = app.name,
                version = app.version,
                verified = app.isVerified,
                plan = plan,
                onConfirm = onConfirmInstall,
                onDismiss = onDismissInstall,
                otherStore = state.install.otherStore,
                trustName = app.authorPubkey?.let { shortDisplayName(it, state.author) },
                catalogSigned = app.isCatalogSigned,
                downloadUrl = app.downloadUrl,
            )
        }
        sheet?.let { (title, markdown) ->
            MarkdownSheet(title, markdown, onOpenUrl) { sheet = null }
        }
        if (zapping) {
            ZapAppSheet(
                appName = app.name,
                social = state.social,
                recipient = state.zaps.recipient,
                recipientName = state.zaps.recipient?.let { shortDisplayName(it.pubkey, state.zaps.profiles[it.pubkey]) }.orEmpty(),
                initialSats = zapAmount,
                onZap = onZap,
                onSettings = {
                    zapping = false
                    onZapClosed()
                    onSettingsClick()
                },
                onDismiss = {
                    zapping = false
                    onZapClosed()
                },
            )
        }
        if (reporting) {
            ReportSheet(
                appName = app.name,
                social = state.social,
                onSend = onReport,
                onSignIn = {
                    reporting = false
                    onSettingsClick()
                },
                onDismiss = {
                    reporting = false
                    onReportClosed()
                },
            )
        }
        if (actions) {
            AppActionSheet(
                app = app,
                saved = saved,
                ignored = ignored,
                installed = state.install.installed,
                onSave = { onSave(!saved) },
                onIgnore = { onIgnore(!ignored) },
                onShare = {
                    actions = false
                    onShare()
                },
                onReport = {
                    actions = false
                    reporting = true
                },
                onUninstall = if (state.install.installed || state.install.updating) {
                    {
                        actions = false
                        onUninstall()
                    }
                } else {
                    null
                },
                onDismiss = { actions = false },
                onAddToStack = {
                    actions = false
                    if (state.userStacks == null) onSettingsClick() else stacking = true
                },
            )
        }
        val userStacks = state.userStacks
        if (stacking && userStacks != null) {
            AddToStackSheet(
                app = app,
                state = userStacks,
                onToggle = onToggleStack,
                onCreate = onCreateStack,
                onDismiss = { stacking = false },
            )
        }
    }
}

private val InstallUi.hasBar: Boolean get() = offer || installed || phase != InstallPhase.Idle

@Composable
private fun AppDetailSkeleton() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZapSpacing.space4)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space4)) {
            SkeletonBlock(64.dp, 64.dp, 14.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                SkeletonBlock(width = 180.dp, height = 22.dp, cornerRadius = 6.dp)
                SkeletonBlock(width = 96.dp, height = 20.dp, cornerRadius = 10.dp)
            }
        }
        SkeletonBlock(width = 400.dp, height = 40.dp, cornerRadius = 12.dp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
            items(4) { SkeletonBlock(width = 120.dp, height = 208.dp, cornerRadius = 14.dp) }
        }
        ParagraphSkeleton()
    }
}

/**
 * Fades in as the head scrolls away; the icon settles from the head's size into the bar.
 */
@Composable
private fun CompactAppHeader(
    app: AppRecord,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
) {
    val animate = animationsEnabled()
    val shown by remember { derivedStateOf { collapse() > COMPACT_START } }
    if (!shown) return
    val reveal = { ((collapse() - COMPACT_START) / (1f - COMPACT_START)).coerceIn(0f, 1f) }
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (animate) reveal() else 1f }
            .background(ZapCanvas)
            .testTag("compactHeader"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier.fillMaxWidth().height(COMPACT_BAR_HEIGHT).padding(start = ZapSpacing.space4, end = ZapSpacing.space3),
        ) {
            AppIcon(
                title = app.name,
                iconUrl = app.iconUrl,
                iconFile = app.iconFile,
                size = 28.dp,
                modifier = Modifier.graphicsLayer {
                    if (!animate) return@graphicsLayer
                    val t = reveal()
                    val scale = 1f + (HEAD_ICON_RATIO - 1f) * (1f - t)
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - t) * 14.dp.toPx()
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            )
            Text(
                app.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).graphicsLayer {
                    if (animate) translationX = (1f - reveal()) * 10.dp.toPx()
                },
            )
        }
        HorizontalDivider(color = ZapLine)
    }
}

private const val COMPACT_START = 0.45f

private val COMPACT_BAR_HEIGHT = 52.dp

/** Head icon (64 dp) over bar icon (28 dp): where the morph starts. */
private const val HEAD_ICON_RATIO = 64f / 28f
