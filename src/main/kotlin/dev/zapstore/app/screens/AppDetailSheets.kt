package dev.zapstore.app.screens

import dev.zapstore.app.components.SwitchRow
import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.app.components.SettingsGroup
import dev.zapstore.app.components.EvidenceLine
import androidx.compose.foundation.background
import dev.zapstore.iolite.AppRecord
import dev.zapstore.app.components.pressScale
import dev.zapstore.app.components.AppIcon
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapActionTint
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapDanger
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.zapstore.app.components.GroupHeader
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.StatusText
import dev.zapstore.iolite.StackRecord
import dev.zapstore.app.components.VersionPill
import dev.zapstore.app.components.ZapBadge
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.install.InstallPlan

/** Surface-2 bottom sheet with a grabber and 28 dp top corners. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZapSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
        Box(Modifier.navigationBarsPadding()) { content() }
    }
}

/**
 * App actions: the subject row (28 dp tile, name, version), then square tiles. Save adds the app
 * to the device's Saved stack; Ignore updates appears only while the app is installed.
 */
@Composable
internal fun AppActionSheet(
    app: AppRecord,
    saved: Boolean,
    ignored: Boolean,
    installed: Boolean,
    onSave: () -> Unit,
    onIgnore: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    onReport: () -> Unit = {},
    onUninstall: (() -> Unit)? = null,
    onAddToStack: () -> Unit = {},
) {
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space4),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5)
                .testTag("appActions"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
                AppIcon(title = app.name, iconUrl = app.iconUrl, iconFile = app.iconFile, size = 28.dp)
                Text(
                    app.name,
                    color = ZapText,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                app.version.takeIf { it.isNotBlank() }?.let { VersionPill(it) }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            ) {
                val tile = Modifier.weight(1f).fillMaxHeight()
                ActionTile(
                    R.drawable.ic_bookmark,
                    stringResource(if (saved) R.string.action_saved else R.string.action_save),
                    onSave,
                    tile.testTag("actionSave"),
                    selected = saved,
                )
                if (installed || ignored) {
                    ActionTile(
                        R.drawable.ic_update,
                        stringResource(if (ignored) R.string.action_resume_updates else R.string.action_ignore_updates),
                        onIgnore,
                        tile.testTag("actionIgnore"),
                        selected = ignored,
                    )
                }
                ActionTile(R.drawable.ic_stack, stringResource(R.string.action_add_to_stack), onAddToStack, tile.testTag("actionAddToStack"))
                ActionTile(R.drawable.ic_share, stringResource(R.string.action_share), onShare, tile.testTag("actionShare"))
                ActionTile(R.drawable.ic_flag, stringResource(R.string.action_report), onReport, tile.testTag("actionReport"), danger = true)
            }
            onUninstall?.let {
                ZapButton(
                    text = stringResource(R.string.uninstall),
                    onClick = it,
                    style = ZapButtonStyle.Danger,
                    icon = R.drawable.ic_trash,
                    modifier = Modifier.fillMaxWidth().testTag("uninstallApp"),
                )
            }
        }
    }
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

/** 72 dp square: icon over a two-line label. Selected tiles take the action tint. */
@Composable
private fun ActionTile(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
) {
    val tint = when {
        danger -> ZapDanger
        selected -> ZapActionText
        else -> ZapText
    }
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        modifier = modifier
            .heightIn(min = 72.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(ZapRadius.md))
            .background(if (selected) ZapActionTint else ZapSurface3)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = ZapSpacing.space2, vertical = ZapSpacing.space3),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            label,
            color = tint,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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
 * Trust and install: verdict, what Android will ask, then Cancel and the one primary action.
 * [trustName] is the verified author, offered as "Always trust" on a first install.
 * A [catalogSigned] listing says Zapstore did not build it and names the original [downloadUrl].
 */
