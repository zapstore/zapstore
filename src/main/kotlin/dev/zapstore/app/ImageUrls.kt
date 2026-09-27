package dev.zapstore.app

import android.net.Uri

private const val ZAPSTORE_CDN_HOST = "cdn.zapstore.dev"

enum class CdnImageVariant(
    val queryValue: String,
) {
    Icon("icon"),
    IconSmall("iconsm"),
    ThumbnailSmall("thumbsm"),
    ThumbnailLarge("thumblg"),
}

fun profileCdnUrl(pubkey: String): String? {
    if (pubkey.length != 64 || pubkey.any { it.digitToIntOrNull(16) == null }) return null
    return "https://$ZAPSTORE_CDN_HOST/${pubkey.lowercase()}.profile.webp"
}

/**
 * Requests a CDN image variant without changing URLs hosted elsewhere.
 *
 * Existing query parameters are preserved, except for an existing `class`
 * parameter, which is replaced with the requested variant.
 */
fun cdnImageUrl(
    imageUrl: String?,
    variant: CdnImageVariant,
): String? {
    if (imageUrl.isNullOrBlank()) return imageUrl

    val uri = runCatching { Uri.parse(imageUrl) }.getOrNull()
        ?: return imageUrl
    if (uri.host != ZAPSTORE_CDN_HOST) return imageUrl

    val builder = uri.buildUpon().clearQuery()
    uri.queryParameterNames
        .filter { it != "class" }
        .forEach { name ->
            uri.getQueryParameter(name)?.let { value ->
                builder.appendQueryParameter(name, value)
            }
        }
    return builder
        .appendQueryParameter("class", variant.queryValue)
        .build()
        .toString()
}

fun isHttpUrl(value: String): Boolean = value.startsWith("https://") || value.startsWith("http://")
