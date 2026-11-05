package dev.zapstore.app.screens

import dev.zapstore.app.ZapWarning
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import dev.zapstore.app.components.ZapTextLink
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import dev.zapstore.iolite.StackRecord
import dev.zapstore.app.ZapSurface2
import dev.zapstore.app.ZapRadius
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import dev.zapstore.app.ZapText3
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.zapstore.app.ZapActionText
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
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.components.BadgeTone
import dev.zapstore.app.components.EmptyRow
import dev.zapstore.app.components.EvidenceCard
import dev.zapstore.app.components.ProfileImage
import dev.zapstore.app.components.SectionLabel
import dev.zapstore.app.components.VersionPill
import dev.zapstore.app.components.ZapBadge
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.app.facts.FactCatalog
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.ZapRecord
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.offset
import java.util.Locale

/** A requested permission: the Android id, its fact label when the table has one, and the fact's note. */
/** [does] leads the row in plain words; [label] is the short name; [id] is the Android name, shown small. */
internal data class PermissionLine(val id: String?, val label: String?, val note: String, val does: String? = null)

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
        PermissionLine(id, known?.label, known?.let { notes[it.fact] }.orEmpty().withoutLeadingId(id), known?.does)
    }.ifEmpty {
        factRows.mapNotNull { fact ->
            if (!fact.yes) return@mapNotNull null
            val label = FactCatalog.permission(fact.key) ?: return@mapNotNull null
            PermissionLine(null, label, fact.reason.withoutLeadingId(null), FactCatalog.permissionDoes(fact.key))
        }
    }
}

private val spdxPattern = Regex("^[A-Za-z0-9.+-]+( (AND|OR|WITH) [A-Za-z0-9.+-]+)*$")

/** The listing's license when it reads as an SPDX expression, such as `MIT` or `GPL-3.0-or-later`. */
internal val AppRecord.spdxLicense: String?
    get() = license?.trim()?.takeIf { it.length <= 48 && spdxPattern.matches(it) }

internal fun AppRecord.hasSecurityCard(): Boolean =
    isCatalogSigned || security.isNotBlank() || factRows.isNotEmpty() || permissionIds.isNotEmpty()

internal data class FactPill(val key: String, val label: String, val tone: FactCatalog.Tone)

internal fun AppRecord.factPills(): List<FactPill> = factRows.mapNotNull { fact ->
    val pill = FactCatalog.pill(fact.key) ?: return@mapNotNull null
    if (!pill.visible(fact.yes)) return@mapNotNull null
    val label = pill.label(fact.yes).let { base ->
        if (fact.key == "source_available" && fact.yes) spdxLicense?.let { "$base · $it" } ?: base else base
    }
    FactPill(fact.key, label, pill.tone(fact.yes))
}

/** The card: warning flags, fact pills and a short read of the prose. The rest opens from [onSeeFull]. */
@Composable
internal fun SecurityReportCard(
    app: AppRecord,
    onOpenUrl: (String) -> Unit,
    onSeeFull: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flags = app.flagLabels()
    EvidenceCard(
        stringResource(R.string.privacy_and_security),
        modifier.fillMaxWidth().testTag("securityCard"),
        headerAction = { CardSheetButton(stringResource(R.string.see_full_report), onSeeFull, "seeFullReport") },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3), modifier = Modifier.fillMaxWidth()) {
            if (flags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                    verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                    modifier = Modifier.testTag("cardFlags"),
                ) { flags.forEach { (label, _) -> ZapBadge(label, BadgeTone.Warning) } }
            }
            FactPills(app.factPills())
            SecurityProse(app, onOpenUrl, brief = true)
        }
    }
}

/**
 * The icon in a card's title row that opens the card's sheet. It is 32 dp, not Material's 48 dp minimum,
 * which would stretch the title row. The offset lines the glyph up with the card's right padding.
 */
@Composable
private fun CardSheetButton(contentDescription: String, onClick: () -> Unit, tag: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset(x = ZapSpacing.space2)
            .size(32.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .testTag(tag),
    ) {
        Icon(painterResource(R.drawable.ic_open_sheet), contentDescription = null, tint = ZapActionText, modifier = Modifier.size(20.dp))
    }
}

