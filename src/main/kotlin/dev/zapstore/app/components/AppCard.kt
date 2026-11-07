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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
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
import dev.zapstore.app.withFontScale
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
    colorFilter: ColorFilter? = null,
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
                colorFilter = colorFilter,
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
 * line 2 the tagline. No version. An app installed on this phone carries a tick on its icon, a clock when
 * an update is listed.
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
        Box {
            AppIcon(title = app.name, iconUrl = app.iconUrl, iconFile = app.iconFile, size = ROW_TILE)
            if (!onDevice && (installed != null || pendingFrom != null)) {
                InstalledBadge(
                    updateAvailable = updating,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = ROW_BADGE_OUT, y = ROW_BADGE_OUT).testTag("installedMark"),
                )
            }
        }
        // At a large font the two lines outgrow the tile, and the row grows with them.
        Column(Modifier.weight(1f).heightIn(min = ROW_TILE), verticalArrangement = Arrangement.SpaceBetween) {
            RowTitleLine(
                title = app.name,
                authorPubkey = authorPubkey,
                author = author,
                note = note,
            )
            Box(Modifier.fillMaxWidth().height(ROW_SECOND_LINE.withFontScale()), contentAlignment = Alignment.CenterStart) {
                if (onDevice) {
                    current?.let {
                        if (updating && listed != null) {
                            VersionPill(
                                it,
                                next = listed,
                                modifier = Modifier.testTag("updatePill:${app.appId}"),
                                status = VersionStatus.Update,
                            )
                        } else {
                            VersionPill(it, status = VersionStatus.Current)
                        }
                    }
                } else if (tagline != null) {
                    Text(
                        tagline,
                        color = ZapText2,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.25.sp),
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
 * Line 1: the name, then "by" the author or a note. Nothing draws past the row. An installed app's tick or
 * clock is a badge on its icon, not part of this line.
 */
@Composable
private fun RowTitleLine(
    title: String,
    authorPubkey: String?,
    author: ProfileRecord?,
    note: String?,
) {
    val titleStyle = MaterialTheme.typography.titleSmall.copy(fontSize = 17.5.sp, lineHeight = 23.25.sp)
    val asideStyle = MaterialTheme.typography.bodySmall
    val name = authorPubkey?.let { shortDisplayName(it, author) }
    val byWord = stringResource(R.string.row_by)
    Layout(
        modifier = Modifier.fillMaxWidth().height(ROW_TITLE_LINE.withFontScale()),
        content = {
            Text(title, color = ZapText, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        val noteMeasurable = if (note != null) measurables[cursor++] else null
        val by = if (authorPubkey != null) measurables[cursor++].measure(loose) else null
        val avatar = if (authorPubkey != null) measurables[cursor++].measure(loose) else null
        val nameNatural = if (authorPubkey != null) measurables[cursor].measure(loose) else null
        // A measurable is measured once, so the title and the note are sized from their intrinsic widths first.
        val titleWidth = measurables[0].maxIntrinsicWidth(constraints.maxHeight)
        val noteWidth = noteMeasurable?.maxIntrinsicWidth(constraints.maxHeight)

        // Width of everything after the title, including the gap that separates it from the title.
        fun tail(noteShown: Int, withName: Boolean): Int = when {
            noteWidth != null -> gap + noteShown
            by != null && avatar != null -> gap + by.width + inner + avatar.width + if (withName) inner + nameNatural!!.width else 0
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
            if (noteDrawn != null) place(noteDrawn, gap)
            else if (by != null && avatar != null) {
                place(by, gap)
                place(avatar, inner)
                if (showName && nameNatural != null) place(nameNatural, inner)
            }
        }
    }
}

/** The detail head tile: the 64 dp step of the app tile scale (28, 40, 48, 56, 64, 88). */
private val HEAD_ICON = 64.dp

/** How far the byline sits above the icon's bottom edge. */
private val HEAD_BYLINE_LIFT = 6.dp

/** Tall enough for the verification mark, which is a 28 dp target. */
private val HEAD_TITLE_LINE = 30.dp

/** Lets a child take [extra] more width than its parent offers, to the right. */
private fun Modifier.reachRight(extra: Dp): Modifier = layout { measurable, constraints ->
    val more = extra.roundToPx()
    val wide = constraints.maxWidth + more
    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
    layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
}

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
    /** Where an indexed app's file is released, shown in place of an author the catalog does not have. */
    source: String? = null,
    onSourceClick: (() -> Unit)? = null,
    /** The "..." menu, drawn at the head's top right. */
    more: (@Composable () -> Unit)? = null,
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
                    // 5% over the sheet-title size, so the name leads the page.
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.4.sp, lineHeight = 30.sp),
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
            // Lifted a little off the icon's bottom edge, which left the two lines far apart.
            Box(Modifier.padding(bottom = HEAD_BYLINE_LIFT)) {
                when {
                    authorPubkey != null -> AuthorByline(
                        pubkey = authorPubkey,
                        profile = author,
                        lead = stringResource(R.string.row_by),
                        onClick = { onAuthorClick(authorPubkey) },
                    )
                    // As the Flutter app did: "From", the known host's logo and short name, or the host; tapping opens the repository.
                    indexed && source != null -> SourceLine(
                        source,
                        lead = stringResource(R.string.source_from),
                        // The "..." menu sits above this line's right end, so the line may run under its lower edge.
                        modifier = Modifier.testTag("headIndexed").reachRight(ZapSpacing.space10),
                        whole = false,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        leadColor = ZapText3,
                        color = ZapText2,
                        onClick = onSourceClick,
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
        }
        // Nudged up and right so the dots sit level with the name and the icon's edge lines up with the page margin.
        more?.let { Box(Modifier.offset(x = ZapSpacing.space2, y = -ZapSpacing.space2)) { it() } }
    }
}

/** Title line. titleSmall is 15/20; the row's title is 10% larger, so 16.5/22. */
private val ROW_TITLE_LINE = 23.25.dp

/** Tagline, or the version pill, centered in this line. Tall enough for the pill, which is the same size everywhere. */
private val ROW_SECOND_LINE = 23.dp

/** The app-row tile from the tokens (48). The two lines are 42, so the column leaves 6 dp between them and the text still meets the icon. */
private val ROW_TILE = 48.dp

/** The author avatar. */
private val ROW_MARK = 16.dp

/** How far the installed badge hangs past the icon's bottom right corner. */
private val ROW_BADGE_OUT = 4.dp

/** Never blank: the tagline, else where the source lives, else the package id. */
internal fun AppRecord.rowLine(): String =
    tagline.ifEmpty {
        repository?.substringAfter("://")?.removePrefix("www.")?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: appId
    }

