package dev.zapstore.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File
import dev.zapstore.app.CdnImageVariant
import dev.zapstore.app.R
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapMarkdown
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.app.ZapVersionPillBackground
import dev.zapstore.app.cdnImageUrl
import dev.zapstore.app.isHttpUrl
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.ProfileRecord

@Composable
fun AppIcon(
    title: String,
    iconUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    iconFile: File? = null,
    cornerRadius: Dp = size * ZapRadius.appTileFraction,
) {
    val remoteReady = rememberRemoteImagesReady()
    val model = when {
        iconFile != null && iconFile.isFile -> iconFile
        remoteReady -> iconUrl?.takeIf(::isHttpUrl)?.let { cdnImageUrl(it, CdnImageVariant.Icon) }
        else -> null
    }
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(ZapSurface3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.take(1).uppercase(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleLarge,
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = stringResource(R.string.app_icon_description, title),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** One catalog listing in a list. [author] is the stored kind 0 for [AppRecord.authorPubkey], if any. */
@Composable
fun AppCard(
    app: AppRecord,
    author: ProfileRecord?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAuthorClick: ((String) -> Unit)? = null,
    showAuthor: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZapRadius.lg))
            .background(ZapSurface1)
            .border(1.dp, ZapLine, RoundedCornerShape(ZapRadius.lg))
            .clickable(onClick = onClick)
            .padding(ZapSpacing.space4),
    ) {
        AppIdentityRow(
            name = app.name,
            iconUrl = app.iconUrl,
            iconFile = app.iconFile,
            version = app.version,
            authorPubkey = app.authorPubkey.takeIf { showAuthor },
            author = author,
            onAuthorClick = onAuthorClick,
        )
        if (app.summary.isNotBlank()) {
            Spacer(Modifier.height(ZapSpacing.space2))
            Text(
                text = ZapMarkdown.parse(app.summary).text,
                color = ZapTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Icon, name, version pill and author byline; shared by cards and the app detail header. */
@Composable
fun AppIdentityRow(
    name: String,
    iconUrl: String?,
    version: String?,
    authorPubkey: String?,
    author: ProfileRecord?,
    modifier: Modifier = Modifier,
    iconFile: File? = null,
    onAuthorClick: ((String) -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val iconSize = (maxWidth * 0.21f).coerceIn(50.dp, 68.dp)
        Row(verticalAlignment = Alignment.Top) {
            AppIcon(title = name, iconUrl = iconUrl, iconFile = iconFile, size = iconSize)
            Spacer(Modifier.width(ZapSpacing.space3))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 23.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Black,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    version?.takeIf { it.isNotBlank() }?.let { VersionPill(it) }
                    authorPubkey?.let { pubkey ->
                        AuthorByline(
                            pubkey = pubkey,
                            profile = author,
                            onClick = onAuthorClick?.let { click -> { click(pubkey) } },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VersionPill(version: String, modifier: Modifier = Modifier) {
    Text(
        text = version,
        color = ZapTextSecondary,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .clip(RoundedCornerShape(ZapRadius.full))
            .background(ZapVersionPillBackground)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
