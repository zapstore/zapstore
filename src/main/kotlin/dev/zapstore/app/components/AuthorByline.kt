package dev.zapstore.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import dev.zapstore.app.ZapSurface3
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showBy) {
            Text(
                text = stringResource(R.string.app_by_author, ""),
                color = ZapTextSecondary,
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
            color = ZapTextSecondary,
            style = if (name.startsWith("npub1")) style.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum") else style.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Profile name, or an abbreviated npub when no kind 0 is known. */
fun shortDisplayName(pubkey: String, profile: ProfileRecord?): String {
    val name = profile?.displayNameOrNpub ?: pubkey.toNpub()
    return if (name.startsWith("npub1") && name.length > 18) "${name.take(9)}…${name.takeLast(4)}" else name.trim()
}
