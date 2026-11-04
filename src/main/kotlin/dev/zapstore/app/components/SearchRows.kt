package dev.zapstore.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.StackRecord

/** A stack among search results: the same row as an app, with its tile, name and a Stack tag. */
@Composable
fun StackResultRow(
    stack: StackRecord,
    apps: Map<String, AppRecord>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tileSize: Dp = 30.dp,
) {
    ResultRow(
        title = stack.name,
        supporting = null,
        tag = stringResource(R.string.search_tag_stack),
        onClick = onClick,
        modifier = modifier,
        leading = { StackTile(stack.appIds, apps, size = tileSize) },
    )
}

/** A developer among search results: avatar, profile name, address and a Developer tag. [profile] is null until a kind 0 is stored. */
@Composable
fun DeveloperResultRow(
    pubkey: String,
    profile: ProfileRecord?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 60.dp,
) {
    ResultRow(
        title = shortDisplayName(pubkey, profile),
        supporting = profile?.nip05?.takeIf { it.isNotBlank() },
        tag = stringResource(R.string.search_tag_developer),
        onClick = onClick,
        modifier = modifier,
        leading = {
            Box(Modifier.size(avatarSize).clip(CircleShape).background(ZapSurface3)) {
                ProfileImage(
                    pubkey = pubkey,
                    pictureUrl = profile?.picture,
                    profileVersion = profile?.eventId,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                )
            }
        },
    )
}

@Composable
private fun ResultRow(
    title: String,
    supporting: String?,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier,
    leading: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZapRadius.md))
            .clickable(onClick = onClick)
            .padding(vertical = ZapSpacing.space3),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, color = ZapText, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (supporting != null) {
                Text(
                    supporting,
                    color = ZapText2,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        ZapBadge(tag, BadgeTone.Neutral)
    }
}
