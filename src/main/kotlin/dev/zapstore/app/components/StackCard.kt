package dev.zapstore.app.components

import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface2
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapTitle3Style
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.StackRecord

/**
 * Up to four app icons, two on top of two. The first catalog app is in front at the bottom left; each icon
 * covers half of the one behind it, to the right and above. A canvas ring keeps them from melting together.
 */
@Composable
fun StackTile(appIds: List<String>, apps: Map<String, AppRecord>, size: Dp, modifier: Modifier = Modifier) {
    val (known, unknown) = appIds.partition(apps::containsKey)
    val shown = (known + unknown).take(4)
    val icon = size * if (shown.size == 1) STACK_LONE_FRACTION else STACK_ICON_FRACTION
    if (shown.isEmpty()) {
        Box(
            modifier
                .size(icon)
                .clip(RoundedCornerShape(icon * ZapRadius.appTileFraction))
                .background(ZapSurface2),
        )
        return
    }
    val rows = if (shown.size > 2) 2 else 1
    val cols = if (shown.size > 1) 2 else 1
    val step = icon * STACK_OVERLAP
    val leftDrop = if (cols > 1) icon * STACK_LEFT_DROP else 0.dp
    val topShift = if (rows > 1) icon * STACK_TOP_SHIFT else 0.dp
    Box(modifier.size(width = icon + step * (cols - 1) + topShift, height = icon + step * (rows - 1) + leftDrop)) {
        shown.indices.sortedWith(compareBy<Int> { stackSlot(it, shown.size).row }.thenByDescending { stackSlot(it, shown.size).col })
            .forEach { index ->
                val slot = stackSlot(index, shown.size)
                val app = apps[shown[index]]
                AppIcon(
                    title = app?.name ?: "",
                    iconUrl = app?.iconUrl,
                    iconFile = app?.iconFile,
                    size = icon,
                    modifier = Modifier
                        .offset(
                            x = step * slot.col + if (slot.row == 0) topShift else 0.dp,
                            y = step * slot.row + if (slot.col == 0) leftDrop else 0.dp,
                        )
                        .border(1.dp, ZapCanvas, RoundedCornerShape(icon * ZapRadius.appTileFraction)),
                )
            }
    }
}

/** Bottom row is in front. Within a row the left icon is in front. */
private data class StackSlot(val col: Int, val row: Int)

private fun stackSlot(index: Int, count: Int): StackSlot = when {
    count <= 2 -> StackSlot(index, 0)
    index <= 1 -> StackSlot(index, 1)
    else -> StackSlot(index - 2, 0)
}

/**
 * Home sizes each card to this fraction of the content width, so the next column peeks. A narrow phone gets
 * wider cards: at 45% a word like "Passwords" no longer fits beside the icon and breaks mid-word.
 */
fun stackShelfFraction(contentWidth: Dp): Float = if (contentWidth < NARROW_SHELF_WIDTH) 0.56f else 0.45f

private val NARROW_SHELF_WIDTH = 320.dp

/** Half the previous 80 dp tile, so the icons are half as big. */
private val StackCardTile = 40.dp

private const val STACK_ICON_FRACTION = 0.62f
private const val STACK_LONE_FRACTION = 0.78f
private const val STACK_OVERLAP = 0.5f
private const val STACK_LEFT_DROP = 0.25f
private const val STACK_TOP_SHIFT = 0.25f

/** Two lines of the row title (16 sp, line height 21.25). */
private val StackNameHeight = 42.5.dp

/** "See all", the gap, and two lines of about (14.75 sp, line height 21.25). */
private val SeeAllTextHeight = 21.25.dp + ZapSpacing.space1 + 42.5.dp

/** The width of a four-icon tile, the widest there is. */
private val StackTileSlotWidth = StackCardTile * STACK_ICON_FRACTION * (1 + STACK_OVERLAP + STACK_TOP_SHIFT)

private fun stackPileHeight(size: Dp, count: Int): Dp {
    val icon = size * if (count <= 1) STACK_LONE_FRACTION else STACK_ICON_FRACTION
    val rows = if (count > 2) 2 else 1
    val leftDrop = if (count > 1) icon * STACK_LEFT_DROP else 0.dp
    return icon + icon * STACK_OVERLAP * (rows - 1) + leftDrop
}

/** The vertical padding of a card on Home's shelf, tighter than the default so the shelf stands about 15% shorter. */
val StackShelfPadding = ZapSpacing.space1

/** The gap between the rows of Home's shelf. */
val StackShelfGap = ZapSpacing.space1

