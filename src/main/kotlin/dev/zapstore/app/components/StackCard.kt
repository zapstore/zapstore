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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/** Home sizes each card to this fraction of the content width, so the next column peeks. */
const val StackShelfFraction = 0.45f

/** Half the previous 80 dp tile, so the icons are half as big. */
private val StackCardTile = 40.dp

private const val STACK_ICON_FRACTION = 0.62f
private const val STACK_LONE_FRACTION = 0.78f
private const val STACK_OVERLAP = 0.5f
private const val STACK_LEFT_DROP = 0.25f
private const val STACK_TOP_SHIFT = 0.25f

/** Two lines of the row title (15 sp, line height 20). The shelf is at least this tall. */
private val StackNameHeight = 40.dp

private fun stackPileHeight(size: Dp, count: Int): Dp {
    val icon = size * if (count <= 1) STACK_LONE_FRACTION else STACK_ICON_FRACTION
    val rows = if (count > 2) 2 else 1
    val leftDrop = if (count > 1) icon * STACK_LEFT_DROP else 0.dp
    return icon + icon * STACK_OVERLAP * (rows - 1) + leftDrop
}

/** One shelf row: the icon pile or a two-line name, plus the card's vertical padding. Two of these, and one [ZapSpacing.space3], are the Home shelf. */
val StackCardHeight = maxOf(stackPileHeight(StackCardTile, 4), StackNameHeight) + ZapSpacing.space2 * 2

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
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(RoundedCornerShape(ZapRadius.md))
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .padding(vertical = ZapSpacing.space2),
    ) {
        StackTile(appIds, apps, size = StackCardTile)
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1, Alignment.CenterVertically),
        ) {
            Text(
                name,
                style = if (head) ZapTitle3Style else MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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

/** Apps in [this] stack that the catalog lists; only these can be opened or installed. */
fun StackRecord.catalogCount(apps: Map<String, AppRecord>): Int = appIds.count(apps::containsKey)
