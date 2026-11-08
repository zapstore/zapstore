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
import androidx.compose.ui.unit.sp
import dev.zapstore.app.R
import dev.zapstore.app.ZapMarkdown
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
import dev.zapstore.app.components.OneLine
import dev.zapstore.app.components.OneLineScope.shrink
import dev.zapstore.app.components.StackTile
import dev.zapstore.app.components.VersionPill
import dev.zapstore.app.components.versionStatusOf
import dev.zapstore.iolite.ActivityRecord
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.StackRecord
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
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
 * opens through [onOpen]. When older entries exist a spinner ends the list, and the caller pages them in as
 * it scrolls (see [LoadMoreNearEnd]).
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
                item(key = "activityMore", contentType = "loading") {
                    LoadingIndicator(modifier.padding(vertical = ZapSpacing.space4).testTag("activityLoadingMore"))
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
    // Only an author with a NIP-C1 proof is named; whoever merely signed the listing is not.
    val named = showWho && app.authorPubkey != null && item.pubkey !in catalogKeys
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
                // One line, always: the app's name is abbreviated first, then the author's name is cut and
                // finally left out. The version pill, "by" and the avatar stay whole.
                OneLine(modifier = Modifier.weight(1f), gap = ACTIVITY_GAP) {
                    Strong(app.name, Modifier.shrink(rank = 0, min = RELEASE_NAME_MIN), weight = FontWeight.Bold)
                    if (app.version.isNotBlank()) {
                        VersionPill(app.version, status = versionStatusOf(app), modifier = Modifier.shrink(rank = 2, min = 40.dp))
                    }
                    if (named) {
                        Verb(stringResource(R.string.activity_by), style = AgoStyle)
                        Avatar(item.pubkey, profile, size = RELEASE_BYLINE_AVATAR)
                        Strong(
                            shortDisplayName(item.pubkey, profile),
                            Modifier.shrink(rank = 1, hideBelow = AUTHOR_NAME_HIDE_BELOW),
                            style = AgoStyle,
                        )
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
 * reads "The catalog"; anyone else with no profile name reads as their short npub unless a zap opens the line with its
 * amount, and on a person's own page ([showWho] false) nobody is named. With no app or stack, the entry belongs to the stack whose page lists it.
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
    val hasAvatar = showWho && !byCatalog
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = ACTIVITY_ROW_PADDING)
            .testTag("activity:${item.eventId}"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
            // One line, always: the target's name is abbreviated first, then the person's.
            OneLine(modifier = Modifier.weight(1f), gap = ACTIVITY_GAP) {
                if (byCatalog) Strong(stringResource(R.string.activity_the_catalog))
                // A zap opens with its amount, so it needs no name. Everyone else leads with theirs.
                if (hasAvatar && item.sats == null) {
                    Avatar(item.pubkey, profile)
                    Strong(shortDisplayName(item.pubkey, profile), Modifier.shrink(rank = 1, min = PERSON_NAME_MIN))
                }
                when {
                    stack != null -> {
                        Verb(stringResource(if (showWho) R.string.activity_updated else R.string.activity_updated_lead))
                        Target(stack.name, Modifier.shrink(rank = 0, min = TARGET_MIN)) {
                            StackTile(stack.appIds, stackApps, size = ACTIVITY_ICON)
                        }
                    }
                    app != null -> {
                        if (item.sats != null) {
                            Sats(item.sats)
                            Verb(stringResource(R.string.activity_to))
                        } else {
                            // Someone's own page has no name to lead, so the verb does.
                            Verb(stringResource(if (showWho) R.string.activity_on else R.string.activity_commented_on))
                        }
                        Target(app.name, Modifier.shrink(rank = 0, min = TARGET_MIN)) {
                            AppIcon(title = app.name, iconUrl = app.iconUrl, iconFile = app.iconFile, size = ACTIVITY_ICON)
                        }
                    }
                    item.sats != null -> Sats(item.sats)
                    else -> Verb(stringResource(R.string.activity_stack_comment))
                }
            }
            Ago(item.createdAt)
        }
        // `nostr:nevent1…` and the like read as "note", a mentioned profile as `@npub1abc…wxyz`.
        item.comment?.let(ZapMarkdown::plain)?.takeIf { it.isNotBlank() }?.let {
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
    Text(agoLabel(createdAt), color = ZapText3, style = AgoTimeStyle, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
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

/** A stack's or app's small icon and its name, cut with an ellipsis when there is no room. */
@Composable
private fun Target(name: String, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ACTIVITY_GAP), modifier = modifier) {
        Box(Modifier.size(ACTIVITY_ICON), contentAlignment = Alignment.Center) { icon() }
        Strong(name, Modifier.weight(1f, fill = false))
    }
}

/** A name in the line, cut with an ellipsis after [maxLines] when there is no room. */
@Composable
private fun Strong(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    weight: FontWeight = FontWeight.SemiBold,
    maxLines: Int = 1,
) {
    Text(
        text,
        color = ZapText,
        style = style.copy(fontWeight = weight),
        maxLines = maxLines,
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

/** The time at the right of a row: the app's smallest text, the size of the count badge and the tab labels. */
private val AgoTimeStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, lineHeight = 12.75.sp, fontFeatureSettings = "tnum")

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

/** "just now", "5m ago", "13h ago", "1d ago", and the UTC date from 60 days on ("Jun 6, 2025" in English): the web page's `ago`. */
@Composable
internal fun agoLabel(createdAt: Long): String {
    val seconds = System.currentTimeMillis() / 1000 - createdAt
    return when {
        seconds < 60 -> stringResource(R.string.activity_just_now)
        seconds < 3_600 -> stringResource(R.string.activity_ago_minutes, seconds / 60)
        seconds < 24 * 3_600 -> stringResource(R.string.activity_ago_hours, seconds / 3_600)
        seconds < 60 * 86_400 -> stringResource(R.string.activity_ago_days, seconds / 86_400)
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
            .format(Instant.ofEpochSecond(createdAt).atZone(ZoneOffset.UTC))
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

/** The least an abbreviated app, stack or person name keeps; the author's name in a release goes whole or not at all. */
private val RELEASE_NAME_MIN = 72.dp
private val TARGET_MIN = 80.dp
private val PERSON_NAME_MIN = 56.dp
private val AUTHOR_NAME_HIDE_BELOW = 28.dp
/** The avatars and app or stack icons inside a line. */
/** Icons and avatars inside a line: 5% over the earlier 20 and 18 dp. */
private val ACTIVITY_ICON = 21.dp
private val RELEASE_BYLINE_AVATAR = 19.dp
/** The app's icon leading a release row: 5% over the control height (44 dp). */
private val ACTIVITY_APP_ICON = 46.dp
private val ACTIVITY_APP_ICON_GAP = 10.dp
/** Between the parts of a line, and between a row's two lines. */
private val ACTIVITY_GAP = 6.dp
private val ACTIVITY_ROW_PADDING = 14.dp
