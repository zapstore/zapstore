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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
                    model = url,
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
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ZapSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(
                title = app.name,
                iconUrl = app.iconUrl,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        release?.let {
                            append(it.version)
                            it.channel?.let { channel -> append(" · ").append(channel) }
                            append(" · ")
                        }
                        append(app.identifier)
                    },
                    color = ZapMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
        ProfileComponent(
            pubkey = app.event.pubKey,
            repository = repository,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (app.summary.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            MarkdownText(
                value = app.summary,
                color = ZapMuted,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
        Spacer(Modifier.height(12.dp))
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

@Composable
fun SectionTitle(
    value: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier,
    )
}

@Composable
fun StatusText(
    value: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value,
        color = ZapMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier,
    )
}

fun isHttpUrl(value: String): Boolean =
    value.startsWith("https://") || value.startsWith("http://")
