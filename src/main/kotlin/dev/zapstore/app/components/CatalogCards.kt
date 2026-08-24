package dev.zapstore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.TextStyle
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
    cornerRadius: Dp = 16.dp,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape),
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
    val authorName = rememberProfileDisplayName(app.event.pubKey, repository)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ZapSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            AppIcon(
                title = app.name,
                iconUrl = app.iconUrl,
                modifier = Modifier.size(52.dp),
            )
            Spacer(modifier.width(12.dp))
            Column(modifier.weight(1f)) {
                AppNameWithByline(
                    name = app.name,
                    authorName = authorName,
                    nameStyle = MaterialTheme.typography.titleMedium,
                    onAuthorClick = onProfileClick,
                    authorTestTag = "profile:${app.event.pubKey}",
                )
                release?.let {
                    Spacer(Modifier.height(8.dp))
                    VersionPill(version = it.version)
                }
            }
        }
        if (app.summary.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = ZapMarkdown.parse(app.summary).text,
                color = ZapMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun AppNameWithByline(
    name: String,
    authorName: String,
    nameStyle: TextStyle,
    modifier: Modifier = Modifier,
    onAuthorClick: (() -> Unit)? = null,
    authorTestTag: String? = null,
) {
    val bylineSize = (nameStyle.fontSize.value * 0.72f).sp
    Column(modifier = modifier) {
        Text(
            text = name,
            style = nameStyle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.app_by_author, authorName),
            color = ZapMuted,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = bylineSize,
                fontWeight = FontWeight.Normal,
                fontFamily = InterFontFamily,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .then(authorTestTag?.let { Modifier.testTag(it) } ?: Modifier)
                .then(onAuthorClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        )
    }
}

@Composable
fun VersionPill(
    version: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = version,
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ZapPrimary)
            .padding(horizontal = 10.dp, vertical = 4.dp),
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
            .clip(RoundedCornerShape(18.dp))
            .background(ZapSurface)
            .clickable(onClick = onClick)
            .padding(14.dp),
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
