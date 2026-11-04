package dev.zapstore.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.components.ProfileImage
import dev.zapstore.app.components.ZapTextLink
import dev.zapstore.app.components.EvidenceLine
import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.app.facts.FactCatalog
import dev.zapstore.iolite.AppByline
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.toNpub

/** How many fact pills the summary carries; the full report has them all. */
private const val SUMMARY_FACTS = 4

/** How many permission names the summary spells out before "+N". */
private const val SUMMARY_PERMISSIONS = 3

/** Where a person's Nostr profile can be read outside Zapstore. */
internal fun npubWorldUrl(pubkey: String): String = "https://npub.world/${pubkey.toNpub()}"

/**
 * What a person about to install an app they do not know needs to see before Download, in the order
 * they ask it: any warning, whether the developer is who they say, who vouches for them, where the app
 * comes from and what it can do. Worst news first. Everything else is one tap away in the full report.
 */
@Composable
internal fun AssuranceSummary(
    app: AppRecord,
    author: ProfileRecord?,
    trust: TrustUi,
    onPersonalizeTrust: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val developer = app.bylinePubkey?.let { shortDisplayName(it, author) }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3), modifier = modifier) {
        app.flagLabels().forEach { (label, note) ->
            EvidenceLine(label, checked = false, warning = true, detail = note)
        }
        when (app.byline) {
            AppByline.Verified -> EvidenceLine(stringResource(R.string.assure_verified, developer), checked = true)
            AppByline.Unverified -> EvidenceLine(stringResource(R.string.assure_unverified, developer), checked = false, warning = true)
            AppByline.Indexed -> CatalogSignedNote(app.name, app.downloadUrl, Modifier.testTag("installCatalogSigned"), source = app.repository)
        }
        if (app.authorPubkey != null && !app.isCatalogSigned) {
            TrustSignals(developer, trust, onPersonalizeTrust, onOpenUrl, Modifier.testTag("trustSignals"))
        }
        // Bad news leads, then neutral facts, then the good ones.
        val facts = app.factPills().sortedBy { if (it.tone == FactCatalog.Tone.Bad) 0 else if (it.tone == FactCatalog.Tone.Neutral) 1 else 2 }
        FactPills(facts.take(SUMMARY_FACTS))
        permissionSummary(app)?.let { EvidenceLine(it, checked = false, modifier = Modifier.testTag("assurePermissions")) }
        ZapTextLink(
            stringResource(R.string.see_full_report),
            onOpenReport,
            Modifier.offset(x = -ZapSpacing.space2).testTag("seeFullReport"),
        )
    }
}

/** "Asks for Camera, Location +2": the permissions that have a human name, then how many more there are. */
@Composable
private fun permissionSummary(app: AppRecord): String? {
    val lines = app.permissionLines()
    val named = lines.mapNotNull { it.label }.distinct()
    if (named.isEmpty()) return null
    val shown = named.take(SUMMARY_PERMISSIONS).joinToString(", ")
    val more = lines.size - minOf(named.size, SUMMARY_PERMISSIONS)
    return stringResource(R.string.assure_permissions, if (more > 0) stringResource(R.string.assure_more, shown, more) else shown)
}

/**
 * Who stands behind the developer: the catalog's followers, or the user's own after they ask. Each person
 * opens their page on npub.world. It is absent when there is nothing to say, fills in without holding
 * back Download, and follows are not endorsements.
 */
@Composable
internal fun TrustSignals(name: String, trust: TrustUi, onPersonalize: () -> Unit, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    if (!trust.hasSignals && !trust.canPersonalize) return
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2), modifier = modifier) {
        if (trust.leaked) {
            EvidenceLine(
                stringResource(R.string.trust_leaked),
                checked = false,
                warning = true,
                detail = stringResource(R.string.trust_leaked_body, name),
            )
        }
        if (trust.youFollow) Text(stringResource(R.string.trust_you_follow, name), color = ZapText, style = MaterialTheme.typography.bodyMedium)
        if (trust.followers.isNotEmpty()) {
            PeopleLine(
                stringResource(if (trust.personal) R.string.trust_followed_by_yours else R.string.trust_followed_by),
                trust.followers, trust, onOpenUrl,
            )
        }
        if (trust.zappers.isNotEmpty()) PeopleLine(stringResource(R.string.trust_zapped_by), trust.zappers, trust, onOpenUrl)
        if (trust.youZapped) Text(stringResource(R.string.trust_you_zapped), color = ZapText, style = MaterialTheme.typography.bodyMedium)
        TrustNote(trust, onPersonalize)
    }
}

/**
 * One quiet sentence under the people: follows are not endorsements, then the way to refresh them from the
 * user's own web of trust. A check that fails says so in one generic line and offers the same link again.
 */
@Composable
private fun TrustNote(trust: TrustUi, onPersonalize: () -> Unit) {
    val ask = trust.canPersonalize && !trust.personal
    if (!trust.hasSignals && !ask) return
    val disclaimer = stringResource(R.string.trust_disclaimer)
    val checking = stringResource(R.string.trust_checking)
    val failed = stringResource(R.string.trust_failed)
    val refresh = stringResource(R.string.trust_personalize)
    val styles = TextLinkStyles(SpanStyle(color = ZapActionText, fontWeight = FontWeight.SemiBold))
    val note = buildAnnotatedString {
        if (!trust.leaked) append("$disclaimer ")
        if (ask) {
            if (trust.checking) {
                append(checking)
            } else {
                if (trust.failed) append("$failed ")
                withLink(LinkAnnotation.Clickable("refresh", styles) { onPersonalize() }) { append(refresh) }
            }
        }
    }
    Text(note, color = ZapText2, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("trustNote"))
}

/** A label, then each person as a 20 dp avatar and name that opens their page on npub.world. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleLine(prefix: String, people: List<TrustPerson>, trust: TrustUi, onOpenUrl: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(prefix, color = ZapText2, style = MaterialTheme.typography.bodyMedium)
        people.forEach { person ->
            val profile = trust.profiles[person.pubkey]
            val name = if (profile == null && person.name.isNotBlank()) person.name else shortDisplayName(person.pubkey, profile)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space1),
                modifier = Modifier
                    .heightIn(min = ZapSpacing.space8)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { onOpenUrl(npubWorldUrl(person.pubkey)) }
                    .testTag("trustPerson:${person.pubkey}"),
            ) {
                // The catalog's list has names but no kind 0, so there is nothing to draw until a check resolves them.
                if (profile != null) {
                    ProfileImage(
                        pubkey = person.pubkey,
                        pictureUrl = profile.picture,
                        profileVersion = profile.eventId,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).clip(CircleShape),
                    )
                }
                Text(name, color = ZapActionText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
