package dev.zapstore.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3
import dev.zapstore.app.ZapWarning
import dev.zapstore.app.components.AppIcon
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.ProfileImage
import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.app.components.StackTile
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.resolvedName
import dev.zapstore.app.components.VersionPill
import dev.zapstore.iolite.ActivityRecord
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.StackRecord
import java.time.Instant
import java.time.ZoneOffset

/**
 * One entry of an activity list: a comment or a zap on an [app], a [stack] its curator published or
 * changed, or, with neither, a comment on the stack whose page lists it.
 */
data class ActivityItem(
    val eventId: String,
    val pubkey: String,
    val createdAt: Long,
    val app: AppRecord? = null,
    val comment: String? = null,
    val sats: Long? = null,
    val stack: StackRecord? = null,
    /** A new release of [app], dated by the release; [pubkey] is the app's author. */
    val release: Boolean = false,
)

/** Which entries Home's Activity lists: releases alone, or releases with comments, zaps and stacks. */
enum class ActivityFilter { All, Releases }

/** The app's latest release as an entry. */
internal fun AppRecord.releaseActivity(): ActivityItem =
    ActivityItem("release:$appId:$version", authorPubkey ?: eventPubkey, releasedAt, this, release = true)

/** The app ids a record's comments and zaps name. */
internal val ActivityRecord.appIds: Set<String>
    get() = (comments.mapNotNull { it.appId } + zaps.map { it.appId }).toSet()

/** The record's comments and zaps on apps the catalog lists, newest first. Others are left out. */
internal fun ActivityRecord.items(apps: Map<String, AppRecord>): List<ActivityItem> {
    val noted = comments.mapNotNull { comment ->
        comment.appId?.let(apps::get)?.let { ActivityItem(comment.eventId, comment.pubkey, comment.createdAt, it, comment = comment.content) }
    }
    val zapped = zaps.mapNotNull { zap ->
        apps[zap.appId]?.let { ActivityItem(zap.eventId, zap.pubkey, zap.createdAt, it, sats = zap.amountSats) }
    }
    return (noted + zapped).sortedByDescending { it.createdAt }
}

/** A stack its curator published or last changed, as an entry dated by that event. */
internal fun StackRecord.activity(): ActivityItem = ActivityItem(eventId, pubkey, updatedAt, stack = this)

/**
 * The entries of an activity section, under a header the caller draws: loading, empty, or the rows. A row
 * opens through [onOpen]. When older entries exist, [onLoadMore] makes a left-aligned "Load more" button
 * that asks for the next page; without it the caller scrolls them in and a spinner stands in for them.
 */
internal fun LazyListScope.activityRows(
    items: List<ActivityItem>,
    loading: Boolean,
    canLoadMore: Boolean,
    profiles: Map<String, ProfileRecord>,
    stackApps: Map<String, AppRecord>,
    /** Null leaves an empty section blank. */
    emptyText: String?,
    onOpen: (ActivityItem) -> Unit,
    /** Keys of the active catalogs: what they do reads "the catalog", never a profile name. */
    catalogKeys: Set<String> = emptySet(),
    /** False on a person's own page, where every entry is theirs and their name would only repeat. */
    showWho: Boolean = true,
    modifier: Modifier = Modifier,
    onLoadMore: (() -> Unit)? = null,
) {
    when {
        items.isEmpty() -> if (!loading && emptyText != null) {
            item(key = "noActivity", contentType = "empty") { EmptyRow(emptyText, modifier) }
        }
        else -> {
            // No rules between entries: the padding of each row is the only gap.
            itemsIndexed(items, key = { _, item -> "activity:${item.eventId}" }, contentType = { _, _ -> "activity" }) { _, item ->
                val releaseApp = item.app.takeIf { item.release }
                if (releaseApp != null) {
                    ReleaseRow(item, releaseApp, profiles[item.pubkey], showWho, catalogKeys, onClick = { onOpen(item) }, modifier = modifier)
                } else {
                    SocialRow(item, profiles[item.pubkey], stackApps, catalogKeys, showWho, onClick = { onOpen(item) }, modifier = modifier)
                }
            }
            if (canLoadMore) {
                if (onLoadMore != null) {
                    item(key = "activityMore", contentType = "more") {
                        ZapButton(
                            text = stringResource(R.string.load_more),
                            onClick = onLoadMore,
                            style = ZapButtonStyle.Tonal,
                            modifier = modifier.padding(top = ZapSpacing.space2).testTag("activityLoadMore"),
                        )
                    }
                } else {
                    item(key = "activityMore", contentType = "loading") {
                        LoadingIndicator(modifier.padding(vertical = ZapSpacing.space4).testTag("activityLoadingMore"))
                    }
                }
            }
        }
    }
}

