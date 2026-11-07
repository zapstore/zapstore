package dev.zapstore.app.screens

import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapWarning
import dev.zapstore.app.components.InfoRow
import dev.zapstore.app.components.ProfileImage
import dev.zapstore.app.components.EvidenceLine
import dev.zapstore.app.components.shortDisplayName
import dev.zapstore.app.facts.FactCatalog
import dev.zapstore.iolite.AppByline
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CertificateProof
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.toNpub

/** How many fact pills the summary carries; the full report has them all. */
private const val SUMMARY_FACTS = 4

/** How many permission names the summary spells out before "+N". */
private const val SUMMARY_PERMISSIONS = 3

/** Where a person's Nostr profile can be read outside Zapstore. */
internal fun npubWorldUrl(pubkey: String): String = "https://npub.world/${pubkey.toNpub()}"

/** Where an app's author can be told how to claim it: the same guide the web page links. */
internal const val CLAIM_URL = "https://zapstore.dev/docs/publish"

/**
 * What a person about to install an app they do not know needs to see before Install, in the order
 * they ask it: any warning, whether the developer is who they say, how others know them, where the app
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
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3), modifier = modifier) {
        app.flagLabels().forEach { (label, note) ->
            EvidenceLine(label, checked = false, warning = true, detail = note)
        }
        PublisherStanding(app, author, trust, onPersonalizeTrust, onOpenUrl)
        // Bad news leads, then neutral facts, then the good ones.
        val facts = app.factPills().sortedBy { if (it.tone == FactCatalog.Tone.Bad) 0 else if (it.tone == FactCatalog.Tone.Neutral) 1 else 2 }
        FactPills(facts.take(SUMMARY_FACTS))
        permissionSummary(app)?.let { EvidenceLine(it, checked = false, modifier = Modifier.testTag("assurePermissions")) }
        // The same button the Latest release card ends in.
        ZapButton(
            stringResource(R.string.card_full_report),
            onOpenReport,
            Modifier.fillMaxWidth().testTag("seeFullReport"),
            style = ZapButtonStyle.Tonal,
        )
    }
}

/**
 * Who published the app and how far to trust them, from one component for the Publisher sheet and the
 * install sheet. A developer with a certificate proof gets the blue seal, and their standing among people
 * ([TrustSignals]). A listing signed with the developer's own key but no proof gets a warning. An indexed
 * listing gets the catalog note and no reputation at all: nobody there has a name to look up.
 *
 * [full] is the sheet: the proof dates, the longer unverified sentence, and the invitation to claim an
 * app that is not yet claimed. The install sheet leaves those out. [onProfileClick] makes the developer's
 * name a link; [downloadUrl] says where the file comes from for an indexed listing.
 */
@Composable
internal fun PublisherStanding(
    app: AppRecord,
    author: ProfileRecord?,
    trust: TrustUi,
    onPersonalizeTrust: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    full: Boolean = false,
    proof: CertificateProof? = null,
    onProfileClick: ((String) -> Unit)? = null,
    downloadUrl: String? = null,
) {
    val pubkey = app.bylinePubkey
    val developer = pubkey?.let { shortDisplayName(it, author) }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3), modifier = modifier) {
        when (app.byline) {
            AppByline.Verified -> {
                VerifiedStandingLine(developer, pubkey, onProfileClick)
                // Right under the claim it backs up, above the proof dates.
                TrustSignals(developer, trust, onPersonalizeTrust, onOpenUrl, Modifier.testTag("trustSignals"))
                if (full) {
                    proof?.let {
                        Column {
                            InfoRow(stringResource(R.string.proof_published), formatDate(it.createdAt), verticalPadding = ZapSpacing.space2)
                            if (it.expiry > 0) InfoRow(stringResource(R.string.valid_until), formatDate(it.expiry), verticalPadding = ZapSpacing.space2)
                        }
                    }
                }
            }
            AppByline.Unverified -> StandingLine(
                verified = false,
                text = if (full) stringResource(R.string.unverified_body, developer, app.name) else stringResource(R.string.assure_unverified, developer),
                name = developer,
                pubkey = pubkey,
                onProfileClick = onProfileClick,
            )
            AppByline.Indexed -> Unit
        }
        if (app.isCatalogSigned) {
            CatalogSignedNote(
                app.name,
                downloadUrl,
                Modifier.testTag(if (full) "catalogSigned" else "installCatalogSigned"),
                source = app.repository,
            )
        }
        if (full && app.byline != AppByline.Verified) ClaimInvite(onOpenUrl)
    }
}

/** The developer proved they own the app: the blue seal, then one sentence with their name as a link when [onProfileClick] is given. */
@Composable
internal fun VerifiedStandingLine(name: String, pubkey: String?, onProfileClick: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    StandingLine(
        verified = true,
        text = stringResource(R.string.assure_verified, name),
        name = name,
        pubkey = pubkey,
        onProfileClick = onProfileClick,
        modifier = modifier,
    )
}

