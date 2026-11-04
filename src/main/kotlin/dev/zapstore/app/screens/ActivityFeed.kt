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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapLine
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
)

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
 * The entries of an activity section, under a header the caller draws: loading, empty, or the rows with a
 * spinner while older ones are asked for. A row opens through [onOpen].
 */
internal fun LazyListScope.activityRows(
    items: List<ActivityItem>,
    loading: Boolean,
    canLoadMore: Boolean,
    profiles: Map<String, ProfileRecord>,
    stackApps: Map<String, AppRecord>,
    emptyText: String,
    onOpen: (ActivityItem) -> Unit,
    /** False on a person's own page, where every entry is theirs and their name would only repeat. */
    showWho: Boolean = true,
    modifier: Modifier = Modifier,
) {
    when {
        items.isEmpty() && loading -> item(key = "activityLoading", contentType = "loading") {
            LoadingIndicator(modifier.padding(vertical = ZapSpacing.space3))
        }
        items.isEmpty() -> item(key = "noActivity", contentType = "empty") {
            EmptyRow(emptyText, modifier)
        }
        else -> {
            itemsIndexed(items, key = { _, item -> "activity:${item.eventId}" }, contentType = { _, _ -> "activity" }) { index, item ->
                // The web list is ruled above its first entry and below every one.
                Column(modifier) {
                    if (index == 0) HorizontalDivider(color = ZapLine)
                    ActivityRow(item, profiles[item.pubkey], stackApps, showWho, onClick = { onOpen(item) })
                    HorizontalDivider(color = ZapLine)
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
 * One entry as the web page draws it: the person's picture, then one line that reads "<name> commented on
 * <app>", "<name> zapped ⚡ N sats to <app>" or "<name> updated the stack <stack>", with the time at the
 * right edge, and a comment or zap note under it. [showWho] is false on a person's own page, where the line
 * starts at what they did and the note lines up under it. An entry with no app or stack is on the page of the
 * stack it belongs to, so its line ends after the verb.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityRow(item: ActivityItem, profile: ProfileRecord?, stackApps: Map<String, AppRecord>, showWho: Boolean, onClick: () -> Unit) {
    val app = item.app
    val stack = item.stack
    fun lead(text: String) = if (showWho) text else text.replaceFirstChar { it.uppercase() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(ACTIVITY_GAP),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
            .testTag("activity:${item.eventId}"),
    ) {
        if (showWho) {
            Box(Modifier.padding(top = 1.dp).size(ACTIVITY_ICON).clip(CircleShape).background(ZapSurface3)) {
                ProfileImage(
                    pubkey = item.pubkey,
                    pictureUrl = profile?.picture,
                    profileVersion = profile?.eventId,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize().testTag("profile:${item.pubkey}"),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    if (showWho) Strong(shortDisplayName(item.pubkey, profile))
                    when {
                        stack != null -> {
                            Verb(lead(stringResource(R.string.activity_updated_stack)))
                            Subject(name = stack.name) { StackTile(stack.appIds, stackApps, size = ACTIVITY_ICON) }
                        }
                        app != null -> {
                            if (item.sats != null) {
                                Verb(lead(stringResource(R.string.activity_zapped)))
                                Sats(item.sats)
                                Verb(stringResource(R.string.activity_to))
                            } else {
                                Verb(lead(stringResource(R.string.activity_commented_on)))
                            }
                            Subject(name = app.name) { AppIcon(title = app.name, iconUrl = app.iconUrl, iconFile = app.iconFile, size = ACTIVITY_ICON) }
                        }
                        item.sats != null -> {
                            Verb(lead(stringResource(R.string.activity_zapped)))
                            Sats(item.sats)
                        }
                        else -> Verb(lead(stringResource(R.string.activity_commented)))
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
            item.comment?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = ZapText2,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp, start = if (showWho) 0.dp else ACTIVITY_ICON + ACTIVITY_GAP),
                )
            }
        }
    }
}

/** A person's or app's name in the line: bold, one line, cut with an ellipsis when it is all there is room for. */
@Composable
private fun Strong(text: String) {
    Text(text, color = ZapText, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** What was done, in the quieter colour: "commented on", "zapped", "to", "updated the stack". */
@Composable
private fun Verb(text: String) {
    Text(text, color = ZapText2, style = MaterialTheme.typography.bodyMedium)
}

/** The app or stack an entry is about: its small picture, then its name in bold. */
@Composable
private fun Subject(name: String, picture: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        picture()
        Strong(name)
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

/** "just now", "5m ago", "13h ago", "3d ago", and the UTC date from 60 days on: the web page's `ago`. */
@Composable
private fun agoLabel(createdAt: Long): String {
    val seconds = System.currentTimeMillis() / 1000 - createdAt
    return when {
        seconds < 60 -> stringResource(R.string.activity_just_now)
        seconds < 3_600 -> stringResource(R.string.activity_ago_minutes, seconds / 60)
        seconds < 48 * 3_600 -> stringResource(R.string.activity_ago_hours, seconds / 3_600)
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
/** The person's picture and the app or stack picture in a line share one size, and the gap after the person's. */
private val ACTIVITY_ICON = 20.dp
private val ACTIVITY_GAP = 10.dp