/**
 * One release, led by the app's icon: "<app> [version] by [avatar] <name>" on one line with the time at the
 * right, and the app's [tagline][AppRecord.tagline] under it. The byline is left out on a person's own page
 * ([showWho] false), for a catalog-signed release, and when no profile names the author.
 */
@Composable
private fun ReleaseRow(
    item: ActivityItem,
    app: AppRecord,
    profile: ProfileRecord?,
    showWho: Boolean,
    catalogKeys: Set<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val named = showWho && item.pubkey !in catalogKeys && !app.isCatalogSigned && resolvedName(profile) != null
    Row(
        horizontalArrangement = Arrangement.spacedBy(ACTIVITY_APP_ICON_GAP),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = ACTIVITY_ROW_PADDING)
            .testTag("activity:${item.eventId}"),
    ) {
        AppIcon(title = app.name, iconUrl = app.iconUrl, iconFile = app.iconFile, size = ACTIVITY_APP_ICON)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ACTIVITY_GAP),
                    modifier = Modifier.weight(1f),
                ) {
                    // Unweighted, so the app's name keeps its width and only the author's name is cut.
                    Strong(app.name, weight = FontWeight.Bold)
                    if (app.version.isNotBlank()) VersionPill(app.version, scale = 0.8f)
                    if (named) {
                        Verb(stringResource(R.string.activity_by), style = AgoStyle)
                        Avatar(item.pubkey, profile, size = RELEASE_BYLINE_AVATAR)
                        Strong(shortDisplayName(item.pubkey, profile), Modifier.weight(1f, fill = false), style = AgoStyle)
                    }
                }
                Ago(item.createdAt)
            }
            app.tagline.takeIf { it.isNotEmpty() }?.let { SecondLine(it) }
        }
    }
}

/**
 * One comment, zap or stack update, led by who did it: "[avatar] <name> on [icon] <app>", "... [bolt] N sats
 * to [icon] <app>" or "... updated [tile] <stack>" on one line, and a comment's text under it. A catalog key
 * reads "The catalog"; anyone else with no profile name, or anyone on a person's own page ([showWho] false),
 * is left out. With no app or stack, the entry belongs to the stack whose page lists it.
 */
@Composable
private fun SocialRow(
    item: ActivityItem,
    profile: ProfileRecord?,
    stackApps: Map<String, AppRecord>,
    catalogKeys: Set<String>,
    showWho: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = item.app
    val stack = item.stack
    val byCatalog = showWho && item.pubkey in catalogKeys
    val hasAvatar = showWho && !byCatalog && resolvedName(profile) != null
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = ACTIVITY_ROW_PADDING)
            .testTag("activity:${item.eventId}"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ACTIVITY_GAP),
                modifier = Modifier.weight(1f),
            ) {
                if (byCatalog) Strong(stringResource(R.string.activity_the_catalog))
                if (hasAvatar) {
                    Avatar(item.pubkey, profile)
                    Strong(shortDisplayName(item.pubkey, profile))
                }
                when {
                    stack != null -> {
                        Verb(stringResource(R.string.activity_updated))
                        Target(stack.name, Modifier.weight(1f, fill = false)) {
                            StackTile(stack.appIds, stackApps, size = ACTIVITY_ICON)
                        }
                    }
                    app != null -> {
                        if (item.sats != null) {
                            Sats(item.sats)
                            Verb(stringResource(R.string.activity_to))
                        } else {
                            Verb(stringResource(R.string.activity_on))
                        }
                        Target(app.name, Modifier.weight(1f, fill = false)) {
                            AppIcon(title = app.name, iconUrl = app.iconUrl, iconFile = app.iconFile, size = ACTIVITY_ICON)
                        }
                    }
                    item.sats != null -> Sats(item.sats)
                    else -> Verb(stringResource(R.string.activity_stack_comment))
                }
            }
            Ago(item.createdAt)
        }
        item.comment?.takeIf { it.isNotBlank() }?.let {
            SecondLine(
                it,
                italic = true,
                // Starts under the name, past the avatar.
                modifier = Modifier.padding(start = if (hasAvatar) ACTIVITY_ICON + ACTIVITY_GAP else 0.dp),
            )
        }
    }
}

