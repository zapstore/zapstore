package dev.zapstore.app.screens

import dev.zapstore.app.ZapTitle3Style
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapWarning
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import dev.zapstore.iolite.StackRecord
import dev.zapstore.app.ZapSurface2
import dev.zapstore.app.ZapRadius
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import dev.zapstore.app.ZapTextTertiary
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zapstore.app.MarkdownText
import dev.zapstore.app.R
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapTextPrimary
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.app.components.AuthorByline
import dev.zapstore.app.components.BadgeTone
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.EvidenceCard
import dev.zapstore.app.components.EvidenceLine
import dev.zapstore.app.components.InfoRow
import dev.zapstore.app.components.ProfileImage
import dev.zapstore.app.components.SectionLabel
import dev.zapstore.app.components.SkeletonBlock
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.VersionPill
import dev.zapstore.app.components.ZapBadge
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapTextLink
import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.app.facts.FactCatalog
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.ProfileRecord
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** A requested permission: the Android id, its fact label when the table has one, and the fact's note. */
internal data class PermissionLine(val id: String?, val label: String?, val note: String)

private val leadingPermissionId = Regex("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\\s+")

/** "CAMERA used for QR codes" reads as "Used for QR codes"; the id is already the row. */
internal fun String.withoutLeadingId(id: String?): String {
    val rest = when {
        id != null && startsWith("$id ") -> substring(id.length + 1)
        else -> leadingPermissionId.find(this)?.let { substring(it.range.last + 1) } ?: return this
    }
    return rest.replaceFirstChar { it.uppercaseChar() }
}

internal fun AppRecord.permissionLines(): List<PermissionLine> {
    val notes = securityNotes
    return permissionIds.map { id ->
        val known = FactCatalog.permissionForId(id)
        PermissionLine(id, known?.label, known?.let { notes[it.fact] }.orEmpty().withoutLeadingId(id))
    }.ifEmpty {
        factRows.mapNotNull { fact ->
            if (!fact.yes) return@mapNotNull null
            val label = FactCatalog.permission(fact.key) ?: return@mapNotNull null
            PermissionLine(null, label, fact.reason.withoutLeadingId(null))
        }
    }
}

private val spdxPattern = Regex("^[A-Za-z0-9.+-]+( (AND|OR|WITH) [A-Za-z0-9.+-]+)*$")

/** The listing's license when it reads as an SPDX expression, such as `MIT` or `GPL-3.0-or-later`. */
internal val AppRecord.spdxLicense: String?
    get() = license?.trim()?.takeIf { it.length <= 48 && spdxPattern.matches(it) }

internal fun AppRecord.hasSecurityCard(): Boolean =
    isVerified || isCatalogSigned || security.isNotBlank() || factRows.isNotEmpty() || permissionIds.isNotEmpty()

/**
 * Security: flags first, then the publisher check, the facts as badges, each permission with
 * its reason, and the prose. A verdict leads; the evidence sits under it.
 */
