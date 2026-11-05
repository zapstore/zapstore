package dev.zapstore.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
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
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

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

/** Which entries Home's Activity lists. */
enum class ActivityFilter { All, Releases, Social }

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
                ActivityRow(item, profiles[item.pubkey], stackApps, catalogKeys, showWho, onClick = { onOpen(item) }, modifier = modifier)
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
 * One entry, led by what it is about, since apps and stacks come first here: the app's icon or the stack's
 * tile, then one line that reads "<app> [version] released by [avatar] <name>", "<app> received ⚡ N sats from
 * <name>", "<app> new comment from <name>" or "<stack> updated by <name>", with the time at the right edge.
 * A comment's text and a release's one-line about sit under the line, and a line that wraps, start where the
 * name starts, so everything lines up right of the icon. The "by <name>" part is left out when no profile
 * names the person, and on a person's own page ([showWho] false), where every entry is theirs. An entry made
 * by a catalog key says "by the catalog" in plain text, and a release by one says just "released".
 * An entry with no app or stack is on the page of the stack it belongs to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityRow(
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
    val byCatalog = item.pubkey in catalogKeys || (item.release && app?.isCatalogSigned == true)
    val named = showWho && !byCatalog && resolvedName(profile) != null
    /** "by <person>" or "from <person>"; "by the catalog" for a catalog key; nothing when no one can be named. */
    @Composable
    fun Who(word: Int, catalogWord: Int) {
        if (showWho && item.pubkey in catalogKeys) {
            Verb(stringResource(catalogWord))
        } else if (named) {
            Attribution(word) { Person(item.pubkey, profile) }
        }
    }
    val leading: (@Composable () -> Unit)? = when {
        stack != null && !item.release -> {
            { StackTile(stack.appIds, stackApps, size = ACTIVITY_ICON, modifier = Modifier.wrapContentSize(Alignment.Center, unbounded = true)) }
        }
        app != null -> {
            { AppIcon(title = app.name, iconUrl = app.iconUrl, iconFile = app.iconFile, size = ACTIVITY_ICON) }
        }
        else -> null
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(ACTIVITY_GAP),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
            .testTag("activity:${item.eventId}"),
    ) {
        // A box of the icon's width, so the lines below can start where the name does.
        if (leading != null) Box(Modifier.size(ACTIVITY_ICON), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    when {
                        item.release && app != null -> {
                            NameWithVersion(app.name, app.version)
                            Verb(stringResource(R.string.activity_released))
                            if (!byCatalog) Who(R.string.activity_by, R.string.activity_by_catalog)
                        }
                        stack != null -> {
                            Strong(stack.name)
                            Verb(stringResource(R.string.activity_updated))
                            Who(R.string.activity_by, R.string.activity_by_catalog)
                        }
                        app != null -> {
                            Strong(app.name)
                            if (item.sats != null) {
                                Verb(stringResource(R.string.activity_received))
                                Sats(item.sats)
                                Who(R.string.activity_from, R.string.activity_from_catalog)
                            } else {
                                Verb(stringResource(R.string.activity_new_comment))
                                Who(R.string.activity_from, R.string.activity_from_catalog)
                            }
                        }
                        item.sats != null -> {
                            Verb(stringResource(R.string.activity_zap))
                            Sats(item.sats)
                            Who(R.string.activity_from, R.string.activity_from_catalog)
                        }
                        else -> {
                            Verb(stringResource(R.string.activity_stack_comment))
                            Who(R.string.activity_from, R.string.activity_from_catalog)
                        }
                    }
                }
                Text(
                    agoLabel(item.createdAt),
                    color = ZapText3,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            if (item.release) {
                app?.aboutProse?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { about ->
                    Text(
                        about,
                        color = ZapText2,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item.comment?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = ZapText2,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** "by" or "from" and the one who did it, kept together so a line break never falls between them. */
@Composable
private fun Attribution(word: Int, who: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Verb(stringResource(word))
        who()
    }
}

/** A person as a small avatar and their name in bold. */
@Composable
private fun Person(pubkey: String, profile: ProfileRecord?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(ACTIVITY_ICON).clip(CircleShape).background(ZapSurface3)) {
            ProfileImage(
                pubkey = pubkey,
                pictureUrl = profile?.picture,
                profileVersion = profile?.eventId,
                contentDescription = null,
                modifier = Modifier.matchParentSize().testTag("profile:$pubkey"),
            )
        }
        Strong(shortDisplayName(pubkey, profile))
    }
}

/** A person's or app's name in the line: bold, one line, cut with an ellipsis when it is all there is room for. */
@Composable
private fun Strong(text: String, modifier: Modifier = Modifier) {
    Text(text, color = ZapText, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** What was done, in the quieter colour: "released", "received", "by", "from". */
@Composable
private fun Verb(text: String) {
    Text(text, color = ZapText2, style = MaterialTheme.typography.bodyMedium)
}

/** App name and version pill as one piece, so a line break falls before the pair, not inside it. */
@Composable
private fun NameWithVersion(name: String, version: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Strong(name, Modifier.weight(1f, fill = false))
        if (version.isNotBlank()) VersionPill(version, scale = 0.8f)
    }
}

@Composable
private fun Sats(sats: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, tint = ZapWarning, modifier = Modifier.size(14.dp))
        Text(
            pluralStringResource(R.plurals.activity_sats, sats.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), satsAmount(sats)),
            color = ZapWarning,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

/** `2,100`, `1.2K`: whole up to 9,999 and compact from 10,000, as the web page writes a zap. */
internal fun satsAmount(sats: Long): String {
    if (sats < 10_000) return NumberFormat.getIntegerInstance(Locale.US).format(sats)
    var scaled = sats.toDouble()
    var unit = 0
    while (scaled >= 1000 && unit < 3) {
        scaled /= 1000
        unit += 1
    }
    var rounded = Math.round(scaled * 10) / 10.0
    if (rounded >= 1000 && unit < 3) {
        rounded = 1.0
        unit += 1
    }
    val digits = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    return digits + "KMB"[unit - 1]
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
/** The person's picture and the app or stack picture in a line share one size. */
private val ACTIVITY_ICON = 20.dp
/** The gap between that picture and the name beside it. */
private val ACTIVITY_GAP = 6.dp
