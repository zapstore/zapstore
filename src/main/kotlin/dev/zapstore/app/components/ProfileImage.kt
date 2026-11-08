package dev.zapstore.app.components

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.profileCdnUrl

/**
 * Avatar from the bundle, then the Zapstore CDN, then the profile's own picture URL.
 * A new [profileVersion] (kind 0 event ID) evicts the cached image so an updated avatar shows.
 * A person with no picture, or one that will not load, gets a [KeyAvatar] drawn from their key.
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
    var failed by remember(pubkey, pictureUrl) { mutableStateOf(false) }

    LaunchedEffect(profileVersion) {
        if (profileVersion != null && previousVersion != null && previousVersion != profileVersion) {
            evictProfileIconCache(context, listOfNotNull(cdnUrl, fallbackUrl))
        }
        if (profileVersion != null) previousVersion = profileVersion
    }

    Box(modifier) {
        if (localAvatar == null && (fallbackUrl == null || failed)) KeyAvatar(pubkey, Modifier.fillMaxSize())
        if (localAvatar != null) {
            AsyncImage(
                model = localAvatar,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
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
                onError = { if (!useFallback && fallbackUrl != null) useFallback = true else failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The picture for a person with no profile picture: a soft gradient between two hues, with a lighter glow
 * off to one side, all drawn from the key, so the same person looks the same everywhere. No text, so no one
 * is named by it.
 */
@Composable
fun KeyAvatar(pubkey: String, modifier: Modifier = Modifier) {
    val bytes = remember(pubkey) { pubkey.chunked(2).mapNotNull { it.toIntOrNull(16) } }
    Canvas(modifier.background(ZapSurface3)) {
        if (bytes.size < 8) return@Canvas
        val hue = (bytes[0] * 256 + bytes[1]) % 360
        // The second hue sits 40 to 100 degrees away: near enough to blend, far enough to read as a gradient.
        val other = (hue + 40 + bytes[2] % 60) % 360
        val angle = Math.toRadians((bytes[3] * 360.0) / 256.0)
        val half = Offset(size.width / 2f, size.height / 2f)
        val reach = Offset((kotlin.math.cos(angle) * size.width / 2f).toFloat(), (kotlin.math.sin(angle) * size.height / 2f).toFloat())
        drawRect(
            Brush.linearGradient(
                listOf(Color.hsv(hue.toFloat(), 0.55f, 0.88f), Color.hsv(other.toFloat(), 0.62f, 0.62f)),
                start = half - reach,
                end = half + reach,
            ),
        )
        // A pale glow on the side the gradient starts from, so two people with near hues still differ.
        val glow = Offset(size.width * (0.2f + 0.6f * bytes[4] / 255f), size.height * (0.2f + 0.6f * bytes[5] / 255f))
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent), center = glow, radius = size.minDimension * 0.7f),
            radius = size.minDimension * 0.7f,
            center = glow,
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
