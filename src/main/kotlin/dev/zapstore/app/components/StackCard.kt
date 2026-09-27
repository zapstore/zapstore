package dev.zapstore.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.StackRecord

/** A curated stack tile. [apps] is keyed by app ID and may lack entries not in the local catalog. */
@Composable
fun StackCard(
    stack: StackRecord,
    apps: Map<String, AppRecord>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .width(176.dp)
            .clip(RoundedCornerShape(ZapRadius.lg))
            .background(ZapSurface1)
            .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.lg))
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .padding(ZapSpacing.space4),
    ) {
        Text(
            text = stack.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stack.description.ifBlank {
                pluralStringResource(R.plurals.curated_apps, stack.apps.size, stack.apps.size)
            },
            color = ZapTextSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(ZapSpacing.space3))
        Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space1 + 2.dp)) {
            stack.appIds.take(3).forEach { appId ->
                val app = apps[appId]
                AppIcon(title = app?.name ?: "?", iconUrl = app?.iconUrl, iconFile = app?.iconFile, size = 34.dp)
            }
        }
    }
}
