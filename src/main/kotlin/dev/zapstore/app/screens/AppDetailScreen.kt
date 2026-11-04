package dev.zapstore.app.screens

import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.app.components.animationsEnabled
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.zapstore.app.CdnImageVariant
import dev.zapstore.app.MarkdownText
import dev.zapstore.app.R
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapText3
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapText2
import dev.zapstore.app.cdnImageUrl
import dev.zapstore.app.components.AppDetailHead
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
import dev.zapstore.iolite.AppByline
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CommentRecord
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
    onConfirmInstall: () -> Unit = {},
    onDismissInstall: () -> Unit = {},
    onPersonalizeTrust: () -> Unit = {},
    onUninstall: () -> Unit = {},
    onOpenApp: () -> Unit = {},
    onPauseInstall: () -> Unit = {},
    onStartInstall: () -> Unit = {},
    onDismissInstallError: () -> Unit = {},
    saved: Boolean = false,
    ignored: Boolean = false,
    onSave: (Boolean) -> Unit = {},
    onIgnore: (Boolean) -> Unit = {},
    onShare: () -> Unit = {},
    onPostComment: (text: String, parent: CommentRecord?) -> Unit = { _, _ -> },
    onReport: (type: String, note: String) -> Unit = { _, _ -> },
    onReportClosed: () -> Unit = {},
    onZap: (sats: Long, note: String) -> Unit = { _, _ -> },
    onZapClosed: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onConnectWallet: (String) -> Boolean = { false },
    onToggleStack: (StackRecord) -> Unit = {},
    onCreateStack: (String) -> Unit = {},
    onStackClick: (StackRecord) -> Unit = {},
) {
    var selectedScreenshot by remember { mutableStateOf<Int?>(null) }
    var releasing by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var stacking by remember { mutableStateOf(false) }
    var zapping by remember { mutableStateOf(false) }
    var commenting by rememberSaveable { mutableStateOf(false) }
    var replyingTo by remember { mutableStateOf<CommentRecord?>(null) }
    var report by remember { mutableStateOf<ReportFocus?>(null) }
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
    var showOriginal by remember(app?.appId) { mutableStateOf(false) }
    val comments = state.comments
    // Zaps are in the zap row above; only the ones that came with a note are listed among the comments.
    val feed = remember(comments.threads, comments.zaps) { feedBlocks(comments.threads, comments.zaps.filter { it.note.isNotBlank() }) }
    val other = remember(comments.other) { feedBlocks(comments.other, emptyList()) }
    val replyable = state.social.signedIn

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
                    authorPubkey = app.bylinePubkey,
                    author = state.author,
                    verified = app.byline == AppByline.Verified,
                    indexed = app.byline == AppByline.Indexed,
                    onAuthorClick = onProfileClick,
                    onVerifiedClick = { report = ReportFocus.Developer },
                    onMore = { actions = true },
                    modifier = Modifier.scrollParallax(listState),
                )
            }
            state.install.otherStore?.let { store ->
                item(key = "otherStore", contentType = "status") {
                    StatusText(stringResource(R.string.installed_by_store, store), Modifier.testTag("installedByStore"))
                }
            }
            if (state.install.signedDifferently) {
                item(key = "signedDifferently", contentType = "status") {
                    StatusText(stringResource(R.string.install_signed_differently), Modifier.testTag("signedDifferently"))
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

            val generated = app.about.trim()
            val listing = app.listing.trim()
            val both = generated.isNotEmpty() && listing.isNotEmpty()
            val showingOriginal = both && showOriginal
            val about = when {
                showingOriginal -> listing
                generated.isNotEmpty() -> generated
                else -> listing.ifBlank { app.summary.trim() }
            }
            item(key = "about", contentType = "text") {
                Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1)) {
                    if (about.isNotBlank()) {
                        MarkdownText(
                            value = about,
                            color = ZapText2,
                            style = MaterialTheme.typography.bodyLarge,
                            onOpenUrl = onOpenUrl,
                            collapsible = generated.isEmpty(),
                        )
                    } else {
                        // Said out loud, as on the web page, instead of leaving the section out.
                        Text(stringResource(R.string.no_description), color = ZapText2, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (generated.isNotEmpty()) {
                        AboutSourceNote(
                            showingOriginal = showingOriginal,
                            canSwap = both,
                            onSwap = { showOriginal = !showOriginal },
                        )
                    }
                }
            }

            if (state.social.zappable || state.zaps.zappers.isNotEmpty()) {
                item(key = "zaps", contentType = "zaps") {
                    ZapRow(
                        state.zaps,
                        onProfileClick = onProfileClick,
                        onZap = if (state.social.zappable) ({ zapping = true }) else null,
                    )
                }
            }

            if (state.stackedBy.stacks.isNotEmpty()) {
                item(key = "stackedBy", contentType = "stackedBy") {
                    StackedByRow(state.stackedBy, onStackClick, Modifier.padding(vertical = ZapSpacing.space2))
                }
            }

            item(key = "release", contentType = "card") {
                ReleaseCard(app, onOpen = { releasing = true })
            }
            if (app.hasSecurityCard()) {
                item(key = "security", contentType = "card") {
                    SecurityReportCard(
                        app = app,
                        onOpenUrl = onOpenUrl,
                        onSeeFull = { report = ReportFocus.Top },
                    )
                }
            } else {
                item(key = "security", contentType = "card") {
                    PackageCard(onOpen = { report = ReportFocus.Top })
                }
            }

            item(key = "commentsHeader", contentType = "header") {
                // "Activity": the list below is the app's comments, and the zaps that came with a note.
                GroupHeader(stringResource(R.string.activity), count = feed.sumOf { it.size }.takeIf { it > 0 })
            }
            item(key = "commentComposer", contentType = "composer") {
                AddCommentButton({ commenting = true })
            }
            when {
                comments.error != null && feed.isEmpty() -> item(key = "commentsError", contentType = "error") {
                    CommentsEmpty(error = comments.error, onRetry = onRetryComments, onSettings = onSettingsClick)
                }
                comments.loading && feed.isEmpty() -> item(key = "commentsLoading", contentType = "loading") {
                    LoadingIndicator(Modifier.padding(vertical = ZapSpacing.space3).testTag("commentsLoading"))
                }
                else -> items(feed, key = { it.first().key }, contentType = { "thread" }) { block ->
                    FeedBlock(
                        block = block,
                        profiles = comments.profiles,
                        replyable = replyable,
                        onReply = { replyingTo = it },
                        onOpenUrl = onOpenUrl,
                        onAuthorClick = onProfileClick,
                    )
                }
            }
            if (other.isNotEmpty()) {
                // Comments on this app id from another coordinate, such as another catalog's. Zaps are not in it.
                item(key = "otherCommentsHeader", contentType = "header") {
                    Column(Modifier.padding(top = ZapSpacing.space4).testTag("otherComments")) {
                        GroupHeader(stringResource(R.string.other_comments))
                        Text(stringResource(R.string.other_comments_hint), color = ZapText3, style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(other, key = { "other:${it.first().key}" }, contentType = { "thread" }) { block ->
                    FeedBlock(
                        block = block,
                        profiles = comments.profiles,
                        replyable = false,
                        onReply = {},
                        onOpenUrl = onOpenUrl,
                        onAuthorClick = onProfileClick,
                    )
                }
            }
        }

        if (app != null) {
            CompactAppHeader(
                app = app,
                collapse = { headCollapse },
                onMore = { actions = true },
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
                source = app.repository,
                trust = state.install.trust,
                onPersonalizeTrust = onPersonalizeTrust,
            )
        }
        if (releasing) {
            ReleaseSheet(app, onOpenUrl) { releasing = false }
        }
        report?.let { focus ->
            SecurityReportSheet(
                app = app,
                author = state.author,
                proof = state.proof,
                focus = focus,
                onOpenUrl = onOpenUrl,
                onProfileClick = { pubkey ->
                    report = null
                    onProfileClick(pubkey)
                },
                onDismiss = { report = null },
            )
        }
        if (commenting) {
            val version = app.version.takeIf { it.isNotBlank() }
            CommentSheet(
                title = stringResource(R.string.comment_on, version ?: app.name),
                hint = stringResource(R.string.comment_hint_app, app.name, version.orEmpty()).trim(),
                signInMessage = stringResource(R.string.comment_sign_in),
                postLabel = stringResource(R.string.post_comment),
                social = state.social,
                onPost = { onPostComment(it, null) },
                onSignIn = onSignIn,
                onDismiss = { commenting = false },
            )
        }
        replyingTo?.let { parent ->
            val parentAuthor = comments.profiles[parent.pubkey]
            CommentSheet(
                title = stringResource(R.string.reply_to, shortDisplayName(parent.pubkey, parentAuthor)),
                hint = stringResource(R.string.reply_hint),
                signInMessage = stringResource(R.string.reply_sign_in),
                postLabel = stringResource(R.string.post_reply),
                social = state.social,
                onPost = { onPostComment(it, parent) },
                onSignIn = onSignIn,
                onDismiss = { replyingTo = null },
                preview = parent.content,
            )
        }
        if (zapping) {
            ZapAppSheet(
                appName = app.name,
                social = state.social,
                recipient = state.zaps.recipient,
                recipientName = state.zaps.recipient?.let { shortDisplayName(it.pubkey, state.zaps.profiles[it.pubkey]) }.orEmpty(),
                initialSats = ZAP_AMOUNTS[0].sats,
                onZap = onZap,
                onSignIn = onSignIn,
                onConnectWallet = onConnectWallet,
                onRetry = onZapClosed,
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
                onWebsite = app.website?.let { site ->
                    {
                        actions = false
                        onOpenUrl(site)
                    }
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
    onMore: () -> Unit,
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
            ZapIconButton(
                R.drawable.ic_more,
                stringResource(R.string.more_actions),
                onMore,
                style = ZapButtonStyle.Ghost,
                modifier = Modifier.testTag("compactMore"),
            )
        }
        HorizontalDivider(color = ZapLine)
    }
}

/** The web caption under an enriched about: the generated line, and a swap to the developer's own text when it exists. */
@Composable
private fun AboutSourceNote(showingOriginal: Boolean, canSwap: Boolean, onSwap: () -> Unit) {
    val lead = stringResource(if (showingOriginal) R.string.original_description else R.string.summary_generated)
    val link = if (canSwap) stringResource(if (showingOriginal) R.string.view_summary else R.string.view_original) else null
    Text(
        text = buildAnnotatedString {
            append(lead)
            if (link != null) {
                append(" ")
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "about-swap",
                        styles = TextLinkStyles(SpanStyle(color = ZapActionText)),
                        linkInteractionListener = { onSwap() },
                    ),
                ) { append(link) }
            }
        },
        color = ZapText3,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(if (showingOriginal) "viewSummary" else "summaryGenerated"),
    )
}

private const val COMPACT_START = 0.45f

private val COMPACT_BAR_HEIGHT = 52.dp

/** Head icon (64 dp) over bar icon (28 dp): where the morph starts. */
private const val HEAD_ICON_RATIO = 64f / 28f
