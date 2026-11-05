package dev.zapstore.app.screens

import androidx.compose.foundation.layout.offset
import dev.zapstore.app.components.ZapTextLink
import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.app.components.EvidenceLine
import androidx.compose.foundation.background
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CertificateProof
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import dev.zapstore.app.components.INDEXED_GLYPH
import dev.zapstore.app.components.grayscale
import dev.zapstore.app.components.pressScale
import dev.zapstore.app.components.AppIcon
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapActionTint
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapDanger
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import dev.zapstore.app.components.ZapIconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.zapstore.app.CdnImageVariant
import dev.zapstore.app.MarkdownText
import dev.zapstore.app.R
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapScrim
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface2
import dev.zapstore.app.ZapSurface4
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3
import dev.zapstore.app.cdnImageUrl
import dev.zapstore.app.components.BadgeTone
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.StatusText
import dev.zapstore.iolite.StackRecord
import dev.zapstore.app.components.InfoRow
import dev.zapstore.app.components.VersionPill
import dev.zapstore.app.components.ZapBadge
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.install.InstallPlan
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.HorizontalDivider
import dev.zapstore.app.components.SectionLabel
import dev.zapstore.app.components.EvidenceField
import dev.zapstore.iolite.AppByline
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.app.ZapLine

/** False while a [ZapSheet] is still sliding in. A field that opens the keyboard waits for true, so the two never animate at once. */
internal val LocalSheetSettled = compositionLocalOf { true }

/**
 * Surface-2 bottom sheet with a grabber and 28 dp top corners. Its content rides above the keyboard:
 * the sheet grows with the keyboard's own animation instead of jumping when the keyboard lands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZapSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ZapSurface2,
        scrimColor = ZapScrim,
        shape = RoundedCornerShape(topStart = ZapRadius.xl, topEnd = ZapRadius.xl),
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = ZapSpacing.space3)
                    .size(width = 36.dp, height = 4.dp)
                    .background(ZapSurface4, RoundedCornerShape(ZapRadius.bar)),
            )
        },
    ) {
        CompositionLocalProvider(LocalSheetSettled provides (sheetState.currentValue == SheetValue.Expanded)) {
            Box(Modifier.navigationBarsPadding().imePadding()) { content() }
        }
    }
}

/**
 * The "..." button and its dropdown: Share, Save (which adds the app to the device's Saved stack and keeps
 * the menu open), Add to stack, then Website and Repository when the listing has them, then Report.
 * Each button keeps its own open state, so the head's and the compact bar's never show two menus.
 */
@Composable
internal fun AppMoreMenu(
    saved: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onReport: () -> Unit,
    onAddToStack: () -> Unit,
    onWebsite: (() -> Unit)?,
    onRepository: (() -> Unit)?,
    buttonTag: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ZapIconButton(
            R.drawable.ic_more,
            stringResource(R.string.more_actions),
            { open = true },
            style = ZapButtonStyle.Ghost,
            modifier = Modifier.testTag(buttonTag),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = ZapSurface2) {
            fun close(action: () -> Unit): () -> Unit = {
                open = false
                action()
            }
            MenuAction(R.drawable.ic_share, stringResource(R.string.action_share), close(onShare), "actionShare")
            MenuAction(
                R.drawable.ic_bookmark,
                stringResource(if (saved) R.string.action_saved else R.string.action_save),
                onSave,
                "actionSave",
                tint = if (saved) ZapActionText else ZapText,
            )
            MenuAction(R.drawable.ic_stack, stringResource(R.string.action_add_to_stack), close(onAddToStack), "actionAddToStack")
            onWebsite?.let { MenuAction(R.drawable.ic_external, stringResource(R.string.action_website), close(it), "actionWebsite") }
            onRepository?.let { MenuAction(R.drawable.ic_external, stringResource(R.string.repository), close(it), "actionRepository") }
            MenuAction(R.drawable.ic_flag, stringResource(R.string.action_report), close(onReport), "actionReport", tint = ZapDanger)
        }
    }
}

