package dev.zapstore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

@Composable
fun AppIcon(
    title: String,
    iconUrl: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(ZapIconBackground),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.take(1).uppercase(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleLarge,
        )
        iconUrl
            ?.takeIf(::isHttpUrl)
            ?.let { url ->
                AsyncImage(
                    model = cdnImageUrl(url, CdnImageVariant.Icon),
                    contentDescription = stringResource(R.string.app_icon_description, title),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
    }
}

@Composable
fun AppCard(
    app: AppInfo,
    onClick: () -> Unit,
    repository: CatalogRepository? = null,
    modifier: Modifier = Modifier,
    release: ReleaseInfo? = null,
    onProfileClick: (() -> Unit)? = null,
) {
    val authorProfile = rememberProfile(app.event.pubKey, repository)
    val authorName = profileDisplayName(authorProfile, app.event.pubKey)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ZapSurface.copy(alpha = 0.6f))
            .border(1.dp, ZapOutline.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        BoxWithConstraints {
            val iconSize = (maxWidth * 0.21f).coerceIn(50.dp, 68.dp)
            Row(verticalAlignment = Alignment.Top) {
                AppIcon(
                    title = app.name,
                    iconUrl = app.iconUrl,
                    modifier = Modifier.size(iconSize),
                    cornerRadius = 14.dp,
                )
                Spacer(modifier.width(14.dp))
                Column(modifier.weight(1f)) {
                    AppNameWithByline(
                        name = app.name,
                        authorName = authorName,
                        authorProfile = authorProfile,
                        authorPubkey = app.event.pubKey,
                        version = release?.version,
                        onAuthorClick = onProfileClick,
                        authorTestTag = "profile:${app.event.pubKey}",
                    )
                }
            }
        }
        if (app.summary.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = ZapMarkdown.parse(app.summary).text,
                color = ZapMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun AppNameWithByline(
    name: String,
    authorName: String,
    authorProfile: ProfileInfo?,
    authorPubkey: String,
    version: String?,
    modifier: Modifier = Modifier,
    onAuthorClick: (() -> Unit)? = null,
    authorTestTag: String? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 19.sp,
                lineHeight = 23.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            version?.let { VersionPill(version = it) }
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(authorTestTag?.let { Modifier.testTag(it) } ?: Modifier)
                    .then(onAuthorClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_by_author, ""),
                    color = ZapMuted,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Normal),
                )
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .clip(RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    ProfileImage(
                        pubkey = authorPubkey,
                        pictureUrl = authorProfile?.picture,
                        profileVersion = authorProfile?.event?.id,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    text = authorName,
                    color = ZapMuted,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@Composable
fun VersionPill(
    version: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = version,
        color = ZapActionForeground,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ZapVersionPill)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
fun StackCard(
    stack: StackInfo,
    appsByAddress: Map<String, AppInfo>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(176.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(ZapSurface.copy(alpha = 0.6f))
            .border(1.dp, ZapOutline.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(
            text = stack.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stack.description.ifBlank { "${stack.appAddresses.size} apps" },
            color = ZapMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            stack.appAddresses.take(3).forEach { address ->
                val app = appsByAddress[address]
                AppIcon(
                    title = app?.name ?: "?",
                    iconUrl = app?.iconUrl,
                    modifier = Modifier.size(34.dp),
                    cornerRadius = 10.dp,
                )
            }
        }
    }
}
