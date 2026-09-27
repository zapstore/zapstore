package dev.zapstore.app.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.toNpub

/** "by <avatar> <name>" for an app author. [profile] is null until the kind 0 is stored. */
@Composable
fun AuthorByline(
    pubkey: String,
    profile: ProfileRecord?,
    modifier: Modifier = Modifier,
    showBy: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .testTag("profile:$pubkey")
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showBy) {
            Text(
                text = stringResource(R.string.app_by_author, ""),
                color = ZapTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ProfileImage(
            pubkey = pubkey,
            pictureUrl = profile?.picture,
            profileVersion = profile?.eventId,
            contentDescription = null,
            modifier = Modifier
                .size(17.dp)
                .clip(CircleShape),
        )
        Text(
            text = shortDisplayName(pubkey, profile),
            color = ZapTextSecondary,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Profile name, or an abbreviated npub when no kind 0 is known. */
fun shortDisplayName(pubkey: String, profile: ProfileRecord?): String {
    val name = profile?.displayNameOrNpub ?: pubkey.toNpub()
    return if (name.startsWith("npub") && name.length > 18) "${name.take(10)}…${name.takeLast(6)}" else name
}
