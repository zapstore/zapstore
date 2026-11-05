package dev.zapstore.app.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zapstore.app.R
import dev.zapstore.app.ZapAction
import dev.zapstore.app.ZapActionPressed
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapActionTint
import dev.zapstore.app.ZapBrand
import dev.zapstore.app.ZapButtonShape
import dev.zapstore.app.ZapDanger
import dev.zapstore.app.ZapDangerFill
import dev.zapstore.app.ZapDangerTint
import dev.zapstore.app.ZapActionContainer
import dev.zapstore.app.ZapInk
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapLineStrong
import dev.zapstore.app.ZapOnAction
import dev.zapstore.app.ZapOnDangerFill
import dev.zapstore.app.ZapPaper
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapSurface2
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapSurface4
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3
import dev.zapstore.app.ZapVerified
import dev.zapstore.app.ZapVerifiedTint
import dev.zapstore.app.ZapWarning
import dev.zapstore.app.ZapWarningTint

// ---- Buttons ----
// 12 dp rectangles, 40 dp visual inside Material's 48 dp target. Disabled is a surface-2 fill with
// a text-3 label, never an opacity fade. Loading hides the label behind a 16 dp spinner.

private val ButtonPadding = PaddingValues(horizontal = 18.dp)

enum class ZapButtonStyle { Primary, Tonal, Outline, Ghost, Danger }

@Composable
fun ZapButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ZapButtonStyle = ZapButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: Int? = null,
    shape: Shape = ZapButtonShape,
    /** Color of [icon] when it should differ from the label, such as the amber bolt on Zap. */
    iconTint: Color? = null,
) {
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = if (style == ZapButtonStyle.Primary) ZapOnAction else ZapText2,
            )
        } else {
            icon?.let {
                Icon(painterResource(it), contentDescription = null, tint = iconTint ?: LocalContentColor.current, modifier = Modifier.size(18.dp))
                Box(Modifier.size(ZapSpacing.space2))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val clickable = enabled && !loading
    val sized = modifier.heightIn(min = ZapSize.button)
    when (style) {
        ZapButtonStyle.Outline -> OutlinedButton(
            onClick = onClick,
            enabled = clickable,
            shape = shape,
            border = BorderStroke(1.dp, ZapLineStrong),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = ZapText,
                disabledContainerColor = ZapSurface2,
                disabledContentColor = ZapText3,
            ),
            contentPadding = ButtonPadding,
            modifier = sized,
            content = content,
        )
        ZapButtonStyle.Ghost -> TextButton(
            onClick = onClick,
            enabled = clickable,
            shape = shape,
            colors = ButtonDefaults.textButtonColors(
                contentColor = ZapText2,
                disabledContentColor = ZapText3,
            ),
            contentPadding = ButtonPadding,
            modifier = sized,
            content = content,
        )
        else -> {
            val (container, onContainer) = when (style) {
                ZapButtonStyle.Primary -> ZapAction to ZapOnAction
                ZapButtonStyle.Tonal -> ZapSurface3 to ZapText
                else -> ZapDangerFill to ZapOnDangerFill
            }
            Button(
                onClick = onClick,
                enabled = clickable,
                shape = shape,
                border = if (style == ZapButtonStyle.Danger) BorderStroke(1.dp, ZapDanger.copy(alpha = 0.3f)) else null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = container,
                    contentColor = onContainer,
                    disabledContainerColor = if (loading) container else ZapSurface2,
                    disabledContentColor = if (loading) onContainer else ZapText3,
                ),
                contentPadding = ButtonPadding,
                modifier = sized,
                content = content,
            )
        }
    }
}

