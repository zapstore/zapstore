package dev.zapstore.app.components

import dev.zapstore.iolite.ProfileRecord
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface2
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.app.ZapTitle3Style
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.StackRecord

/** Four app tiles inside one tile, catalog apps first; inner tiles use 28% corners. */
@Composable
fun StackTile(appIds: List<String>, apps: Map<String, AppRecord>, size: Dp, modifier: Modifier = Modifier) {
    val gap = size * 0.06f
    val inner = (size - gap * 3) / 2
    Column(
        verticalArrangement = Arrangement.spacedBy(gap),
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * ZapRadius.appTileFraction))
            .background(ZapSurface2)
            .padding(gap),
    ) {
        val (known, unknown) = appIds.partition(apps::containsKey)
        (known + unknown).take(4).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                pair.forEach { appId ->
                    val app = apps[appId]
                    AppIcon(
                        title = app?.name ?: "",
                        iconUrl = app?.iconUrl,
                        iconFile = app?.iconFile,
                        size = inner,
                        cornerRadius = inner * 0.28f,
                    )
                }
            }
        }
    }
}

/** A curated stack on a shelf. [apps] is keyed by app ID and may lack entries not in the local catalog. */
@Composable
fun StackCard(
    stack: StackRecord,
    apps: Map<String, AppRecord>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .width(112.dp)
            .clip(RoundedCornerShape(ZapRadius.md))
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .padding(vertical = ZapSpacing.space1),
    ) {
        StackTile(stack.appIds, apps, size = 112.dp)
        Spacer(Modifier.size(ZapSpacing.space2))
        Text(
            text = stack.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = pluralStringResource(R.plurals.curated_apps, stack.catalogCount(apps), stack.catalogCount(apps)),
            color = ZapTextSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }
}

/** A stack in a horizontal shelf: tile, name, one line of description, and who curated it. */
@Composable
fun StackShelfCard(
    stack: StackRecord,
    apps: Map<String, AppRecord>,
    author: ProfileRecord?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = modifier
            .clip(RoundedCornerShape(ZapRadius.md))
            .clickable(onClick = onClick)
            .padding(vertical = ZapSpacing.space1),
    ) {
        StackTile(stack.appIds, apps, size = 80.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stack.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stack.description.ifBlank { pluralStringResource(R.plurals.curated_apps, stack.catalogCount(apps), stack.catalogCount(apps)) },
                color = ZapTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AuthorByline(pubkey = stack.pubkey, profile = author, showBy = false, modifier = Modifier.padding(top = ZapSpacing.space1))
        }
    }
}

/** Stack page head: the stack tile, name and description. */
@Composable
fun StackHead(stack: StackRecord, apps: Map<String, AppRecord>, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space4), modifier = modifier) {
        StackTile(stack.appIds, apps, size = 88.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1)) {
            Text(stack.name, style = ZapTitle3Style)
            Text(
                stack.description.ifBlank { pluralStringResource(R.plurals.curated_apps, stack.catalogCount(apps), stack.catalogCount(apps)) },
                color = ZapTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Apps in [this] stack that the catalog lists; only these can be opened or installed. */
fun StackRecord.catalogCount(apps: Map<String, AppRecord>): Int = appIds.count(apps::containsKey)
