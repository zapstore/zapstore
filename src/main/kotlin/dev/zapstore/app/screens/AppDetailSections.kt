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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.zapstore.app.ZapActionText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
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
import dev.zapstore.app.components.VersionStatus
import dev.zapstore.app.components.ZapBadge
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.app.isHttpUrl
import dev.zapstore.app.facts.FactCatalog
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.ZapRecord
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.zapstore.app.ZapCanvas
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
internal fun ReleaseCard(
    app: AppRecord,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    versionStatus: VersionStatus? = null,
) {
    EvidenceCard(
        stringResource(R.string.release_title),
        modifier.fillMaxWidth().testTag("releaseCard"),
        headerAction = { CardSheetButton(stringResource(R.string.release_notes), onOpen, "releaseLink") },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
            if (app.version.isNotBlank()) VersionPill(app.version, status = versionStatus, modifier = Modifier.testTag("releaseVersion"))
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
 * Zap: what people have given on the left, the Zap button on the right, so the total never reads as
 * the amount the button sends. The summary opens [ZappersSheet]. With no zappers the left side asks
 * for a zap to the recipient. Without [onZap] (the account can't zap) only the summary shows.
 */
@Composable
internal fun ZapRow(
    summary: ZapSummary,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onZap: (() -> Unit)? = null,
) {
    var listing by rememberSaveable { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = modifier.fillMaxWidth().heightIn(min = ZapSpacing.space12),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
            modifier = Modifier.weight(1f),
        ) {
            Box(Modifier.weight(1f, fill = false)) {
                val recipient = summary.recipient
                when {
                    summary.zappers.isNotEmpty() -> ZappersSummary(summary, onClick = { listing = true })
                    onZap != null && recipient != null -> Text(
                        if (recipient.catalog) stringResource(R.string.zap_support_catalog)
                        else stringResource(
                            R.string.zap_support,
                            shortDisplayName(recipient.pubkey, summary.profiles[recipient.pubkey]),
                        ),
                        color = ZapText2,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("zapSupport"),
                    )
                }
            }
            if (summary.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp).testTag("zapsLoading"),
                    color = ZapText,
                    strokeWidth = 2.5.dp,
                )
            }
        }
        onZap?.let { zap ->
            ZapButton(
                text = stringResource(R.string.zap),
                onClick = zap,
                style = ZapButtonStyle.Tonal,
                icon = R.drawable.ic_bolt,
                iconTint = ZapWarning,
                modifier = Modifier.testTag("zapOpen"),
            )
        }
    }
    if (listing) {
        ZappersSheet(
            summary,
            onProfileClick = { pubkey ->
                listing = false
                onProfileClick(pubkey)
            },
            onDismiss = { listing = false },
        )
    }
}

/** Face diameter, a tenth under the 28dp circle. The ring scales with it. */
private val stackedFace = 28.dp * 0.9f

/** Overlap of space2, plus a quarter, plus a tenth of that. */
private val stackedOverlap = -(ZapSpacing.space2 * 1.25f * 1.1f)

/**
 * How wide a face stack is when [count] circles of [face] px show [step] px of each next face.
 * [step] never exceeds the preferred gap, and drops (then the circle shrinks) so the stack
 * stays within [maxWidth].
 */
internal fun packFaces(count: Int, face: Int, preferredStep: Int, maxWidth: Int): FacePack {
    if (count <= 0 || face <= 0 || maxWidth <= 0) return FacePack(0, 0, 0)
    val stepCap = preferredStep.coerceIn(0, face)
    val natural = face + (count - 1) * stepCap
    if (natural <= maxWidth) return FacePack(face, stepCap, natural)
    val tight = if (count == 1) 0 else ((maxWidth - face) / (count - 1)).coerceIn(0, stepCap)
    val tightWidth = face + (count - 1) * tight
    if (tightWidth <= maxWidth) return FacePack(face, tight, tightWidth)
    val shrunk = maxWidth.coerceAtMost(face)
    return FacePack(shrunk, 0, shrunk)
}

internal data class FacePack(val face: Int, val step: Int, val width: Int)

/**
 * How many of [candidates] fit in [maxWidth] at [preferredStep]. A leftover that is most of
 * another step buys one more face, overlapped a little tighter.
 */
internal fun facesThatFit(candidates: Int, face: Int, preferredStep: Int, maxWidth: Int): Int {
    if (candidates <= 0 || face <= 0 || maxWidth < face) return 0
    val step = preferredStep.coerceIn(1, face)
    val comfortable = (1 + (maxWidth - face) / step).coerceAtMost(candidates)
    if (comfortable >= candidates) return comfortable
    val slack = maxWidth - (face + (comfortable - 1) * step)
    return if (slack * 2 >= step) comfortable + 1 else comfortable
}