@Composable
private fun MenuAction(icon: Int, label: String, onClick: () -> Unit, tag: String, tint: Color = ZapText) {
    DropdownMenuItem(
        text = { Text(label, color = tint, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

/**
 * Add to stack: the account's public stacks, checked when they list the app. Each tap saves at
 * once; a toast confirms. The note says stacks are public.
 */
@Composable
internal fun AddToStackSheet(
    app: AppRecord,
    state: UserStacksUi,
    onToggle: (StackRecord) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5)
                .testTag("addToStack"),
        ) {
            Text(stringResource(R.string.add_to_stack_title, app.name), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.stacks_are_public), color = ZapText2, style = MaterialTheme.typography.bodyMedium)
            if (state.loading && state.stacks.isEmpty()) LoadingIndicator(Modifier.size(20.dp))
            StacksPicker(app.appId, state, onToggle, onCreate)
            ZapButton(
                text = stringResource(R.string.done),
                onClick = onDismiss,
                style = ZapButtonStyle.Tonal,
                modifier = Modifier.fillMaxWidth().testTag("addToStackDone"),
            )
        }
    }
}

/** The account's public stacks, checked when they list [appId], then a row that names a new one. */
@Composable
private fun StacksPicker(
    appId: String,
    state: UserStacksUi,
    onToggle: (StackRecord) -> Unit,
    onCreate: (String) -> Unit,
) {
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    val creating = state.busy == UserStacksUi.NEW_STACK
    LaunchedEffect(state.created) {
        naming = false
        name = ""
    }
    Column(Modifier.testTag("userStacks")) {
        state.stacks.forEach { stack ->
            val holds = appId in stack.appIds
            StackPickerRow(
                label = stack.name,
                icon = if (holds) R.drawable.ic_check else null,
                busy = state.busy == stack.identifier,
                onClick = { onToggle(stack) },
                modifier = Modifier.testTag("userStack:${stack.identifier}"),
            )
        }
        if (naming) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                modifier = Modifier.padding(top = ZapSpacing.space2),
            ) {
                ZapTextArea(
                    value = name,
                    onValueChange = { name = it.take(MAX_STACK_NAME) },
                    placeholder = stringResource(R.string.stack_name),
                    maxLines = 1,
                    modifier = Modifier.weight(1f).testTag("newStackName"),
                )
                ZapButton(
                    text = stringResource(R.string.create),
                    onClick = { onCreate(name) },
                    enabled = name.isNotBlank() && state.busy == null,
                    loading = creating,
                    modifier = Modifier.height(ZapSize.control).testTag("createStack"),
                )
            }
        } else {
            StackPickerRow(
                label = stringResource(R.string.new_stack),
                icon = R.drawable.ic_plus,
                busy = false,
                onClick = { naming = true },
                modifier = Modifier.testTag("newStack"),
            )
        }
        state.error?.let { StatusText(it, Modifier.testTag("userStacksError")) }
    }
}

@Composable
private fun StackPickerRow(label: String, icon: Int?, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ZapSize.control)
            .clip(RoundedCornerShape(ZapRadius.md))
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .padding(horizontal = ZapSpacing.space1),
    ) {
        Text(
            label,
            color = ZapText,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            busy -> LoadingIndicator(Modifier.size(20.dp))
            icon != null -> Icon(painterResource(icon), contentDescription = null, tint = ZapActionText, modifier = Modifier.size(20.dp))
        }
    }
}

private const val MAX_STACK_NAME = 64

