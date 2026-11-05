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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
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
import dev.zapstore.app.ZapText3
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

/** Version name by app id, for every catalog app installed on this device. */
val LocalInstalledVersions = compositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * One catalog listing. Always two lines, and the icon is exactly as tall as those two lines.
 *
 * **My apps** ([onDevice]): line 1 the name, then "by" the verified author when one exists; line 2 the
 * version pill, `installed → listed` when an update is pending. No tagline. A [note] (who installed it,
 * or that another store signed it) takes the author's place on line 1.
 *
 * **Everywhere else** (search, a stack, a profile, Saved): line 1 the name, then "by" the verified author;
 * line 2 the tagline. No version. An app installed on this phone carries a check after the name.
 *
 * Nothing overflows the row. The name ellipsizes. The author's name is shown in full or not at all: when
 * it does not fit, the row keeps "by" and the avatar. An indexed or unverified listing names nobody.
 */
@Composable
fun AppRow(
    app: AppRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDevice: Boolean = false,
    author: ProfileRecord? = null,
    installed: String? = LocalInstalledVersions.current[app.appId],
    pendingFrom: String? = LocalPendingUpdates.current[app.appId],
    note: String? = null,
    showAuthor: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val listed = app.version.takeIf { it.isNotBlank() }
    val current = (pendingFrom ?: installed ?: listed.takeIf { onDevice })?.takeIf { it.isNotBlank() }
    val updating = pendingFrom != null && listed != null && pendingFrom != listed
    val tagline = if (onDevice) null else app.rowLine().let { remember(it) { ZapMarkdown.parse(it).text } }.ifBlank { null }
    val authorPubkey = app.authorPubkey?.takeIf { showAuthor && note == null }
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
        AppIcon(title = app.name, iconUrl = app.iconUrl, iconFile = app.iconFile, size = ROW_TILE)
        Column(Modifier.weight(1f).height(ROW_TILE), verticalArrangement = Arrangement.SpaceBetween) {
            RowTitleLine(
                title = app.name,
                installed = !onDevice && (installed != null || pendingFrom != null),
                updateAvailable = !onDevice && updating,
                authorPubkey = authorPubkey,
                author = author,
                note = note,
            )
            Box(Modifier.fillMaxWidth().height(ROW_SECOND_LINE), contentAlignment = Alignment.CenterStart) {
                if (onDevice) {
                    current?.let {
                        if (updating && listed != null) {
                            VersionPill(
                                it,
                                next = listed,
                                modifier = Modifier.testTag("updatePill:${app.appId}"),
                                scale = ROW_PILL_SCALE,
                                status = VersionStatus.Update,
                            )
                        } else {
                            VersionPill(it, scale = ROW_PILL_SCALE, status = VersionStatus.Current)
                        }
                    }
                } else if (tagline != null) {
                    Text(
                        tagline,
                        color = ZapText2,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        trailing?.let { Box(Modifier.align(Alignment.CenterVertically)) { it() } }
    }
}

/**
 * Line 1: the name, then the installed mark, then "by" the author or a note. The mark is a tick
 * when the phone has the listed version, a clock when a newer one is listed. Nothing draws past the row.
 */
@Composable
private fun RowTitleLine(
    title: String,
    installed: Boolean,
    authorPubkey: String?,
    author: ProfileRecord?,
    note: String?,
    updateAvailable: Boolean = false,
) {
    val titleStyle = MaterialTheme.typography.titleSmall.copy(fontSize = 16.5.sp, lineHeight = 22.sp)
    val asideStyle = MaterialTheme.typography.bodySmall
    val name = authorPubkey?.let { shortDisplayName(it, author) }
    val byWord = stringResource(R.string.row_by)
    Layout(
        modifier = Modifier.fillMaxWidth().height(ROW_TITLE_LINE),
        content = {
            Text(title, color = ZapText, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (installed) InstalledPill(Modifier.testTag("installedMark"), updateAvailable = updateAvailable)
            if (note != null) {
                Text(note, color = ZapText3, style = asideStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else if (authorPubkey != null) {
                Text(byWord, color = ZapText2, style = asideStyle)
                Box(
                    Modifier
                        .size(ROW_MARK)
                        .clip(CircleShape)
                        .background(ZapSurface3)
                        .testTag("profile:$authorPubkey"),
                ) {
                    ProfileImage(
                        pubkey = authorPubkey,
                        pictureUrl = author?.picture,
                        profileVersion = author?.eventId,
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Text(name.orEmpty(), color = ZapText2, style = asideStyle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false)
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity)
        val gap = 8.dp.roundToPx()
        val inner = 6.dp.roundToPx()
        var cursor = 1
        val mark = if (installed) measurables[cursor++].measure(loose) else null
        val noteMeasurable = if (note != null) measurables[cursor++] else null
        val by = if (authorPubkey != null) measurables[cursor++].measure(loose) else null
        val avatar = if (authorPubkey != null) measurables[cursor++].measure(loose) else null
        val nameNatural = if (authorPubkey != null) measurables[cursor].measure(loose) else null
        // A measurable is measured once, so the title and the note are sized from their intrinsic widths first.
        val titleWidth = measurables[0].maxIntrinsicWidth(constraints.maxHeight)
        val noteWidth = noteMeasurable?.maxIntrinsicWidth(constraints.maxHeight)

        // Width of everything after the title, including the gap that separates it from the title.
        fun tail(noteShown: Int, withName: Boolean): Int = when {
            noteWidth != null && mark != null -> gap + mark.width + gap + noteShown
            noteWidth != null -> gap + noteShown
            by != null && avatar != null && mark != null -> gap + mark.width + gap + by.width + inner + avatar.width + if (withName) inner + nameNatural!!.width else 0
            by != null && avatar != null -> gap + by.width + inner + avatar.width + if (withName) inner + nameNatural!!.width else 0
            mark != null -> gap + mark.width
            else -> 0
        }
        val showName = nameNatural != null && by != null && titleWidth + tail(0, true) <= width
        val titleMax = when {
            noteWidth != null && titleWidth + tail(noteWidth, false) > width -> (width * 3 / 5).coerceAtMost(titleWidth)
            else -> (width - tail(noteWidth ?: 0, showName)).coerceAtLeast(0)
        }
        val titlePlaceable = measurables[0].measure(loose.copy(maxWidth = minOf(titleWidth, titleMax)))
        val noteMax = (width - titlePlaceable.width - tail(0, false)).coerceAtLeast(0)
        val noteDrawn = noteMeasurable?.measure(loose.copy(maxWidth = minOf(noteWidth!!, noteMax)))

        val height = constraints.maxHeight
        layout(width, height) {
            var x = 0
            fun place(item: androidx.compose.ui.layout.Placeable, before: Int) {
                x += before
                item.placeRelative(x, (height - item.height) / 2)
                x += item.width
            }
            place(titlePlaceable, 0)
            if (mark != null) place(mark, gap)
            if (noteDrawn != null) place(noteDrawn, gap)
            else if (by != null && avatar != null) {
                place(by, gap)
                place(avatar, inner)
                if (showName && nameNatural != null) place(nameNatural, inner)
            }
        }
    }
}

/** The detail head tile. */
private val HEAD_ICON = 56.dp

/** Tall enough for the verification mark, which is a 28 dp target. */
private val HEAD_TITLE_LINE = 28.dp

/**
 * App page head. The name and its verification mark top-align with the icon, "by" the author
 * bottom-aligns with it, so the two lines span the same height as the icon. A catalog-signed app
 * has no author: the indexed mark, and a line that says so. The version lives in the Latest
 * release card.
 */
@Composable
fun AppDetailHead(
    name: String,
    iconUrl: String?,
    modifier: Modifier = Modifier,
    iconFile: File? = null,
    authorPubkey: String? = null,
    author: ProfileRecord? = null,
    verified: Boolean = false,
    indexed: Boolean = false,
    onAuthorClick: (String) -> Unit = {},
    onMarkClick: () -> Unit = {},
    onMore: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space4),
        modifier = modifier.fillMaxWidth(),
    ) {
        AppIcon(title = name, iconUrl = iconUrl, iconFile = iconFile, size = HEAD_ICON)
        Column(
            // Top-aligns the name with the icon's top edge and the author line with its bottom edge,
            // so the two lines span the same height as the icon instead of floating centered in it.
            Modifier.weight(1f).height(HEAD_ICON),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().height(HEAD_TITLE_LINE),
            ) {
                Text(
                    name,
                    color = ZapText,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                when {
                    indexed -> IndexedMark(onMarkClick)
                    verified -> VerifiedSeal(onMarkClick)
                    authorPubkey != null -> UnverifiedMark(onMarkClick)
                }
            }
            when {
                authorPubkey != null -> AuthorByline(
                    pubkey = authorPubkey,
                    profile = author,
                    lead = stringResource(R.string.row_by),
                    onClick = { onAuthorClick(authorPubkey) },
                )
                indexed -> Text(
                    stringResource(R.string.indexed_author),
                    color = ZapText2,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.testTag("headIndexed"),
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

/** Title line. titleSmall is 15/20; the row's title is 10% larger, so 16.5/22. */
private val ROW_TITLE_LINE = 22.dp

/** Tagline, or the version pill. The pill is slightly shorter and is centered in this line. */
private val ROW_SECOND_LINE = 20.dp

/** The app-row tile from the tokens (48). The two lines are 42, so the column leaves 6 dp between them and the text still meets the icon. */
private val ROW_TILE = 48.dp

/** The author avatar. */
private val ROW_MARK = 16.dp

/** The row's pill is 20% smaller than the page-level pill, the same as in Activity. */
private const val ROW_PILL_SCALE = 0.8f

/** Never blank: the tagline, else where the source lives, else the package id. */
internal fun AppRecord.rowLine(): String =
    tagline.ifEmpty {
        repository?.substringAfter("://")?.removePrefix("www.")?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: appId
    }