/** Rows in a stacks shelf: Home's, and anywhere else stacks show the same way (a profile, signed in or not). */
const val StacksShelfRows = 3

/** One shelf row: the icon pile, a two-line name, or the See all slot, plus the card's vertical padding. */
val StackCardHeight = maxOf(stackPileHeight(StackCardTile, 4), StackNameHeight, SeeAllTextHeight) + StackShelfPadding * 2

/**
 * The one stack card: Home's shelf, Profile's shelf, the Stacks list and a stack page's head.
 * [apps] is keyed by app ID and may lack entries not in the local catalog.
 * The card is the title. [supporting] and [byline] are optional lines under it, never the stack description.
 * [head] is the stack page: the same title, larger.
 */
@Composable
fun StackCard(
    stack: StackRecord,
    apps: Map<String, AppRecord>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    supporting: String? = null,
    byline: String? = null,
    head: Boolean = false,
    onShare: (() -> Unit)? = null,
    underName: (@Composable () -> Unit)? = null,
    count: Int? = null,
    verticalPadding: Dp = ZapSpacing.space2,
    flushStart: Boolean = false,
) {
    StackFace(
        name = stack.name.ifBlank { stack.identifier },
        appIds = stack.appIds,
        apps = apps,
        supporting = supporting,
        byline = byline,
        onClick = onClick,
        onShare = onShare,
        head = head,
        underName = underName,
        count = count,
        verticalPadding = verticalPadding,
        flushStart = flushStart,
        modifier = modifier,
    )
}

@Composable
internal fun StackFace(
    name: String,
    appIds: List<String>,
    apps: Map<String, AppRecord>,
    supporting: String? = null,
    modifier: Modifier = Modifier,
    byline: String? = null,
    onClick: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    head: Boolean = false,
    mark: (@Composable () -> Unit)? = null,
    underName: (@Composable () -> Unit)? = null,
    count: Int? = null,
    verticalPadding: Dp = ZapSpacing.space2,
    flushStart: Boolean = false,
) {
    Row(
        verticalAlignment = if (head) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(RoundedCornerShape(ZapRadius.md))
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .padding(vertical = verticalPadding),
    ) {
        if (mark != null) {
            mark()
        } else {
            // Every tile gets the width of the fullest one, so titles start at the same x whatever the app count.
            // On the shelf the pile sits at the card's left edge, so the leftmost icon lines up with the page's edge.
            Box(
                Modifier.width(StackTileSlotWidth),
                contentAlignment = when {
                    flushStart -> Alignment.CenterStart
                    head -> Alignment.TopCenter
                    else -> Alignment.Center
                },
            ) {
                StackTile(appIds, apps, size = StackCardTile)
            }
        }
        Column(
            Modifier.weight(1f).then(if (head) Modifier else Modifier.fillMaxHeight()),
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1, if (head) Alignment.Top else Alignment.CenterVertically),
        ) {
            if (count != null) {
                val appsLabel = pluralStringResource(R.plurals.curated_apps, count, count)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = if (head) ZapTitle3Style else MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    GroupCount(count, Modifier.clearAndSetSemantics { contentDescription = appsLabel })
                }
            } else {
                FitTitle(name, if (head) ZapTitle3Style else MaterialTheme.typography.titleSmall)
            }
            if (underName != null) underName()
            if (!supporting.isNullOrBlank()) {
                Text(
                    supporting,
                    color = ZapText2,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (byline != null) {
                Text(byline, color = ZapText2, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        onShare?.let { ShareButton(it) }
    }
}

/** The shelf's last slot: an arrow where the icons would be, and it opens every stack. */
@Composable
fun SeeAllStackCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = ZapSpacing.space2,
    flushStart: Boolean = false,
) {
    StackFace(
        name = stringResource(R.string.see_all),
        appIds = emptyList(),
        apps = emptyMap(),
        supporting = stringResource(R.string.every_stack),
        onClick = onClick,
        mark = { SeeAllMark() },
        verticalPadding = verticalPadding,
        flushStart = flushStart,
        modifier = modifier,
    )
}

/** The same outer size as a four-icon pile, so this slot lines up with the cards beside it. */
@Composable
private fun SeeAllMark() {
    val size = stackPileHeight(StackCardTile, 4)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * ZapRadius.appTileFraction))
            .background(ZapSurface2),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_right),
            contentDescription = null,
            tint = ZapText2,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Apps in [this] stack that the catalog lists; only these can be opened or installed. */
fun StackRecord.catalogCount(apps: Map<String, AppRecord>): Int = appIds.count(apps::containsKey)