/** Warning flags as label and note, in file order, for the ids the app knows. */
internal fun AppRecord.flagLabels(): List<Pair<String, String>> =
    securityFlags.mapNotNull { flag -> FactCatalog.flag(flag.id)?.let { it to flag.note } }

/** True when there is a verdict to show: a flag, a pill, or any written report. */
internal fun AppRecord.hasVerdict(): Boolean =
    flagLabels().isNotEmpty() || factPills().isNotEmpty() || securityWarnings.isNotBlank() || securityBody.isNotBlank()

/** The verdict half of the full report: flags with their notes, the pills, and all the prose. */
@Composable
internal fun SecurityVerdict(app: AppRecord, onOpenUrl: (String) -> Unit) {
    val flags = app.flagLabels()
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3), modifier = Modifier.fillMaxWidth().testTag("securityReport")) {
        if (flags.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2), modifier = Modifier.testTag("securityFlags")) {
                flags.forEach { (label, note) ->
                    ZapBadge(label, BadgeTone.Warning)
                    if (note.isNotBlank()) Text(note, color = ZapText2, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        FactPills(app.factPills())
        SecurityProse(app, onOpenUrl)
    }
}

/**
 * The permissions with a human name, as pills. Expanded, every permission the app asks for, by label with
 * its note; raw ids with no label follow in one line.
 */
@Composable
internal fun PermissionsList(app: AppRecord) {
    val lines = app.permissionLines()
    val (named, other) = lines.partition { it.label != null }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3), modifier = Modifier.fillMaxWidth().testTag("permissions")) {
        if (expanded) {
            named.forEach { PermissionRow(it) }
            if (other.isNotEmpty()) {
                Text(
                    other.joinToString(" · ") { it.id.orEmpty() },
                    color = ZapText3,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("otherPermissions"),
                )
            }
        } else if (named.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                modifier = Modifier.testTag("permissionPills"),
            ) { named.mapNotNull { it.label }.distinct().forEach { ZapBadge(it, BadgeTone.Neutral) } }
        }
        ZapTextLink(
            if (expanded) stringResource(R.string.permissions_show_less) else stringResource(R.string.permissions_show_all, lines.size),
            { expanded = !expanded },
            Modifier.offset(x = -ZapSpacing.space2).testTag("permissionsExpand"),
        )
    }
}

internal fun AppRecord.hasPermissions(): Boolean = permissionLines().isNotEmpty()