/** Primary button that fills left to right with [progress] while a download runs. */
@Composable
fun ZapProgressButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    enabled: Boolean = true,
) {
    val running = progress != null
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = ZapSize.button)
            .clip(ZapButtonShape)
            .background(if (running) ZapActionContainer else if (enabled) ZapAction else ZapSurface2)
            .clickable(enabled = enabled && !running, role = Role.Button, onClick = onClick),
    ) {
        if (progress != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind { drawRect(ZapAction, size = size.copy(width = size.width * progress.coerceIn(0f, 1f))) },
            )
        }
        Text(
            text,
            color = if (enabled || running) ZapOnAction else ZapText3,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

/** Square icon action beside a primary button, such as Open or Uninstall. */
@Composable
fun ZapIconButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ZapButtonStyle = ZapButtonStyle.Tonal,
) {
    val (container, content) = when (style) {
        ZapButtonStyle.Primary -> ZapAction to ZapOnAction
        ZapButtonStyle.Danger -> ZapDangerTint to ZapDanger
        ZapButtonStyle.Ghost -> Color.Transparent to ZapText2
        else -> ZapSurface3 to ZapText
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(ZapSize.controlLarge)
            .clip(ZapButtonShape)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

/** The text link for list-level actions such as "Update all". */
@Composable
fun ZapTextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = ZapButtonShape,
        colors = ButtonDefaults.textButtonColors(contentColor = ZapActionText, disabledContentColor = ZapText3),
        contentPadding = PaddingValues(horizontal = ZapSpacing.space2),
        modifier = modifier,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// ---- Badges and pills ----

enum class BadgeTone { Verified, Warning, Danger, Neutral }

/** 26 dp pill in a 12% tint of its color. Always words, never a bare icon or score. */
@Composable
fun ZapBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier, icon: Int? = null) {
    val (background, foreground) = when (tone) {
        BadgeTone.Verified -> ZapVerifiedTint to ZapVerified
        BadgeTone.Warning -> ZapWarningTint to ZapWarning
        BadgeTone.Danger -> ZapDangerTint to ZapDanger
        BadgeTone.Neutral -> ZapSurface3 to ZapText2
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 26.dp)
            .clip(RoundedCornerShape(ZapRadius.full))
            .background(background)
            .padding(start = if (icon != null) 8.dp else 10.dp, end = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icon?.let { Icon(painterResource(it), contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp)) }
            Text(text, color = foreground, style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.75.sp, lineHeight = 17.sp))
        }
    }
}

/** 30 dp pill. Selected: action tint, cobalt text, 55% cobalt border. [trailingIcon] marks what a tap does. */
@Composable
fun ZapChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leadingIcon: Int? = null,
    trailingIcon: Int? = null,
) {
    val foreground = if (selected) ZapActionText else ZapText2
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(ZapRadius.full))
            .background(if (selected) ZapActionTint else ZapSurface2)
            .then(if (selected) Modifier.border(1.dp, ZapAction.copy(alpha = 0.55f), RoundedCornerShape(ZapRadius.full)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (leadingIcon != null) 10.dp else 12.dp, end = if (trailingIcon != null) 8.dp else 12.dp),
    ) {
        leadingIcon?.let { Icon(painterResource(it), contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp)) }
        Text(text, color = foreground, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.75.sp, lineHeight = 17.sp))
        trailingIcon?.let { Icon(painterResource(it), contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp)) }
    }
}