@Composable
private fun Ago(createdAt: Long) {
    Text(agoLabel(createdAt), color = ZapText3, style = AgoStyle, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
}

/** A row's about or comment, one line under the first. */
@Composable
private fun SecondLine(text: String, modifier: Modifier = Modifier, italic: Boolean = false) {
    Text(
        text,
        color = ZapText2,
        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = ACTIVITY_GAP),
    )
}

/** A small circular avatar, [ACTIVITY_ICON] wide unless [size] says otherwise. */
@Composable
private fun Avatar(pubkey: String, profile: ProfileRecord?, size: Dp = ACTIVITY_ICON) {
    Box(Modifier.size(size).clip(CircleShape).background(ZapSurface3)) {
        ProfileImage(
            pubkey = pubkey,
            pictureUrl = profile?.picture,
            profileVersion = profile?.eventId,
            contentDescription = null,
            modifier = Modifier.matchParentSize().testTag("profile:$pubkey"),
        )
    }
}

/** A stack's or app's small icon and its name; the name is what gives way to an ellipsis. */
@Composable
private fun Target(name: String, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ACTIVITY_GAP), modifier = modifier) {
        Box(Modifier.size(ACTIVITY_ICON), contentAlignment = Alignment.Center) { icon() }
        Strong(name, Modifier.weight(1f, fill = false))
    }
}

/** A name in the line, cut with an ellipsis when there is no room. */
@Composable
private fun Strong(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    weight: FontWeight = FontWeight.SemiBold,
) {
    Text(
        text,
        color = ZapText,
        style = style.copy(fontWeight = weight),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Body text at the label size. */
private val AgoStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(
        fontSize = MaterialTheme.typography.labelSmall.fontSize,
        lineHeight = MaterialTheme.typography.labelSmall.lineHeight,
    )

/** What was done, in the quieter colour: "by", "to", "on", "updated". */
@Composable
private fun Verb(text: String, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    Text(text, color = ZapText2, style = style)
}

@Composable
private fun Sats(sats: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, tint = ZapWarning, modifier = Modifier.size(14.dp))
        Text(
            pluralStringResource(R.plurals.activity_sats, sats.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), compactSats(sats)),
            color = ZapWarning,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

/** "just now", "5m ago", "13h ago", "1d ago", and the UTC date from 60 days on: the web page's `ago`. */
@Composable
internal fun agoLabel(createdAt: Long): String {
    val seconds = System.currentTimeMillis() / 1000 - createdAt
    return when {
        seconds < 60 -> stringResource(R.string.activity_just_now)
        seconds < 3_600 -> stringResource(R.string.activity_ago_minutes, seconds / 60)
        seconds < 24 * 3_600 -> stringResource(R.string.activity_ago_hours, seconds / 3_600)
        seconds < 60 * 86_400 -> stringResource(R.string.activity_ago_days, seconds / 86_400)
        else -> Instant.ofEpochSecond(createdAt).atZone(ZoneOffset.UTC).toLocalDate().toString()
    }
}

/** Calls [onLoadMore] once the last visible item of a list is within a few of the end. */
@Composable
internal fun LoadMoreNearEnd(position: () -> Pair<Int?, Int>, canLoadMore: Boolean, onLoadMore: () -> Unit) {
    // The item count rejoins the key so a page that lands still near the end asks for the next.
    val nearEndAt by remember {
        derivedStateOf {
            val (last, total) = position()
            if (last != null && total > 0 && last >= total - PREFETCH) total else null
        }
    }
    LaunchedEffect(nearEndAt, canLoadMore) { if (nearEndAt != null && canLoadMore) onLoadMore() }
}

private const val PREFETCH = 6
/** The avatars and app or stack icons inside a line. */
private val ACTIVITY_ICON = 20.dp
private val RELEASE_BYLINE_AVATAR = 18.dp
/** The app's icon leading a release row. */
private val ACTIVITY_APP_ICON = ZapSize.control
private val ACTIVITY_APP_ICON_GAP = 10.dp
/** Between the parts of a line, and between a row's two lines. */
private val ACTIVITY_GAP = 6.dp
private val ACTIVITY_ROW_PADDING = 14.dp
