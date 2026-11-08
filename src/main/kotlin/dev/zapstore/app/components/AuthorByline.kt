package dev.zapstore.app.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.toNpub

/** "by <avatar> <name>" for an app author. [profile] is null until the kind 0 is stored. */
@Composable
fun AuthorByline(
    pubkey: String,
    profile: ProfileRecord?,
    modifier: Modifier = Modifier,
    showBy: Boolean = true,
    /** Replaces the "by" lead. */
    lead: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .testTag("profile:$pubkey")
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showBy) {
            Text(
                text = lead ?: stringResource(R.string.app_by_author, ""),
                color = ZapText2,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box(Modifier.size(16.dp).clip(CircleShape).background(ZapSurface3)) {
            ProfileImage(
                pubkey = pubkey,
                pictureUrl = profile?.picture,
                profileVersion = profile?.eventId,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
            )
        }
        val name = shortDisplayName(pubkey, profile)
        val style = MaterialTheme.typography.bodySmall
        Text(
            text = name,
            color = ZapText2,
            style = style.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** The warning beside a listing signer who has not proved the app is theirs. Tapping it opens that explanation. */
@Composable
fun UnverifiedMark(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val label = stringResource(R.string.unverified_author)
    // The same 28 dp box as [VerifiedSeal], so either mark takes the same room on the author line.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size + 12.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag("unverifiedAuthor"),
    ) {
        Image(painterResource(R.drawable.ic_unverified), contentDescription = null, modifier = Modifier.size(size))
    }
}

/** The info mark beside the source of a catalog-listed app: tapping it opens the same sheet as the author marks. */
@Composable
fun InfoMark(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val label = stringResource(R.string.info_about_listing)
    // The same 28 dp box as the other marks, so every kind takes the same room on the line.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size + 12.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag("indexedAuthor"),
    ) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = ZapText3, modifier = Modifier.size(size + 2.dp))
    }
}

/** The web verified mark: a blue scalloped badge with a white tick. Tapping it opens how the proof was checked. */
@Composable
fun VerifiedSeal(onClick: (() -> Unit)? = null, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size + 8.dp)
            .then(
                if (onClick != null) {
                    Modifier.clip(CircleShape).clickable(onClick = onClick).testTag("verifiedSeal")
                } else {
                    Modifier
                },
            ),
    ) {
        Image(
            painterResource(R.drawable.ic_verified),
            contentDescription = stringResource(R.string.verified_author),
            modifier = Modifier.size(size),
        )
    }
}

/** The key as `npub1...z6g`: what a person with no kind 0 name is called. The whole key is for evidence fields. */
fun shortNpub(pubkey: String): String {
    val npub = pubkey.toNpub()
    return if (npub.length > 12) npub.take(5) + "..." + npub.takeLast(3) else npub
}

/** The profile's name, or null while no kind 0 names them. For lines that read better without a person. */
fun resolvedName(profile: ProfileRecord?): String? =
    (profile?.displayName ?: profile?.name)?.trim()?.takeIf { it.isNotEmpty() }

/** The profile's name, or the short npub (`npub1...z6g`) while no kind 0 names them. */
fun shortDisplayName(pubkey: String, profile: ProfileRecord?): String =
    resolvedName(profile) ?: shortNpub(pubkey)