/** Update counts: cobalt with white tabular digits. An update is an action, not an error. */
@Composable
fun ZapCountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 20.dp)
            .clip(RoundedCornerShape(ZapRadius.full))
            .background(ZapAction)
            .padding(horizontal = 6.dp),
    ) {
        Text(count.toString(), color = ZapOnAction, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * What a version pill says about the app on this phone, as a tiny icon inside the pill. The same three
 * states, and the same glyphs, as the Flutter app: can install, can update, already up to date.
 */
enum class VersionStatus(val icon: Int, val label: Int) {
    Install(R.drawable.ic_pill_install, R.string.version_status_install),
    Update(R.drawable.ic_update, R.string.version_status_update),
    Current(R.drawable.ic_pill_current, R.string.version_status_current),
}

/**
 * "Already installed" in a list row: the version pill's own tick, alone in a mini pill of the same
 * navy. [updateAvailable] swaps the tick for a clock, when the listing is newer than what's on the phone.
 */
@Composable
fun InstalledPill(modifier: Modifier = Modifier, updateAvailable: Boolean = false) {
    val label = stringResource(if (updateAvailable) R.string.installed_update_mark else R.string.installed_mark)
    val icon = if (updateAvailable) R.drawable.ic_clock else VersionStatus.Current.icon
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(width = 22.dp, height = 15.dp)
            .clip(RoundedCornerShape(ZapRadius.full))
            .background(ZapBrand)
            .semantics { contentDescription = label },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
    }
}

/**
 * Every version number sits in this navy pill, alone or as `current → next`. A [status] adds its icon
 * after the version, in white, and its words to what a screen reader hears.
 */
@Composable
fun VersionPill(
    version: String,
    modifier: Modifier = Modifier,
    next: String? = null,
    scale: Float = 1f,
    status: VersionStatus? = null,
    textScale: Float = scale,
) {
    val versions = if (next == null) version else "$version → $next"
    val spoken = if (status == null) versions else "$versions, ${stringResource(status.label)}"
    val fontSize = 13.75.sp * textScale
    val style = MaterialTheme.typography.labelMedium.copy(
        fontSize = fontSize,
        lineHeight = fontSize,
        fontWeight = FontWeight(680),
        fontFeatureSettings = "tnum",
    )
    val spacing = 4.dp * scale
    Layout(
        content = {
            Text(version.pillText(), color = Color.White, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (next != null) {
                VersionPillArrow(scale)
                Text(next.pillText(), color = Color.White, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (status != null) {
                Icon(painterResource(status.icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp * scale))
            }
        },
        modifier = modifier
            .height(24.dp * scale)
            .clip(RoundedCornerShape(ZapRadius.full))
            .background(ZapBrand)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(horizontal = 10.dp * scale),
    ) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity)
        // A measurable is measured once, so the fit is worked out from intrinsic widths first.
        val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val gaps = gap * (natural.size - 1).coerceAtLeast(0)
        val max = constraints.maxWidth
        val textSlots = if (next != null) setOf(0, 2) else setOf(0)
        val fits = max == Constraints.Infinity || natural.sum() + gaps <= max
        val fixed = natural.filterIndexed { index, _ -> index !in textSlots }.sum()
        val budget = (max - fixed - gaps).coerceAtLeast(0)
        val textWidth = textSlots.sumOf { natural[it] }.coerceAtLeast(1)
        val placeables = measurables.mapIndexed { index, measurable ->
            val shrink = !fits && index in textSlots
            measurable.measure(if (shrink) loose.copy(maxWidth = budget * natural[index] / textWidth) else loose)
        }
        val width = placeables.sumOf { it.width } + gaps
        val height = if (constraints.maxHeight == Constraints.Infinity) placeables.maxOf { it.height } else constraints.maxHeight
        layout(width, height) {
            var x = 0
            placeables.forEach { item ->
                item.placeRelative(x, (height - item.height) / 2)
                x += item.width + gap
            }
        }
    }
}

/** The small filled triangle between a pair's two versions, matching the web pill's inline SVG. */
@Composable
private fun VersionPillArrow(scale: Float) {
    Canvas(Modifier.size(6.dp * scale, 7.dp * scale)) {
        val path = Path().apply {
            moveTo(size.width * 0.2f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(size.width * 0.2f, size.height)
            close()
        }
        drawPath(path, color = Color.White.copy(alpha = 0.7f))
    }
}

// ---- Containers ----

/** Surface-2 card for checkable facts (Security, Releases). Never nested in another card. */
@Composable
fun EvidenceCard(
    title: String,
    modifier: Modifier = Modifier,
    contentPadding: Dp = ZapSpacing.space4,
    /** A text link at the end of the title row, for a card whose body is one line. */
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
        modifier = modifier
            .clip(RoundedCornerShape(ZapRadius.lg))
            .background(ZapSurface2)
            .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.lg))
            .padding(contentPadding),
    ) {
        val titleStyle = MaterialTheme.typography.titleSmall.copy(fontSize = 18.sp, lineHeight = 23.25.sp)
        if (headerAction == null) {
            Text(title, style = titleStyle)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = titleStyle, modifier = Modifier.weight(1f))
                headerAction()
            }
        }
        content()
    }
}

/** A row in an evidence card: a green check when the claim holds, a plain dot when it is only a fact. */
@Composable
fun EvidenceLine(text: String, checked: Boolean, modifier: Modifier = Modifier, detail: String? = null, warning: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2), modifier = modifier) {
        Icon(
            painterResource(if (checked) R.drawable.ic_check else if (warning) R.drawable.ic_unverified else R.drawable.ic_info),
            contentDescription = null,
            tint = if (checked) ZapVerified else if (warning) ZapWarning else ZapText3,
            modifier = Modifier.size(18.dp).padding(top = 1.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = ZapText)
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = ZapText2)
            }
        }
    }
}

