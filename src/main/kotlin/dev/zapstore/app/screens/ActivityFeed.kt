package dev.zapstore.app.screens

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText3
import dev.zapstore.app.ZapWarning
import dev.zapstore.app.components.AppIcon
import dev.zapstore.app.components.AuthorByline
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.LoadingIndicator
import dev.zapstore.app.components.StackTile
import dev.zapstore.iolite.ActivityRecord
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.StackRecord
import java.text.NumberFormat

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
                Column(modifier) {
                    if (index > 0) HorizontalDivider(color = ZapLine, modifier = Modifier.padding(start = if (item.leads) ACTIVITY_TEXT_INSET else 0.dp))
                    ActivityRow(item, profiles[item.pubkey], stackApps, showWho, onClick = { onOpen(item) })
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

/** A row that starts with a picture of its subject (an app or a stack) rather than with a person. */
private val ActivityItem.leads: Boolean get() = app != null || stack != null

/**
 * The subject leads (icon, name, time) and the second line says who did what, with their picture; a comment
 * follows as the row's content. An entry with no subject is a comment on the stack whose page it is on, so
 * the person leads.
 */
@Composable
private fun ActivityRow(item: ActivityItem, profile: ProfileRecord?, stackApps: Map<String, AppRecord>, showWho: Boolean, onClick: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = ZapSpacing.space3)
            .testTag("activity:${item.eventId}"),
    ) {
        val app = item.app
        val stack = item.stack
        when {
            stack != null -> StackTile(stack.appIds, stackApps, size = ACTIVITY_ICON)
            app != null -> AppIcon(title = app.name, iconUrl = app.iconUrl, iconFile = app.iconFile, size = ACTIVITY_ICON)
        }
        Column(Modifier.weight(1f)) {
            if (item.leads) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                    Text(
                        stack?.name ?: app?.name.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Time(item.createdAt)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    if (showWho) AuthorByline(item.pubkey, profile, showBy = false, modifier = Modifier.weight(1f, fill = false))
                    What(item)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AuthorByline(item.pubkey, profile, showBy = false, modifier = Modifier.weight(1f, fill = false))
                    What(item)
                    Time(item.createdAt, Modifier.weight(1f), end = true)
                }
            }
            item.comment?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = ZapText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = ZapSpacing.space2),
                )
            }
        }
    }
}

/** What the entry is: "commented", "zapped ⚡ N sats", or "updated the stack". */
@Composable
private fun What(item: ActivityItem) {
    val style = MaterialTheme.typography.bodySmall
    when {
        item.stack != null -> Text(stringResource(R.string.activity_updated_stack), color = ZapText3, style = style, maxLines = 1)
        item.sats != null -> {
            Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, tint = ZapWarning, modifier = Modifier.size(14.dp))
            Text(
                pluralStringResource(R.plurals.activity_sats, item.sats.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), NumberFormat.getIntegerInstance().format(item.sats)),
                color = ZapWarning,
                style = style.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
        }
        else -> Text(stringResource(R.string.activity_commented), color = ZapText3, style = style, maxLines = 1)
    }
}

@Composable
private fun Time(createdAt: Long, modifier: Modifier = Modifier, end: Boolean = false) {
    Text(
        DateUtils.getRelativeTimeSpanString(
            createdAt * 1000,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString(),
        color = ZapText3,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        textAlign = if (end) TextAlign.End else null,
        modifier = modifier,
    )
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
internal val ACTIVITY_ICON = 44.dp
internal val ACTIVITY_TEXT_INSET = ACTIVITY_ICON + ZapSpacing.space3
