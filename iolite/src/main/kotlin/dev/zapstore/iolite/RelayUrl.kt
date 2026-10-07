package dev.zapstore.iolite

@JvmInline
value class RelayUrl(val url: String) {
    override fun toString(): String = url
}

fun String.normalizeRelayUrl(): RelayUrl {
    val trimmed = trim()
    require(trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) {
        "relay URL must be ws:// or wss://"
    }
    val withoutTrailing = trimmed.trimEnd('/')
    return RelayUrl(withoutTrailing)
}

fun String.httpOriginToRelayUrl(): RelayUrl {
    val trimmed = trim().trimEnd('/')
    val ws = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
        trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
        else -> trimmed
    }
    return ws.normalizeRelayUrl()
}

fun RelayUrl.originHttpUrl(): String =
    url.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")

/** `GET /bundle` on the relay origin; path, query, and fragment are ignored. */
fun RelayUrl.bundleUrl(from: Long): String = origin() + "/bundle?from=$from"

/** True when the host is a Tor onion service. */
val RelayUrl.isOnion: Boolean
    get() = url.substringAfter("://").substringBefore('/').substringBefore(':').endsWith(".onion")

private fun RelayUrl.origin(): String {
    val http = originHttpUrl().substringBefore('#').substringBefore('?')
    val schemeEnd = http.indexOf("://")
    require(schemeEnd > 0) { "invalid relay URL $url" }
    val afterScheme = http.substring(schemeEnd + 3)
    val slash = afterScheme.indexOf('/')
    val authority = (if (slash == -1) afterScheme else afterScheme.substring(0, slash)).trimEnd('/')
    return http.substring(0, schemeEnd + 3) + authority
}