@Composable
internal fun InstallSheet(
    name: String,
    version: String,
    verified: Boolean,
    plan: InstallPlan,
    onConfirm: (alwaysTrust: Boolean) -> Unit,
    onDismiss: () -> Unit,
    otherStore: String? = null,
    trustName: String? = null,
    catalogSigned: Boolean = false,
    downloadUrl: String? = null,
    trust: TrustUi = TrustUi(),
    onPersonalizeTrust: () -> Unit = {},
) {
    val switching = plan is InstallPlan.Takeover && otherStore != null
    var alwaysTrust by rememberSaveable { mutableStateOf(false) }
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5),
        ) {
            Text(
                stringResource(if (plan is InstallPlan.FirstInstall) R.string.install_title else R.string.update_title, name),
                style = MaterialTheme.typography.titleLarge,
            )
            VersionPill(version)
            if (verified) ZapBadge(stringResource(R.string.verified_author), BadgeTone.Verified)
            if (catalogSigned) CatalogSignedNote(name, downloadUrl, Modifier.testTag("installCatalogSigned"))
            Text(
                if (switching) {
                    stringResource(R.string.install_takeover_store, otherStore.orEmpty())
                } else {
                    stringResource(
                        when (plan) {
                            InstallPlan.FirstInstall -> R.string.install_android_confirm
                            InstallPlan.Takeover -> R.string.install_takeover
                            else -> R.string.install_silent
                        },
                    )
                },
                color = ZapText2,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (plan is InstallPlan.FirstInstall && trustName != null && !catalogSigned) {
                TrustSignals(trustName, trust, onPersonalizeTrust, Modifier.testTag("trustSignals"))
            }
            if (plan is InstallPlan.FirstInstall && trustName != null) {
                SettingsGroup {
                    SwitchRow(
                        title = stringResource(R.string.always_trust, trustName),
                        subtitle = stringResource(R.string.always_trust_hint),
                        checked = alwaysTrust,
                        onCheckedChange = { alwaysTrust = it },
                        modifier = Modifier.testTag("alwaysTrust"),
                    )
                }
            }
            ZapButton(
                text = stringResource(if (switching) R.string.update_with_zapstore else R.string.install_confirm),
                onClick = { onConfirm(alwaysTrust) },
                modifier = Modifier.fillMaxWidth().heightIn(min = ZapSize.controlLarge).testTag("installConfirm"),
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
 * Who follows the publisher: the catalog's list, or the user's own after they ask for it. It is
 * absent when there is nothing to say, fills in without holding back Download, and follows are not
 * endorsements.
 */
@Composable
private fun TrustSignals(name: String, trust: TrustUi, onPersonalize: () -> Unit, modifier: Modifier = Modifier) {
    if (!trust.hasSignals && !trust.canPersonalize) return
    fun label(person: TrustPerson): String {
        val profile = trust.profiles[person.pubkey]
        return if (profile == null && person.name.isNotBlank()) person.name else shortDisplayName(person.pubkey, profile)
    }
    val lines = buildList {
        if (trust.youFollow) add(stringResource(R.string.trust_you_follow, name))
        if (trust.followers.isNotEmpty()) {
            add(
                stringResource(
                    if (trust.personal) R.string.trust_followed_by_yours else R.string.trust_followed_by,
                    trust.followers.joinToString(", ", transform = ::label),
                ),
            )
        }
        if (trust.zappers.isNotEmpty()) add(stringResource(R.string.trust_zapped_by, trust.zappers.joinToString(", ", transform = ::label)))
        if (trust.youZapped) add(stringResource(R.string.trust_you_zapped))
    }
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2), modifier = modifier) {
        if (trust.leaked) {
            EvidenceLine(
                stringResource(R.string.trust_leaked),
                checked = false,
                detail = stringResource(R.string.trust_leaked_body, name),
            )
        }
        lines.forEach { Text(it, color = ZapText, style = MaterialTheme.typography.bodyMedium) }
        if (trust.hasSignals && !trust.leaked) {
            Text(stringResource(R.string.trust_disclaimer), color = ZapText2, style = MaterialTheme.typography.bodySmall)
        }
        if (trust.canPersonalize && !trust.personal) {
            if (trust.failed) {
                Text(stringResource(R.string.trust_failed), color = ZapText2, style = MaterialTheme.typography.bodySmall)
            }
            ZapButton(
                text = stringResource(if (trust.checking) R.string.trust_checking else R.string.trust_personalize),
                onClick = onPersonalize,
                style = ZapButtonStyle.Outline,
                enabled = !trust.checking,
                modifier = Modifier.fillMaxWidth().testTag("trustPersonalize"),
            )
        }
    }
}

/** Zapstore lists this app but did not build or sign it; the APK comes from [downloadUrl]. */
@Composable
internal fun CatalogSignedNote(name: String, downloadUrl: String?, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1), modifier = modifier) {
        EvidenceLine(
            stringResource(R.string.catalog_signed),
            checked = false,
            detail = stringResource(R.string.catalog_signed_body, name),
        )
        downloadUrl?.let { url ->
            Text(
                stringResource(R.string.downloads_from),
                color = ZapText2,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 26.dp),
            )
            Text(
                downloadLabel(url),
                color = ZapText,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 26.dp).testTag("downloadUrl"),
            )
        }
    }
}

/** The URL without its scheme, so the host leads. */
internal fun downloadLabel(url: String): String = url.trim().substringAfter("://")

@Composable
internal fun MarkdownSheet(title: String, markdown: String, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            MarkdownText(value = markdown, color = ZapText2, style = MaterialTheme.typography.bodyLarge, onOpenUrl = onOpenUrl)
        }
    }
}

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
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = cdnImageUrl(screenshots[page], CdnImageVariant.ThumbnailLarge),
                    contentDescription = stringResource(R.string.screenshot_description, appName),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 48.dp),
                )
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
