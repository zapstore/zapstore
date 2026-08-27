package dev.zapstore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.zapstore.app.PROFILE_CACHE_DURATION
import kotlinx.coroutines.flow.collect

@Composable
fun rememberProfile(pubkey: String, repository: CatalogRepository?): ProfileInfo? {
    val profile by produceState<ProfileInfo?>(initialValue = null, pubkey, repository) {
        if (repository == null) return@produceState

        repository.profile(pubkey).collect { value = it }
    }
    return profile
}

fun profileDisplayName(profile: ProfileInfo?, pubkey: String): String =
    profile?.displayName
        ?.takeIf(String::isNotBlank)
        ?: profile?.name?.takeIf(String::isNotBlank)
        ?: pubkey.toNpub()

@Composable
fun rememberProfileDisplayName(
    pubkey: String,
    repository: CatalogRepository?,
): String = profileDisplayName(rememberProfile(pubkey, repository), pubkey)

@Composable
fun ProfileComponent(
    pubkey: String,
    repository: CatalogRepository?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val profile = rememberProfile(pubkey, repository)
    val displayName = profileDisplayName(profile, pubkey)

    Row(
        modifier = modifier
            .testTag("profile:$pubkey")
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(ZapSurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            ProfileImage(
                pubkey = pubkey,
                pictureUrl = profile?.picture,
                profileVersion = profile?.event?.id,
                contentDescription = stringResource(R.string.profile_avatar_description, displayName),
                modifier = Modifier
                .size(40.dp)
                    .clip(CircleShape),
            )
        }
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ProfileImage(
    pubkey: String,
    pictureUrl: String?,
    profileVersion: String? = null,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val cdnUrl = remember(pubkey) {
        profileCdnUrl(pubkey)?.let { cdnImageUrl(it, CdnImageVariant.IconSmall) }
    }
    var useFallback by remember(pubkey) { mutableStateOf(cdnUrl == null) }
    val fallbackUrl = pictureUrl?.takeIf(::isHttpUrl)
    val imageUrl = if (useFallback) fallbackUrl else cdnUrl
    var imageLoaded by remember(imageUrl) { mutableStateOf(false) }
    val imageAlpha by animateFloatAsState(
        targetValue = if (imageLoaded) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "profile icon fade",
    )
    val context = LocalPlatformContext.current
    var previousProfileVersion by remember(pubkey) { mutableStateOf<String?>(null) }

    LaunchedEffect(profileVersion) {
        if (profileVersion != null &&
            previousProfileVersion != null &&
            previousProfileVersion != profileVersion
        ) {
            evictProfileIconCache(
                context = context,
                urls = listOfNotNull(cdnUrl, fallbackUrl),
            )
        }
        if (profileVersion != null) previousProfileVersion = profileVersion
    }

    imageUrl?.let { url ->
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
            onLoading = { imageLoaded = false },
            onSuccess = { imageLoaded = true },
            onError = {
                imageLoaded = false
                if (!useFallback && fallbackUrl != null) useFallback = true
            },
            modifier = modifier.alpha(imageAlpha),
        )
    }
}

private fun evictProfileIconCache(context: android.content.Context, urls: List<String>) {
    val imageLoader = SingletonImageLoader.get(context)
    urls.forEach { url ->
        val cacheKey = profileIconCacheKey(url)
        imageLoader.memoryCache?.remove(MemoryCache.Key(cacheKey))
        imageLoader.diskCache?.remove(cacheKey)
    }
}

internal fun profileIconCacheKey(url: String, nowMillis: Long = System.currentTimeMillis()): String {
    val cachePeriod = PROFILE_CACHE_DURATION.inWholeMilliseconds
    val period = nowMillis / cachePeriod
    return "profile-icon:$period:$url"
}

private val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private val BECH32_GENERATOR = longArrayOf(
    0x3b6a57b2L,
    0x26508e6dL,
    0x1ea119faL,
    0x3d4233ddL,
    0x2a1462b3L,
)

private fun String.toNpub(): String {
    if (length != 64 || any { it.digitToIntOrNull(16) == null }) return this
    val bytes = chunked(2).map { it.toInt(16) }
    val data = convertBits(bytes, 8, 5, true)
    val checksum = createBech32Checksum("npub", data)
    return buildString {
        append("npub1")
        (data + checksum).forEach { append(BECH32_CHARSET[it]) }
    }
}

private fun convertBits(data: List<Int>, fromBits: Int, toBits: Int, pad: Boolean): List<Int> {
    var accumulator = 0
    var bits = 0
    val result = mutableListOf<Int>()
    val maxValue = (1 shl toBits) - 1
    data.forEach { value ->
        accumulator = (accumulator shl fromBits) or value
        bits += fromBits
        while (bits >= toBits) {
            bits -= toBits
            result += (accumulator shr bits) and maxValue
        }
    }
    if (pad && bits > 0) result += (accumulator shl (toBits - bits)) and maxValue
    return result
}

private fun createBech32Checksum(hrp: String, data: List<Int>): List<Int> {
    val values = expandHrp(hrp) + data + List(6) { 0 }
    val polymod = bech32Polymod(values) xor 1L
    return (0 until 6).map { shift ->
        ((polymod shr (5 * (5 - shift))) and 31).toInt()
    }
}

private fun expandHrp(hrp: String): List<Int> =
    hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

private fun bech32Polymod(values: List<Int>): Long {
    var checksum = 1L
    values.forEach { value ->
        val top = checksum shr 25
        checksum = ((checksum and 0x1ffffffL) shl 5) xor value.toLong()
        BECH32_GENERATOR.forEachIndexed { index, generator ->
            if (((top shr index) and 1L) != 0L) checksum = checksum xor generator
        }
    }
    return checksum
}