/**
 * Faces for the summary, biggest first. A zapper with no picture URL stays in [ZappersSheet]
 * and off this stack, including a named person whose face would be a key avatar.
 * How many show is chosen later, from the width left on the line.
 */
internal fun stackedZappers(zappers: List<Zapper>, profiles: Map<String, ProfileRecord>): List<Zapper> =
    zappers.filter { stacksZapperFace(profiles[it.pubkey]) }

private fun stacksZapperFace(profile: ProfileRecord?): Boolean =
    isHttpUrl(profile?.picture?.trim().orEmpty())

/**
 * `21k sats` on one line, then `from` the faces and `…` in caption, sharing the amount's baseline.
 * One target: it opens the full list.
 */
@Composable
private fun ZappersSummary(summary: ZapSummary, onClick: () -> Unit) {
    val total = pluralStringResource(R.plurals.zap_total, summary.totalSats.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), compactSats(summary.totalSats))
    val stacked = stackedZappers(summary.zappers, summary.profiles)
    var shown by remember(stacked) { mutableIntStateOf(stacked.size) }
    val aside = MaterialTheme.typography.bodySmall
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space1),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ZapSpacing.space12)
            .clip(RoundedCornerShape(ZapRadius.md))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("zappers"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space1)) {
            Text(
                total,
                color = ZapText,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline().testTag("zapTotal"),
            )
            if (stacked.isNotEmpty()) {
                Text(
                    stringResource(R.string.zap_from),
                    color = ZapText2,
                    style = aside,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        if (stacked.isNotEmpty()) {
            StackedFaces(
                stacked,
                summary.profiles,
                Modifier.weight(1f, fill = false).align(Alignment.CenterVertically),
                onShown = { shown = it },
            )
        }
        if (summary.zapperCount > shown) {
            Text(
                stringResource(R.string.zap_and_more),
                color = ZapText2,
                style = aside,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * As many pictured zappers as the leftover width holds. Preferred overlap when they fit;
 * a little tighter when the slack on the right is enough for one more. `from` and `…` are
 * outside this and keep their width.
 */
@Composable
private fun StackedFaces(
    stacked: List<Zapper>,
    profiles: Map<String, ProfileRecord>,
    modifier: Modifier = Modifier,
    onShown: (Int) -> Unit = {},
) {
    SubcomposeLayout(modifier) { constraints ->
        val face = stackedFace.roundToPx()
        val preferredStep = (stackedFace + stackedOverlap).roundToPx().coerceAtLeast(1)
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val count = facesThatFit(stacked.size, face, preferredStep, maxWidth)
        onShown(count)
        val pack = packFaces(count, face, preferredStep, maxWidth)
        if (count == 0 || pack.face == 0) {
            layout(0, 0) {}
        } else {
            val placeables = subcompose(count) {
                stacked.take(count).forEachIndexed { i, zapper ->
                    val profile = profiles[zapper.pubkey]
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(if (i == 0) ZapWarning else ZapCanvas, CircleShape)
                            .padding(2.dp * 0.9f)
                            .clip(CircleShape)
                            .background(ZapSurface3),
                    ) {
                        ProfileImage(
                            pubkey = zapper.pubkey,
                            pictureUrl = profile?.picture,
                            profileVersion = profile?.eventId,
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
            }.map { it.measure(Constraints.fixed(pack.face, pack.face)) }
            layout(pack.width, pack.face) {
                placeables.forEachIndexed { i, placeable ->
                    placeable.placeRelative(i * pack.step, 0, zIndex = (count - i).toFloat())
                }
            }
        }
    }
}

/** Every zapper the summary holds, biggest first; each row opens the profile. */
@Composable
private fun ZappersSheet(summary: ZapSummary, onProfileClick: (String) -> Unit, onDismiss: () -> Unit) {
    ZapSheet(onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5)
                .testTag("zappersSheet"),
        ) {
            Text(
                stringResource(R.string.zappers_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = ZapSpacing.space2),
            )
            summary.zappers.forEach { zapper ->
                val profile = summary.profiles[zapper.pubkey]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(role = Role.Button) { onProfileClick(zapper.pubkey) }
                        .testTag("zapper:${zapper.pubkey}"),
                ) {
                    Box(Modifier.size(ZapSpacing.space8).clip(CircleShape).background(ZapSurface3)) {
                        ProfileImage(
                            pubkey = zapper.pubkey,
                            pictureUrl = profile?.picture,
                            profileVersion = profile?.eventId,
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    Text(
                        shortDisplayName(zapper.pubkey, profile),
                        color = ZapText,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        pluralStringResource(R.plurals.zap_total, zapper.sats.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), compactSats(zapper.sats)),
                        color = ZapText2,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        maxLines = 1,
                    )
                }
            }
            val more = summary.zapperCount - summary.zappers.size
            if (more > 0) {
                Text(
                    pluralStringResource(R.plurals.zappers_more, more, more),
                    color = ZapText3,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = ZapSpacing.space2),
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
