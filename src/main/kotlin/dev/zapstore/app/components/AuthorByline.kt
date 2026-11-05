package dev.zapstore.app.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zapstore.app.R
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapText2
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

/** The mark for a listing the indexer tracks: card index dividers, a catalog entry. Change it here only. */
const val INDEXED_GLYPH = "🗂️"

/** Emoji keep their own colors, so the indexed mark is drawn with the saturation taken out. */
internal fun Modifier.grayscale(): Modifier = graphicsLayer {
    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
}

/** Catalog-signed listing: the indexer published it. Tapping it opens that explanation. */
@Composable
fun IndexedMark(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.indexed_author)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .testTag("indexedAuthor"),
    ) {
        Text(
            INDEXED_GLYPH,
            modifier = Modifier.grayscale(),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 11.25.sp, lineHeight = 11.25.sp),
        )
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

/** Whom a person is called when no kind 0 names them. The npub is for a person's own page and evidence fields only. */
const val UNKNOWN_PERSON = "Someone"

/** The profile's name, or null while no kind 0 names them. For lines that read better without a person than with "Someone". */
fun resolvedName(profile: ProfileRecord?): String? =
    (profile?.displayName ?: profile?.name)?.trim()?.takeIf { it.isNotEmpty() }

/** The profile's name, or [UNKNOWN_PERSON]: a raw key in a row tells no one anything. */
@Suppress("UNUSED_PARAMETER")
fun shortDisplayName(pubkey: String, profile: ProfileRecord?): String =
    (profile?.displayName ?: profile?.name)?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_PERSON