/** The seal or the warning, then a sentence that links [name] to the profile of [pubkey] when there is somewhere to go. */
@Composable
private fun StandingLine(
    verified: Boolean,
    text: String,
    name: String,
    pubkey: String?,
    onProfileClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val at = if (pubkey == null || onProfileClick == null || name.isEmpty()) -1 else text.indexOf(name)
    val styles = TextLinkStyles(SpanStyle(color = ZapActionText, fontWeight = FontWeight.SemiBold))
    val sentence = buildAnnotatedString {
        if (at < 0) {
            append(text)
        } else {
            append(text.substring(0, at))
            withLink(LinkAnnotation.Clickable("developer", styles) { onProfileClick!!(pubkey!!) }) { append(name) }
            append(text.substring(at + name.length))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2), modifier = modifier) {
        if (verified) {
            Image(
                painterResource(R.drawable.ic_verified),
                contentDescription = stringResource(R.string.verified_author),
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(painterResource(R.drawable.ic_unverified), contentDescription = null, tint = ZapWarning, modifier = Modifier.size(20.dp))
        }
        Text(sentence, color = ZapText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("authorStatement"))
    }
}

/** "Are you the developer? Claim your app": for a listing nobody has claimed, as on the web page. Opens the publishing guide. */
@Composable
private fun ClaimInvite(onOpenUrl: (String) -> Unit) {
    val styles = TextLinkStyles(SpanStyle(color = ZapActionText, fontWeight = FontWeight.SemiBold))
    val prompt = stringResource(R.string.claim_prompt)
    val link = stringResource(R.string.claim_link)
    Text(
        buildAnnotatedString {
            append("$prompt ")
            withLink(LinkAnnotation.Clickable("claim", styles) { onOpenUrl(CLAIM_URL) }) { append(link) }
        },
        color = ZapText2,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("claimInvite"),
    )
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
 * back Install, and follows are not endorsements.
 */
@Composable
internal fun TrustSignals(name: String, trust: TrustUi, onPersonalize: () -> Unit, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier) {
    if (!trust.hasContent) return
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
        TrustNote(name, trust, onPersonalize)
    }
}

/**
 * One quiet sentence under the people: follows are not endorsements, then the way to refresh them from the
 * user's own web of trust. With nobody found yet, only the check is offered; a check that finds nobody
 * says so. A check that fails says so in one generic line and offers the same link again.
 */
@Composable
private fun TrustNote(name: String, trust: TrustUi, onPersonalize: () -> Unit) {
    val ask = trust.canPersonalize && !trust.personal
    if (!trust.hasSignals && !ask) {
        if (trust.personal) {
            Text(stringResource(R.string.trust_none, name), color = ZapText2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("trustNote"))
        }
        return
    }
    val disclaimer = stringResource(R.string.trust_disclaimer)
    val checking = stringResource(R.string.trust_checking)
    val failed = stringResource(R.string.trust_failed)
    val refresh = stringResource(if (trust.hasSignals) R.string.trust_personalize else R.string.trust_check)
    val styles = TextLinkStyles(SpanStyle(color = ZapActionText, fontWeight = FontWeight.SemiBold))
    val note = buildAnnotatedString {
        if (trust.hasSignals && !trust.leaked) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(disclaimer) }
            append(" ")
        }
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

/** A label, then each person as a 20 dp avatar and bold white name that opens their page on npub.world, separated by commas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleLine(prefix: String, people: List<TrustPerson>, trust: TrustUi, onOpenUrl: (String) -> Unit) {
    val body = MaterialTheme.typography.bodyMedium
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(prefix, color = ZapText2, style = body)
        people.forEachIndexed { index, person ->
            val profile = trust.profiles[person.pubkey]
            val name = if (profile == null && person.name.isNotBlank()) person.name else shortDisplayName(person.pubkey, profile)
            // The comma stays with its name, so a wrapped line never starts with one.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space1),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onOpenUrl(npubWorldUrl(person.pubkey)) }
                        .testTag("trustPerson:${person.pubkey}"),
                ) {
                    // Always drawn, so the row does not jump when the kind 0 arrives: until then, and for a
                    // person with no picture, it is the avatar drawn from their key.
                    ProfileImage(
                        pubkey = person.pubkey,
                        pictureUrl = profile?.picture,
                        profileVersion = profile?.eventId,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).clip(CircleShape),
                    )
                    Text(name, color = ZapText, fontWeight = FontWeight.SemiBold, style = body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (index < people.lastIndex) Text(",", color = ZapText2, style = body)
            }
        }
    }
}