/** A question with one primary answer: title, explanation, the action full width, then Cancel. */
@Composable
internal fun ConfirmSheet(
    title: String,
    body: List<String>,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmStyle: ZapButtonStyle = ZapButtonStyle.Primary,
    confirmTag: String = "confirm",
) {
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier.fillMaxWidth().padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            body.forEach { Text(it, color = ZapText2, style = MaterialTheme.typography.bodyMedium) }
            ZapButton(
                text = confirm,
                onClick = onConfirm,
                style = confirmStyle,
                modifier = Modifier.fillMaxWidth().height(ZapSize.controlLarge).testTag(confirmTag),
            )
            ZapButton(
                text = stringResource(R.string.install_cancel),
                onClick = onDismiss,
                style = ZapButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Trust and install: what the person needs to decide, then the one primary action. A first install
 * leads with the [AssuranceSummary]; a takeover or silent update says what Android will do instead.
 */
@Composable
internal fun InstallSheet(
    app: AppRecord,
    author: ProfileRecord?,
    plan: InstallPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onOpenReport: () -> Unit = {},
    otherStore: String? = null,
    trust: TrustUi = TrustUi(),
    onPersonalizeTrust: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    val switching = plan is InstallPlan.Takeover && otherStore != null
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
                Text(
                    stringResource(if (plan is InstallPlan.FirstInstall) R.string.install_title else R.string.update_title, app.name),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                VersionPill(app.version)
            }
            if (plan is InstallPlan.FirstInstall) {
                AssuranceSummary(app, author, trust, onPersonalizeTrust, onOpenUrl, onOpenReport)
            } else {
                if (app.byline == AppByline.Verified) {
                    VerifiedStandingLine(app.bylinePubkey?.let { shortDisplayName(it, author) }.orEmpty(), app.bylinePubkey, null)
                }
                if (app.isCatalogSigned) CatalogSignedNote(app.name, app.downloadUrl, Modifier.testTag("installCatalogSigned"), source = app.repository)
                Text(
                    if (switching) {
                        stringResource(R.string.install_takeover_store, otherStore.orEmpty())
                    } else {
                        stringResource(if (plan is InstallPlan.Takeover) R.string.install_takeover else R.string.install_silent)
                    },
                    color = ZapText2,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ZapButton(
                text = stringResource(if (switching) R.string.update_with_zapstore else R.string.install_confirm),
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().heightIn(min = ZapSize.controlLarge).testTag("installConfirm"),
            )
        }
    }
}

/** An indexed listing: the catalog key signed it. [source] is the repository the indexer tracks, when known. */
@Composable
internal fun CatalogSignedNote(
    name: String,
    downloadUrl: String?,
    modifier: Modifier = Modifier,
    source: String? = null,
) {
    val from = source?.trim().orEmpty()
    val glyph = mapOf(
        "indexed" to InlineTextContent(
            Placeholder(1.3.em, 1.em, PlaceholderVerticalAlign.TextCenter),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(INDEXED_GLYPH, modifier = Modifier.grayscale(), style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1), modifier = modifier) {
        Text(
            buildAnnotatedString {
                appendInlineContent("indexed", INDEXED_GLYPH)
                append(" ")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ZapText)) {
                    append(stringResource(R.string.catalog_indexed))
                    append(" ")
                }
                if (from.isEmpty()) {
                    append(stringResource(R.string.catalog_indexed_body, name))
                } else {
                    append(stringResource(R.string.catalog_indexed_from_lead, name))
                    // SpanStyle has no padding, so the spaces sit inside the highlight.
                    withStyle(SpanStyle(color = Color.White, background = Color.Black)) { append("\u00A0$from\u00A0") }
                    append(stringResource(R.string.catalog_indexed_from_tail))
                }
            },
            inlineContent = glyph,
            color = ZapText2,
            style = MaterialTheme.typography.bodyMedium,
        )
        downloadUrl?.let { url ->
            Text(
                stringResource(R.string.downloads_from),
                color = ZapText2,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                downloadLabel(url),
                color = ZapText,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("downloadUrl"),
            )
        }
    }
}

/** The URL without its scheme, so the host leads. */
internal fun downloadLabel(url: String): String = url.trim().substringAfter("://")

/**
 * The full report. The verdict first (flags, pills, prose) and the permissions, then the exact package
 * and file, and where the source is. Each identifier has a copy icon. Who published the app is the
 * author sheet, opened from the mark beside the name.
 */
@Composable
internal fun SecurityReportSheet(
    app: AppRecord,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space5),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5)
                .testTag("securityReportSheet"),
        ) {
            Text(stringResource(R.string.privacy_and_security), color = Color.White, style = MaterialTheme.typography.titleLarge)
            if (app.hasVerdict()) SecurityVerdict(app, onOpenUrl)
            if (app.hasPermissions()) {
                ReportSection(stringResource(R.string.report_permissions)) { PermissionsList(app) }
            }
            ReportSection(stringResource(R.string.report_package), Modifier.testTag("reportPackage")) {
                EvidenceField(stringResource(R.string.app_id), app.appId)
                app.apkHash?.let { EvidenceField(stringResource(R.string.apk_sha256), it) }
                app.certificateHash?.let { EvidenceField(stringResource(R.string.signing_certificate), it) }
                app.certificateChain().drop(1).forEach { EvidenceField(stringResource(R.string.previous_certificates), it) }
                app.downloadUrl?.let { EvidenceField(stringResource(R.string.download_url), it) }
            }
            if (app.repository != null || app.license != null) {
                ReportSection(stringResource(R.string.report_source), Modifier.testTag("reportSource")) {
                    app.repository?.let { EvidenceField(stringResource(R.string.repository), it, link = it, onOpenUrl = onOpenUrl) }
                    app.license?.let { EvidenceField(stringResource(R.string.license), it, copyable = false) }
                }
            }
        }
    }
}

