package dev.zapstore.iolite

import java.net.URI
import java.net.URLEncoder

@JvmInline
value class RelayUrl(val url: String) {
    override fun toString(): String = url
}

/**
 * One spelling per relay: lowercase scheme and host, no default port, no fragment, no repeated or
 * trailing slashes. Throws unless this is a single ws:// or wss:// URL; a joined list such as
 * `wss://a wss://b` or `wss://a,wss://b` is rejected rather than merged into one URL.
 */
fun String.normalizeRelayUrl(): RelayUrl {
    val trimmed = trim()
    require(trimmed.length <= MAX_RELAY_URL && trimmed.none { it.isWhitespace() || it.isISOControl() || it == ',' }) {
        "relay URL must be a single URL"
    }
    val uri = runCatching { URI(trimmed) }.getOrElse { throw IllegalArgumentException("invalid relay URL", it) }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "ws" || scheme == "wss") { "relay URL must be ws:// or wss://" }
    val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("relay URL needs a host")
    require(uri.rawUserInfo == null) { "relay URL must not carry credentials" }
    val port = uri.port.takeUnless { it == -1 || (scheme == "wss" && it == 443) || (scheme == "ws" && it == 80) }
    val path = uri.rawPath.orEmpty().replace(Regex("/{2,}"), "/").trimEnd('/')
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return RelayUrl("$scheme://$host${port?.let { ":$it" }.orEmpty()}$path$query")
}

/** [normalizeRelayUrl], or null for anything that is not one WebSocket URL. */
fun String.relayUrlOrNull(): RelayUrl? = runCatching { normalizeRelayUrl() }.getOrNull()

private const val MAX_RELAY_URL = 4_096

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

/** `GET /v1/bundle` on the relay origin. [client] is the Zapstore version sent as `v`. */
fun RelayUrl.bundleUrl(from: Long, client: String = ""): String {
    val base = origin() + "/v1/bundle?from=$from"
    if (client.isEmpty()) return base
    return base + "&v=" + URLEncoder.encode(client, "UTF-8")
}

/** True when the host is a Tor onion service. */
val RelayUrl.isOnion: Boolean
    get() = host().endsWith(".onion")

/** True for a relay on this machine. Its address is the one the bundle was fetched from. */
val RelayUrl.isLoopback: Boolean
    get() = host().let { it == "127.0.0.1" || it == "localhost" || it == "::1" }

private fun RelayUrl.host(): String {
    val authority = url.substringAfter("://").substringBefore('/')
    if (authority.startsWith("[")) return authority.substringBefore(']').substringAfter('[')
    return authority.substringBefore(':')
}

private fun RelayUrl.origin(): String {
    val http = originHttpUrl().substringBefore('#').substringBefore('?')
    val schemeEnd = http.indexOf("://")
    require(schemeEnd > 0) { "invalid relay URL $url" }
    val afterScheme = http.substring(schemeEnd + 3)
    val slash = afterScheme.indexOf('/')
    val authority = (if (slash == -1) afterScheme else afterScheme.substring(0, slash)).trimEnd('/')
    return http.substring(0, schemeEnd + 3) + authority
}
