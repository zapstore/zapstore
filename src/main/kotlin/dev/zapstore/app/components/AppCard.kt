package dev.zapstore.app.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.zapstore.app.CdnImageVariant
import dev.zapstore.app.R
import dev.zapstore.app.ZapMarkdown
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapTitle3Style
import dev.zapstore.app.ZapVerified
import dev.zapstore.app.cdnImageUrl
import dev.zapstore.app.isHttpUrl
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.ProfileRecord
import java.io.File

/** App tile at 22% corners. Until the image loads it shows the first letter on surface-3. */
@Composable
fun AppIcon(
    title: String,
    iconUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    iconFile: File? = null,
    cornerRadius: Dp = size * ZapRadius.appTileFraction,
) {
    val remoteReady = rememberRemoteImagesReady()
    val model = when {
        iconFile != null && iconFile.isFile -> iconFile
        remoteReady -> iconUrl?.takeIf(::isHttpUrl)?.let { cdnImageUrl(it, CdnImageVariant.Icon) }
        else -> null
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(ZapSurface3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.take(1).uppercase(),
            color = ZapText2,
            style = if (size >= 56.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.app_icon_description, title),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Installed version by app id, for each listing that has a pending update. */
val LocalPendingUpdates = compositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * One catalog listing: tile, name, version pill and C1 author, description, optional trailing slot.
 * The pill reads `installed → listed` when this device has an update pending for the app.
 * The author shows only when a current NIP-C1 proof names one; [author] is that key's profile when stored.
 */
@Composable
fun AppRow(
    app: AppRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = app.rowLine(),
    tileSize: Dp = 60.dp,
    version: String? = app.version,
    supportingLines: Int = 1,
    author: ProfileRecord? = null,
    installed: String? = LocalPendingUpdates.current[app.appId],
    showAuthor: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(RoundedCornerShape(ZapRadius.md))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(vertical = ZapSpacing.space3),
    ) {
        AppIcon(title = app.name, iconUrl = app.iconUrl, iconFile = app.iconFile, size = tileSize)
        Column(Modifier.weight(1f)) {
            Text(
                app.name,
                color = ZapText,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val description = supporting?.takeIf { it.isNotBlank() }?.let { remember(it) { ZapMarkdown.parse(it).text } }
            if (description != null) {
                Text(
                    description,
                    color = ZapText2,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = supportingLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val pill = version?.takeIf { it.isNotBlank() }
            val authorPubkey = app.authorPubkey?.takeIf { showAuthor }
            if (pill != null || authorPubkey != null) {
                FactsLine(Modifier.padding(top = if (description != null) ZapSpacing.space2 else 6.dp)) {
                    pill?.let {
                        if (installed != null && installed != it) {
                            VersionPill(installed, next = it, modifier = Modifier.testTag("updatePill:${app.appId}"))
                        } else {
                            VersionPill(it)
                        }
                    }
                    authorPubkey?.let { AuthorByline(pubkey = it, profile = author, showBy = false) }
                }
            }
        }
        trailing?.let { Box(Modifier.align(Alignment.CenterVertically)) { it() } }
    }
}

/** One listing in a horizontal shelf: tile, name and one line of description. */
@Composable
fun AppShelfTile(app: AppRecord, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(ZapRadius.md))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .padding(vertical = ZapSpacing.space1),
    ) {
        AppIcon(title = app.name, iconUrl = app.iconUrl, iconFile = app.iconFile, size = 56.dp)
        Column(Modifier.weight(1f)) {
            Text(app.name, color = ZapText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val description = app.rowLine().takeIf { it.isNotBlank() }?.let { remember(it) { ZapMarkdown.parse(it).text } }
            if (description != null) {
                Text(
                    description,
                    color = ZapText2,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * Version pill, then author, on one line. The pill keeps its width; the author takes the rest
 * and ellipsizes. When less than [minAuthor] is left (a long update pair), the author moves
 * to its own line instead of being crushed.
 */
@Composable
private fun FactsLine(
    modifier: Modifier = Modifier,
    gap: Dp = ZapSpacing.space3,
    lineGap: Dp = 6.dp,
    minAuthor: Dp = 88.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        if (measurables.size < 2) {
            val only = measurables.firstOrNull()?.measure(loose)
            return@Layout layout(only?.width ?: 0, only?.height ?: 0) { only?.placeRelative(0, 0) }
        }
        val pill = measurables[0].measure(loose)
        val left = width - pill.width - gap.roundToPx()
        if (left >= minAuthor.roundToPx()) {
            val author = measurables[1].measure(loose.copy(maxWidth = left))
            val height = maxOf(pill.height, author.height)
            layout(width, height) {
                pill.placeRelative(0, (height - pill.height) / 2)
                author.placeRelative(pill.width + gap.roundToPx(), (height - author.height) / 2)
            }
        } else {
            val author = measurables[1].measure(loose)
            val top = pill.height + lineGap.roundToPx()
            layout(width, top + author.height) {
                pill.placeRelative(0, 0)
                author.placeRelative(0, top)
            }
        }
    }
}

/** App page head: 64 dp tile, name, version pill, release date, and the C1 author when a proof names one. */
@Composable
fun AppDetailHead(
    name: String,
    iconUrl: String?,
    version: String?,
    modifier: Modifier = Modifier,
    iconFile: File? = null,
    caption: String? = null,
    authorPubkey: String? = null,
    author: ProfileRecord? = null,
    onAuthorClick: (String) -> Unit = {},
    onMore: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space4),
        modifier = modifier.fillMaxWidth(),
    ) {
        AppIcon(title = name, iconUrl = iconUrl, iconFile = iconFile, size = 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
            Text(name, style = ZapTitle3Style, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                version?.takeIf { it.isNotBlank() }?.let { VersionPill(it) }
                caption?.let {
                    Text(it, color = ZapText2, style = MaterialTheme.typography.bodySmall, maxLines = 1, softWrap = false)
                }
            }
            authorPubkey?.let {
                AuthorByline(
                    pubkey = it,
                    profile = author,
                    onClick = { onAuthorClick(it) },
                    modifier = Modifier.heightIn(min = 32.dp),
                )
            }
        }
        onMore?.let {
            ZapIconButton(
                R.drawable.ic_more,
                stringResource(R.string.more_actions),
                it,
                style = ZapButtonStyle.Ghost,
                modifier = Modifier.offset(x = ZapSpacing.space2).testTag("appMore"),
            )
        }
    }
}

/** Never blank: the tagline, else where the source lives, else the package id. */
internal fun AppRecord.rowLine(): String =
    tagline.ifEmpty {
        repository?.substringAfter("://")?.removePrefix("www.")?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: appId
    }

