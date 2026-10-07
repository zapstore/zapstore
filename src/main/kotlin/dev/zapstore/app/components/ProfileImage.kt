package dev.zapstore.app.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.zapstore.app.AppConfig
import dev.zapstore.app.CdnImageVariant
import dev.zapstore.app.ZapstoreApplication
import dev.zapstore.app.cdnImageUrl
import dev.zapstore.app.isHttpUrl
import dev.zapstore.app.profileCdnUrl

/**
 * Avatar from the bundle, then the Zapstore CDN, then the profile's own picture URL.
 * A new [profileVersion] (kind 0 event ID) evicts the cached image so an updated avatar shows.
 */
@Composable
fun ProfileImage(
    pubkey: String,
    pictureUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    profileVersion: String? = null,
) {
    val remoteReady = rememberRemoteImagesReady()
    val context = LocalPlatformContext.current
    val localAvatar = profileCdnUrl(pubkey)?.let {
        (context.applicationContext as? ZapstoreApplication)?.iolite?.avatarFile(pubkey)
    }?.takeIf { it.isFile }
    val cdnUrl = remember(pubkey) { profileCdnUrl(pubkey)?.let { cdnImageUrl(it, CdnImageVariant.IconSmall) } }
    var useFallback by remember(pubkey) { mutableStateOf(cdnUrl == null) }
    val fallbackUrl = pictureUrl?.takeIf(::isHttpUrl)
    val imageUrl = if (useFallback) fallbackUrl else cdnUrl
    var previousVersion by remember(pubkey) { mutableStateOf<String?>(null) }

    LaunchedEffect(profileVersion) {
        if (profileVersion != null && previousVersion != null && previousVersion != profileVersion) {
            evictProfileIconCache(context, listOfNotNull(cdnUrl, fallbackUrl))
        }
        if (profileVersion != null) previousVersion = profileVersion
    }

    if (localAvatar != null) {
        AsyncImage(
            model = localAvatar,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else if (remoteReady) imageUrl?.let { url ->
        val request = remember(url) {
            ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey(profileIconCacheKey(url))
                .diskCacheKey(profileIconCacheKey(url))
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            onError = { if (!useFallback && fallbackUrl != null) useFallback = true },
            modifier = modifier,
        )
    }
}

private fun evictProfileIconCache(context: Context, urls: List<String>) {
    val imageLoader = SingletonImageLoader.get(context)
    urls.forEach { url ->
        val cacheKey = profileIconCacheKey(url)
        imageLoader.memoryCache?.remove(MemoryCache.Key(cacheKey))
        imageLoader.diskCache?.remove(cacheKey)
    }
}

/** Cache key that rolls over once per profile cache period so stale avatars refresh on their own. */
internal fun profileIconCacheKey(url: String, nowMillis: Long = System.currentTimeMillis()): String {
    val period = nowMillis / AppConfig.profileCacheDuration.inWholeMilliseconds
    return "profile-icon:$period:$url"
}
