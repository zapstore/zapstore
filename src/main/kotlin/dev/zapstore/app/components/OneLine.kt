package dev.zapstore.app.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A row that never wraps and never draws past its width. Children keep their natural width until the line
 * runs out of room; then the ones marked with [OneLineScope.shrink] give up width, lowest `rank` first, down
 * to their `min`. A child with a `hideBelow` that would end up narrower than that is dropped whole, so a name
 * is shown readable or not at all. Children without `shrink` (a version pill, a "by" and an avatar) are kept.
 */
@Composable
fun OneLine(
    modifier: Modifier = Modifier,
    gap: Dp = 6.dp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable OneLineScope.() -> Unit,
) {
    Layout(
        content = { OneLineScope.content() },
        modifier = modifier,
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val data = measurables.map { it.parentData as? Shrink }
        val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val minimum = data.mapIndexed { i, d -> if (d == null) natural[i] else d.min.roundToPx().coerceAtMost(natural[i]) }
        val hideBelow = data.map { it?.hideBelow?.roundToPx() }
        val visible = BooleanArray(measurables.size) { true }
        val widths = IntArray(measurables.size)
        val max = constraints.maxWidth

        fun allocate() {
            val shown = visible.indices.filter { visible[it] }
            natural.forEachIndexed { i, w -> widths[i] = if (visible[i]) w else 0 }
            var overflow = shown.sumOf { natural[it] } + gapPx * (shown.size - 1).coerceAtLeast(0) - max
            if (max == Int.MAX_VALUE || overflow <= 0) return
            for (i in shown.filter { data[it] != null }.sortedBy { data[it]!!.rank }) {
                val cut = minOf(overflow, widths[i] - minimum[i]).coerceAtLeast(0)
                widths[i] -= cut
                overflow -= cut
                if (overflow <= 0) break
            }
        }
        allocate()
        while (true) {
            val drop = visible.indices.filter { visible[it] && hideBelow[it] != null && widths[it] < hideBelow[it]!! }
                .minByOrNull { data[it]!!.rank } ?: break
            visible[drop] = false
            allocate()
        }

        val placeables = measurables.mapIndexed { i, m ->
            if (!visible[i]) null
            else m.measure(constraints.copy(minWidth = 0, maxWidth = widths[i].coerceAtLeast(0), minHeight = 0))
        }
        val shown = placeables.filterNotNull()
        val height = (shown.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = shown.sumOf { it.width } + gapPx * (shown.size - 1).coerceAtLeast(0)
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), height) {
            var x = 0
            shown.forEach { item ->
                item.placeRelative(x, verticalAlignment.align(item.height, height))
                x += item.width + gapPx
            }
        }
    }
}

object OneLineScope {
    /** See [OneLine]. A lower [rank] gives up its width first. */
    @Stable
    fun Modifier.shrink(rank: Int, min: Dp = 0.dp, hideBelow: Dp? = null): Modifier =
        this.then(ShrinkModifier(Shrink(rank, min, hideBelow)))
}

internal data class Shrink(val rank: Int, val min: Dp, val hideBelow: Dp?)

private class ShrinkModifier(private val shrink: Shrink) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = shrink
}
