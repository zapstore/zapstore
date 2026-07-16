package dev.zapstore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import kotlinx.coroutines.flow.collect
import kotlin.time.Duration.Companion.hours

@Composable
fun rememberProfile(pubkey: String, repository: CatalogRepository?): ProfileInfo? {
    val profile by produceState<ProfileInfo?>(initialValue = null, pubkey, repository) {
        if (repository == null) return@produceState

        repository.query(
            Filter(authors = listOf(pubkey), kinds = listOf(Catalog.profileKind), limit = 1),
            type = QueryType.LocalAndRemote,
            cachedFor = PROFILE_CACHE_DURATION,
            relays = PROFILE_RELAYS,
        ).collect { state ->
            value = state.items.maxByOrNull(Event::createdAt)?.let(::ProfileInfo)
        }
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
    val pictureUrl = profile?.picture?.takeIf(::isHttpUrl)

    Row(
        modifier = modifier
            .testTag("profile:$pubkey")
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(ZapSurfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("•", color = ZapMuted, style = MaterialTheme.typography.titleLarge)
            pictureUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = stringResource(R.string.profile_avatar_description, displayName),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                )
            }
        }
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
private val PROFILE_CACHE_DURATION = 6.hours

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
