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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zapstore.app.R
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
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
                    colorFilter = StackIconFilter,
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

/** Stack icons keep 75% of their saturation, so a pile reads quieter than a single app icon. */
private val StackIconFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.75f) })

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

/** The stack list and a stack page use this tile. */
private val StackCardTile = 40.dp

/** The shelf tile, a step under [StackCardTile]. */
private val StackShelfTile = 32.dp

private const val STACK_ICON_FRACTION = 0.62f
private const val STACK_LONE_FRACTION = 0.78f
private const val STACK_OVERLAP = 0.5f
private const val STACK_LEFT_DROP = 0.25f
private const val STACK_TOP_SHIFT = 0.25f

/** Two lines of the shelf title. Label size (14.75 sp) on the caption line (19), the step under the 16/21.25 row title. */
private val StackShelfLine = 19.dp
private val StackNameHeight = StackShelfLine * 2

/** The width of a four-icon tile, the widest there is. */
private fun tileSlotWidth(size: Dp) = size * STACK_ICON_FRACTION * (1 + STACK_OVERLAP + STACK_TOP_SHIFT)

private val StackTileSlotWidth = tileSlotWidth(StackCardTile)

private fun stackPileHeight(size: Dp, count: Int): Dp {
    val icon = size * if (count <= 1) STACK_LONE_FRACTION else STACK_ICON_FRACTION
    val rows = if (count > 2) 2 else 1
    val leftDrop = if (count > 1) icon * STACK_LEFT_DROP else 0.dp
    return icon + icon * STACK_OVERLAP * (rows - 1) + leftDrop
}

/** Inset inside a shelf card. Snug, so the plate sits close to the icon and the title. */
val StackShelfPadding = 9.dp

/** The gap between shelf cards, the same horizontally and vertically. */
val StackShelfGap = ZapSpacing.space3

/** Rows in a stacks shelf: Home's, and anywhere else stacks show the same way (a profile, signed in or not). */
const val StacksShelfRows = 3

/** One shelf row: the icon pile, a two-line name, or the See all slot, plus the card's vertical padding. */
val StackCardHeight = maxOf(stackPileHeight(StackShelfTile, 4), StackNameHeight) + StackShelfPadding * 2

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
    plate: Boolean = false,
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
        plate = plate,
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
    underName: (@Composable () -> Unit)? = null,
    count: Int? = null,
    verticalPadding: Dp = ZapSpacing.space2,
    flushStart: Boolean = false,
    plate: Boolean = false,
) {
    Row(
        verticalAlignment = if (head) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(RoundedCornerShape(ZapRadius.md))
            .then(if (plate) Modifier.background(ZapSurface1) else Modifier)
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .then(if (plate) Modifier.padding(StackShelfPadding) else Modifier.padding(vertical = verticalPadding)),
    ) {
        val tile = if (plate) StackShelfTile else StackCardTile
        // Every tile gets the width of the fullest one, so titles start at the same x whatever the app count.
        // On the shelf the pile sits at the card's left edge, inset when the card has a plate.
        Box(
            Modifier.width(if (plate) tileSlotWidth(tile) else StackTileSlotWidth),
            contentAlignment = when {
                flushStart -> Alignment.CenterStart
                head -> Alignment.TopCenter
                else -> Alignment.Center
            },
        ) {
            StackTile(appIds, apps, size = tile)
        }
        val titleStyle = when {
            head -> ZapTitle3Style
            plate -> MaterialTheme.typography.labelLarge.copy(lineHeight = StackShelfLine.value.sp)
            else -> MaterialTheme.typography.titleSmall
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
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    GroupCount(count, Modifier.clearAndSetSemantics { contentDescription = appsLabel })
                }
            } else {
                FitTitle(name, titleStyle)
            }
            if (underName != null) underName()
            if (!supporting.isNullOrBlank()) {
                Text(
                    supporting,
                    color = ZapText2,
                    style = if (plate) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    maxLines = if (plate) 1 else 2,
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

/** Apps in [this] stack that the catalog lists; only these can be opened or installed. */
fun StackRecord.catalogCount(apps: Map<String, AppRecord>): Int = appIds.count(apps::containsKey)
