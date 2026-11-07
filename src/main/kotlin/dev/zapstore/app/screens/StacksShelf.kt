package dev.zapstore.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.components.StackCard
import dev.zapstore.app.components.StackCardHeight
import dev.zapstore.app.components.StackShelfGap
import dev.zapstore.app.components.StackShelfPadding
import dev.zapstore.app.components.StacksShelfRows
import dev.zapstore.app.components.stackShelfFraction
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.StackRecord

/**
 * Stacks as Home's shelf: up to three rows, scrolling horizontally, each card sized to leave the next
 * column peeking. With fewer cards than rows the shelf has only as many rows as cards, so no blank space is left. Used on Home, and on a profile (signed in or not) so stacks read the same way
 * everywhere. [saved] leads the shelf when given: the device's own list beside anyone's curated ones.
 */
@Composable
fun StacksShelf(
    stacks: List<StackRecord>,
    apps: Map<String, AppRecord>,
    onStackClick: (StackRecord) -> Unit,
    modifier: Modifier = Modifier,
    saved: SavedApps? = null,
    onSavedClick: () -> Unit = {},
    horizontalPadding: Dp = ZapSpacing.space4,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val contentWidth = maxWidth - horizontalPadding * 2
        val shelfWidth = contentWidth * stackShelfFraction(contentWidth)
        val cards = (if (saved != null && saved.ids.isNotEmpty()) 1 else 0) + stacks.size
        val rows = cards.coerceIn(1, StacksShelfRows)
        LazyHorizontalGrid(
            rows = GridCells.Fixed(rows),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(StackShelfGap),
            verticalArrangement = Arrangement.spacedBy(StackShelfGap),
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            modifier = Modifier
                .fillMaxWidth()
                .height(StackCardHeight * rows + StackShelfGap * (rows - 1))
                .testTag("stackShelf"),
        ) {
            if (saved != null && saved.ids.isNotEmpty()) {
                item(key = "saved") {
                    SavedStackCard(saved, onSavedClick, Modifier.width(shelfWidth), flushStart = true, plate = true)
                }
            }
            items(stacks, key = { it.eventId }) { stack ->
                StackCard(
                    stack = stack,
                    apps = apps,
                    onClick = { onStackClick(stack) },
                    verticalPadding = StackShelfPadding,
                    flushStart = true,
                    plate = true,
                    modifier = Modifier.width(shelfWidth).testTag("stack:${stack.identifier}"),
                )
            }
        }
    }
}