/** Key and value; the value uses tabular figures so columns align. */
@Composable
fun EvidenceValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3), modifier = modifier.fillMaxWidth()) {
        Text(label, color = ZapText3, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f))
        Text(value, color = ZapText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(0.6f))
    }
}

/** Surface-1 row with an icon and one sentence. */
@Composable
fun EmptyRow(
    text: String,
    modifier: Modifier = Modifier,
    icon: Int = R.drawable.ic_info,
    iconTint: Color = ZapText3,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZapRadius.md))
            .background(ZapSurface1)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .padding(ZapSpacing.space4),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        Text(text, color = ZapText2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onClick != null) {
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = ZapText3, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * List-group header with an optional item count beside the title and an action link on the right.
 * [aside] follows the count and takes the free width; [trailing] sits on the right.
 */
@Composable
fun GroupHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    action: String? = null,
    onAction: () -> Unit = {},
    actionTag: String = "groupAction",
    aside: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 40.dp),
    ) {
        SectionLabel(title)
        count?.let { GroupCount(it) }
        if (aside != null) {
            Box(Modifier.weight(1f).padding(start = 2.dp)) { aside() }
        } else {
            Spacer(Modifier.weight(1f))
        }
        trailing?.invoke(this)
        if (action != null) ZapTextLink(action, onAction, Modifier.testTag(actionTag))
    }
}

/** Neutral 16 dp count beside a title; quieter than [ZapCountBadge], which marks updates. */
@Composable
internal fun GroupCount(count: Int, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 16.dp)
            .height(16.dp)
            .clip(RoundedCornerShape(ZapRadius.full))
            .background(ZapSurface3)
            .padding(horizontal = 5.dp),
    ) {
        Text(
            count.toString(),
            color = ZapText2,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 12.75.sp, fontFeatureSettings = "tnum"),
        )
    }
}

// ---- Settings rows ----

@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = ZapText,
    trailing: (@Composable () -> Unit)? = if (onClick != null) {
        { Icon(painterResource(R.drawable.ic_chevron_right), null, tint = ZapText3, modifier = Modifier.size(18.dp)) }
    } else {
        null
    },
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .padding(horizontal = ZapSpacing.space4, vertical = ZapSpacing.space3),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = titleColor, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, color = ZapText2, style = MaterialTheme.typography.bodySmall) }
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        modifier = modifier,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = ZapAction,
                    checkedThumbColor = Color.White,
                    checkedBorderColor = ZapAction,
                    uncheckedTrackColor = ZapSurface4,
                    uncheckedThumbColor = Color.White,
                    uncheckedBorderColor = ZapSurface4,
                ),
            )
        },
    )
}

/** Rows in one surface-1 group, separated by hairlines. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZapRadius.lg))
            .background(ZapSurface1)
            .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.lg)),
        content = content,
    )
}

// ---- Segmented control ----

/** Switches views of the same data; never navigates. Counts turn cobalt on the active segment. */
@Composable
fun <T> ZapSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    count: (T) -> Int? = { null },
    tag: (T) -> String = { "segment:$it" },
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(ZapRadius.full))
            .background(ZapSurface2)
            .padding(3.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 30.dp)
                    .clip(RoundedCornerShape(ZapRadius.full))
                    .background(if (active) ZapSurface4 else Color.Transparent)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(option) })
                    .padding(horizontal = ZapSpacing.space1)
                    .testTag(tag(option)),
            ) {
                Text(
                    label(option),
                    color = if (active) ZapText else ZapText2,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                count(option)?.takeIf { it > 0 }?.let {
                    Text(
                        " $it",
                        color = if (active) ZapActionText else ZapText3,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/**
 * A version short enough for the pill: semver build metadata (`+…`) is dropped, then anything
 * past [PILL_VERSION_MAX] characters is cut. The pill's content description keeps the full value.
 */
internal fun String.pillText(): String {
    val core = substringBefore('+').ifBlank { this }
    return if (core.length <= PILL_VERSION_MAX) core else core.take(PILL_VERSION_MAX - 1) + "…"
}

private const val PILL_VERSION_MAX = 16