/**
 * What the mark beside the app's name opens (the blue seal, the unverified mark or the indexed mark):
 * who published the app and whether they proved it is theirs, with their standing among people when they
 * did, or an invitation to claim the app when nobody has. Nothing about the app's privacy or security;
 * that is the report's job. The install sheet shows the same content from [PublisherStanding].
 */
@Composable
internal fun PublisherSheet(
    app: AppRecord,
    author: ProfileRecord?,
    proof: CertificateProof?,
    onOpenUrl: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onDismiss: () -> Unit,
    trust: TrustUi = TrustUi(),
    onPersonalizeTrust: () -> Unit = {},
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space4),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5)
                .testTag("authorSheet"),
        ) {
            Text(stringResource(R.string.publisher_title), color = Color.White, style = MaterialTheme.typography.titleLarge)
            PublisherStanding(
                app = app,
                author = author,
                trust = trust,
                onPersonalizeTrust = onPersonalizeTrust,
                onOpenUrl = onOpenUrl,
                full = true,
                proof = proof,
                onProfileClick = onProfileClick,
            )
        }
    }
}

/** A hairline, a small label, then the section's rows. */
@Composable
private fun ReportSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
        HorizontalDivider(color = ZapLine)
        SectionLabel(title, Modifier.padding(top = ZapSpacing.space1))
        content()
    }
}

/** The latest release: the fixed facts first, then the notes, which can run long. */
@Composable
internal fun ReleaseSheet(app: AppRecord, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5)
                .testTag("releaseSheet"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
                Text(
                    app.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                app.version.takeIf { it.isNotBlank() }?.let { VersionPill(it) }
            }
            InfoRow(stringResource(R.string.release_date), formatDate(app.releasedAt), verticalPadding = ZapSpacing.space1)
            app.sizeLabel.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.size), it, verticalPadding = ZapSpacing.space1) }
            app.versionCode.takeIf { it > 0 }?.let { InfoRow(stringResource(R.string.version_code), it.toString(), verticalPadding = ZapSpacing.space1) }
            app.channel?.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.channel), it, verticalPadding = ZapSpacing.space1) }
            HorizontalDivider(color = ZapLine)
            SectionLabel(stringResource(R.string.release_notes))
            if (app.releaseNotes.isNotBlank()) {
                MarkdownText(value = app.releaseNotes, color = ZapText2, style = MaterialTheme.typography.bodyLarge, onOpenUrl = onOpenUrl)
            } else {
                Text(stringResource(R.string.no_release_notes), color = ZapText2, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