@Composable
internal fun FactPills(pills: List<FactPill>) {
    if (pills.isEmpty()) return
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

/** The written report. On the page card ([brief]) it is cut short; the full report holds the rest. */
@Composable
private fun SecurityProse(app: AppRecord, onOpenUrl: (String) -> Unit, brief: Boolean = false) {
    if (app.securityWarnings.isNotBlank()) {
        app.securityWarnings.lineSequence().map { it.trim().removePrefix("⚠️").removePrefix("⚠").trim() }
            .filter { it.isNotEmpty() }
            .forEach {
                Text(
                    it,
                    color = ZapText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (brief) 2 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
    }
    if (app.securityBody.isNotBlank()) {
        MarkdownText(
            value = app.securityBody,
            color = ZapText2,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (brief) 3 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            onOpenUrl = onOpenUrl,
        )
    }
}

@Composable
private fun PermissionRow(line: PermissionLine) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(line.does ?: line.label ?: line.id.orEmpty(), color = ZapText, style = MaterialTheme.typography.bodyMedium)
        if (line.note.isNotBlank()) Text(line.note, color = ZapText2, style = MaterialTheme.typography.bodySmall)
        // The Android name for anyone who wants to look it up; shown once, under what it does.
        line.id?.let { Text(it, color = ZapText3, style = MaterialTheme.typography.labelMedium) }
    }
}

/** Latest release: the version pill and the date, with the notes behind the title-row icon. Size stays in the sheet. */
@Composable
internal fun ReleaseCard(app: AppRecord, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    EvidenceCard(
        stringResource(R.string.release_title),
        modifier.fillMaxWidth().testTag("releaseCard"),
        headerAction = { CardSheetButton(stringResource(R.string.release_notes), onOpen, "releaseLink") },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
            if (app.version.isNotBlank()) VersionPill(app.version)
            Text(
                formatDate(app.releasedAt),
                color = ZapText2,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** For an app with no security data: who published it, the package and the source still open from here. */
@Composable
internal fun PackageCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    EvidenceCard(
        stringResource(R.string.privacy_and_security),
        modifier.fillMaxWidth().testTag("packageCard"),
        headerAction = { CardSheetButton(stringResource(R.string.see_full_report), onOpen, "seeFullReport") },
    ) {
        Text(stringResource(R.string.no_security_report), color = ZapText2, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Zap, as in the reference app: one wide button, then who has zapped, biggest first. Without a
 * [onZap] (the account can't zap) only the strip shows. Where the sats go is said in the zap sheet.
 */
@Composable
internal fun ZapRow(
    summary: ZapSummary,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onZap: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1)) {
        onZap?.let { zap ->
            ZapButton(
                text = stringResource(R.string.zap),
                onClick = zap,
                style = ZapButtonStyle.Tonal,
                icon = R.drawable.ic_bolt,
                iconTint = ZapWarning,
                modifier = Modifier.fillMaxWidth().testTag("zapOpen"),
            )
        }
        if (summary.zappers.isNotEmpty()) ZappersStrip(summary, onProfileClick)
    }
}

/** `⚡ 3,400 sats by` then each zapper's face and total, scrolling sideways. A face opens the profile. */
@Composable
private fun ZappersStrip(summary: ZapSummary, onProfileClick: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("zappers"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space1)) {
            Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, tint = ZapWarning, modifier = Modifier.size(20.dp))
            Text(
                pluralStringResource(R.plurals.zap_total, summary.totalSats.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), compactSats(summary.totalSats)),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                maxLines = 1,
                modifier = Modifier.testTag("zapTotal"),
            )
            Text(
                stringResource(R.string.zap_sats_by),
                color = ZapText2,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.padding(start = ZapSpacing.space1),
            )
        }
        summary.zappers.forEach { zapper ->
            val profile = summary.profiles[zapper.pubkey]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
                modifier = Modifier
                    .heightIn(min = ZapSpacing.space12)
                    .clip(RoundedCornerShape(ZapRadius.full))
                    .clickable(role = Role.Button) { onProfileClick(zapper.pubkey) }
                    .testTag("zapper:${zapper.pubkey}"),
            ) {
                Box(Modifier.size(ZapSpacing.space6).clip(CircleShape).background(ZapSurface3)) {
                    ProfileImage(
                        pubkey = zapper.pubkey,
                        pictureUrl = profile?.picture,
                        profileVersion = profile?.eventId,
                        contentDescription = shortDisplayName(zapper.pubkey, profile),
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Text(
                    compactSats(zapper.sats),
                    color = ZapText2,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 1,200 as `1.2k`, 290,180 as `290k`, 2,000,000 as `2m`: one decimal below ten of a unit, none above. */
internal fun compactSats(sats: Long): String {
    fun scaled(value: Double, suffix: String): String {
        val text = if (value >= 10) String.format(Locale.ROOT, "%.0f", value) else String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
        return text + suffix
    }
    return when {
        sats >= 1_000_000 -> scaled(sats / 1_000_000.0, "m")
        sats >= 1_000 -> scaled(sats / 1_000.0, "k")
        else -> sats.toString()
    }
}

/** A quick zap amount, with the same emoji and label as the reference app so returning users feel at home. */
internal data class ZapAmountOption(val emoji: String, val label: String, val sats: Long)

internal val ZAP_AMOUNTS = listOf(
    ZapAmountOption("\uD83E\uDD19", "2100", 2_100L),
    ZapAmountOption("\uD83D\uDC9C", "10k", 10_000L),
    ZapAmountOption("\uD83E\uDD29", "21k", 21_000L),
    ZapAmountOption("\uD83C\uDFC3", "42k", 42_000L),
    ZapAmountOption("\uD83D\uDCAF", "100k", 100_000L),
)

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
                            color = ZapText,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stack.name.ifBlank { stack.identifier },
                            color = ZapText3,
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