@Composable
internal fun SecurityCard(app: AppRecord, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    val pills = app.factRows.mapNotNull { fact ->
        val pill = FactCatalog.pill(fact.key) ?: return@mapNotNull null
        if (!pill.visible(fact.yes)) return@mapNotNull null
        val label = pill.label(fact.yes).let { base ->
            if (fact.key == "source_available" && fact.yes) app.spdxLicense?.let { "$base · $it" } ?: base else base
        }
        Triple(fact.key, label, pill.tone(fact.yes))
    }
    val flags = app.securityFlags.mapNotNull { flag -> FactCatalog.flag(flag.id)?.let { it to flag.note } }
    val permissions = app.permissionLines()
    EvidenceCard(stringResource(R.string.privacy_and_security), modifier.fillMaxWidth().testTag("securityCard")) {
        if (flags.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2), modifier = Modifier.testTag("securityFlags")) {
                flags.forEach { (label, note) ->
                    ZapBadge(label, BadgeTone.Warning)
                    if (note.isNotBlank()) Text(note, color = ZapTextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (app.isVerified) EvidenceLine(stringResource(R.string.verified_author), checked = true)
        if (app.isCatalogSigned) CatalogSignedNote(app.name, downloadUrl = null, Modifier.testTag("catalogSigned"))
        if (pills.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                modifier = Modifier.fillMaxWidth().testTag("facts"),
            ) {
                pills.forEach { (key, label, tone) ->
                    when (tone) {
                        FactCatalog.Tone.Good -> ZapBadge(label, BadgeTone.Verified, Modifier.testTag("fact_$key"), R.drawable.ic_check)
                        FactCatalog.Tone.Bad -> ZapBadge(label, BadgeTone.Danger, Modifier.testTag("fact_$key"), R.drawable.ic_close)
                        FactCatalog.Tone.Neutral -> ZapBadge(label, BadgeTone.Neutral, Modifier.testTag("fact_$key"))
                    }
                }
            }
        }
        if (app.securityWarnings.isNotBlank()) {
            app.securityWarnings.lineSequence().map { it.trim().removePrefix("⚠️").removePrefix("⚠").trim() }
                .filter { it.isNotEmpty() }
                .forEach { Text(it, color = ZapTextPrimary, style = MaterialTheme.typography.bodyMedium) }
        }
        if (app.securityBody.isNotBlank()) {
            MarkdownText(
                value = app.securityBody,
                color = ZapTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                onOpenUrl = onOpenUrl,
            )
        }
        if (permissions.isNotEmpty()) {
            HorizontalDivider(color = ZapLine)
            val (named, other) = permissions.partition { it.label != null }
            Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3), modifier = Modifier.testTag("permissions")) {
                named.forEach { PermissionRow(it) }
                if (other.isNotEmpty()) {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    ZapTextLink(
                        if (expanded) stringResource(R.string.hide_other_permissions)
                        else pluralStringResource(R.plurals.show_other_permissions, other.size, other.size),
                        { expanded = !expanded },
                        Modifier.testTag("toggleOtherPermissions"),
                    )
                    if (expanded) {
                        Text(
                            other.joinToString(" · ") { it.id.orEmpty() },
                            color = ZapTextTertiary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("otherPermissions"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(line: PermissionLine) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(line.label ?: line.id.orEmpty(), color = ZapTextPrimary, style = MaterialTheme.typography.bodyMedium)
        if (line.note.isNotBlank()) Text(line.note, color = ZapTextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

/** Releases and the publisher record: key and value pairs, the signer last. */
@Composable
internal fun ReleaseCard(
    app: AppRecord,
    author: ProfileRecord?,
    onOpenUrl: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onReleaseNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EvidenceCard(stringResource(R.string.latest_release), modifier.fillMaxWidth().testTag("releaseCard")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
            VersionPill(app.version)
            Text(formatDate(app.releasedAt), color = ZapTextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (app.releaseNotes.isNotBlank()) {
                ZapTextLink(stringResource(R.string.release_notes), onReleaseNotes, Modifier.testTag("releaseNotes"))
            }
        }
        Column {
            InfoRow(
                label = stringResource(R.string.source),
                value = app.repository ?: stringResource(R.string.not_available),
                link = app.repository,
                onOpenUrl = onOpenUrl,
            )
            app.website?.let { InfoRow(stringResource(R.string.website), it, link = it, onOpenUrl = onOpenUrl) }
            app.license?.let { InfoRow(stringResource(R.string.license), it) }
            InfoRow(stringResource(R.string.app_id), app.appId)
            app.authorPubkey?.let { pubkey ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                ) {
                    StatusText(stringResource(R.string.verified_author), Modifier.weight(1f))
                    AuthorByline(pubkey = pubkey, profile = author, showBy = false, onClick = { onProfileClick(pubkey) })
                }
            }
        }
    }
}

/**
 * The zap card: the total, who zapped most, who the sats go to, and one-tap amounts that open
 * the zap sheet with that amount chosen. Without a C1 owner the catalog that lists the app is paid,
 * and the card says so in the catalog's own name.
 */
@Composable
internal fun ZapCard(summary: ZapSummary, appName: String, modifier: Modifier = Modifier, onZap: ((Long) -> Unit)? = null) {
    val format = remember { NumberFormat.getInstance() }
    val shape = RoundedCornerShape(ZapRadius.lg)
    Column(
        verticalArrangement = Arrangement.spacedBy(ZapSpacing.space4),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(ZapWarning.copy(alpha = 0.10f), ZapSurface1)))
            .border(1.dp, ZapWarning.copy(alpha = 0.22f), shape)
            .padding(ZapSpacing.space4)
            .testTag("zapCard"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(ZapWarning.copy(alpha = 0.16f)),
            ) {
                Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, tint = ZapWarning, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                when {
                    summary.loading && summary.count == 0 -> SkeletonBlock(width = 120.dp, height = 26.dp, cornerRadius = 6.dp)
                    summary.count == 0 -> {
                        Text(stringResource(R.string.zap_none), style = ZapTitle3Style)
                        Text(
                            summary.error ?: stringResource(R.string.zap_be_first, appName),
                            color = ZapTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    else -> {
                        Text(
                            pluralStringResource(R.plurals.zap_total, summary.totalSats.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), format.format(summary.totalSats)),
                            style = ZapTitle3Style.copy(fontFeatureSettings = "tnum"),
                            modifier = Modifier.testTag("zapTotal"),
                        )
                        Text(
                            pluralStringResource(R.plurals.zap_count, summary.count, summary.count),
                            color = ZapTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (summary.zappers.isNotEmpty()) ZapperFaces(summary.zappers, summary.profiles)
        }
        summary.recipient?.let { recipient ->
            val profile = summary.profiles[recipient.pubkey]
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2), modifier = Modifier.testTag("zapRecipient")) {
                Box(Modifier.padding(top = 1.dp).size(18.dp).clip(CircleShape).background(ZapSurface3)) {
                    ProfileImage(pubkey = recipient.pubkey, pictureUrl = profile?.picture, profileVersion = profile?.eventId, contentDescription = null, modifier = Modifier.matchParentSize())
                }
                val name = shortDisplayName(recipient.pubkey, profile)
                Text(
                    if (recipient.catalog) stringResource(R.string.zap_goes_to_catalog, name) else stringResource(R.string.zap_goes_to_developer, name),
                    color = ZapTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        onZap?.let { zap ->
            Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                ZAP_AMOUNTS.forEach { amount ->
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .height(ZapSize.button)
                            .clip(RoundedCornerShape(ZapRadius.full))
                            .background(ZapWarning.copy(alpha = 0.14f))
                            .border(1.dp, ZapWarning.copy(alpha = 0.35f), RoundedCornerShape(ZapRadius.full))
                            .clickable(role = Role.Button) { zap(amount) }
                            .testTag("zapQuick:$amount"),
                    ) {
                        Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, tint = ZapWarning, modifier = Modifier.size(14.dp))
                        Text(
                            compactSats(amount),
                            color = ZapWarning,
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The biggest zappers, overlapping, each ringed in the card color. */
@Composable
private fun ZapperFaces(pubkeys: List<String>, profiles: Map<String, ProfileRecord>) {
    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp), modifier = Modifier.testTag("zappers")) {
        pubkeys.forEach { pubkey ->
            val profile = profiles[pubkey]
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(ZapSurface1)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(ZapSurface3),
            ) {
                ProfileImage(pubkey = pubkey, pictureUrl = profile?.picture, profileVersion = profile?.eventId, contentDescription = null, modifier = Modifier.matchParentSize())
            }
        }
    }
}

/** 21, 100, 1k, 5k. */
internal fun compactSats(sats: Long): String =
    if (sats >= 1_000 && sats % 1_000 == 0L) "${sats / 1_000}k" else sats.toString()

internal val ZAP_AMOUNTS = listOf(21L, 100L, 1_000L, 5_000L)

@Composable
internal fun CommentsEmpty(error: CommentsError?, onRetry: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.testTag("commentsEmpty"), verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
        EmptyRow(
            stringResource(
                when (error) {
                    CommentsError.RelaysUnreachable -> R.string.comments_error_relays
                    CommentsError.TimedOut -> R.string.comments_error_timeout
                    null -> R.string.no_comments
                },
            ),
        )
        if (error != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                ZapButton(stringResource(R.string.try_again), onRetry, style = ZapButtonStyle.Tonal, modifier = Modifier.testTag("commentsRetry"))
                ZapButton(stringResource(R.string.network_settings), onSettings, style = ZapButtonStyle.Ghost, modifier = Modifier.testTag("commentsNetworkSettings"))
            }
        }
    }
}

/** A comment as a row: avatar, name, date, text. Replies indent; no card behind them. */
@Composable
internal fun CommentRow(
    comment: CommentRecord,
    author: ProfileRecord?,
    onOpenUrl: (String) -> Unit,
    onAuthorClick: (String) -> Unit,
    depth: Int = 0,
) {
    Box(Modifier.fillMaxWidth().testTag("commentDepth:${comment.eventId}:$depth")) {
    Column(
        verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ZapSpacing.space6 * depth, top = ZapSpacing.space2, bottom = ZapSpacing.space2)
            .testTag("comment:${comment.eventId}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAuthorClick(comment.pubkey) }
                .testTag("profile:${comment.pubkey}"),
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(ZapSurface3)) {
                ProfileImage(
                    pubkey = comment.pubkey,
                    pictureUrl = author?.picture,
                    profileVersion = author?.eventId,
                    contentDescription = stringResource(R.string.profile_avatar_description, shortDisplayName(comment.pubkey, author)),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                shortDisplayName(comment.pubkey, author),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(formatDate(comment.createdAt), color = ZapTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        MarkdownText(value = comment.content, color = ZapTextSecondary, style = MaterialTheme.typography.bodyMedium, onOpenUrl = onOpenUrl)
    }
    }
}

internal fun formatDate(createdAt: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(createdAt * 1_000))

/** Curators whose public stacks list this app: avatar, name, and the stack; tapping opens it. */
@Composable
internal fun StackedByRow(stackedBy: StackedByUi, onStackClick: (StackRecord) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.testTag("stackedBy")) {
        SectionLabel(stringResource(R.string.stacked_by), Modifier.padding(bottom = ZapSpacing.space2))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
            items(stackedBy.stacks, key = { it.eventId }) { stack ->
                val profile = stackedBy.profiles[stack.pubkey]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                    modifier = Modifier
                        .clip(RoundedCornerShape(ZapRadius.full))
                        .background(ZapSurface2)
                        .clickable { onStackClick(stack) }
                        .padding(start = 4.dp, end = ZapSpacing.space3, top = 4.dp, bottom = 4.dp)
                        .widthIn(max = 220.dp)
                        .testTag("stackedBy:${stack.pubkey}"),
                ) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(ZapSurface3)) {
                        ProfileImage(
                            pubkey = stack.pubkey,
                            pictureUrl = profile?.picture,
                            profileVersion = profile?.eventId,
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    Column {
                        Text(
                            shortDisplayName(stack.pubkey, profile),
                            color = ZapTextPrimary,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stack.name.ifBlank { stack.identifier },
                            color = ZapTextTertiary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
