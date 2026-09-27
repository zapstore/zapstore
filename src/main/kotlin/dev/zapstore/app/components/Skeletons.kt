package dev.zapstore.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zapstore.app.ZapSurface2

@Composable
fun SkeletonBlock(
    width: Dp,
    height: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .then(if (width == Dp.Infinity) Modifier.fillMaxWidth() else Modifier.width(width))
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(ZapSurface2),
    )
}

/** Three placeholder lines standing in for a paragraph. */
@Composable
fun ParagraphSkeleton(modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(vertical = 4.dp),
    ) {
        SkeletonBlock(width = Dp.Infinity, height = 16.dp, cornerRadius = 5.dp)
        SkeletonBlock(width = Dp.Infinity, height = 16.dp, cornerRadius = 5.dp)
        SkeletonBlock(width = 200.dp, height = 16.dp, cornerRadius = 5.dp)
    }
}
