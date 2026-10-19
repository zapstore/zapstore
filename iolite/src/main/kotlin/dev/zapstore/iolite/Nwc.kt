package dev.zapstore.iolite

import java.net.URI
import java.net.URLDecoder

/** A NIP-47 `nostr+walletconnect://` connection. [secret] is the hex key this app signs requests with. */
data class NwcConnection(
    val walletPubkey: String,
    val relays: Set<RelayUrl>,
    val secret: String,
    val lud16: String?,
) {
    companion object {
        const val SCHEME = "nostr+walletconnect"

        fun parse(input: String): NwcConnection? = runCatching {
            val text = input.trim()
            if (!text.startsWith("$SCHEME://")) return null
            val uri = URI(text.replaceFirst("$SCHEME://", "nwc://"))
            val wallet = (uri.host ?: uri.authority)?.lowercase()?.takeIf { it.isHex(64) } ?: return null
            val params = uri.rawQuery.orEmpty().split('&').filter { '=' in it }.map {
                val (key, value) = it.split('=', limit = 2)
                key to URLDecoder.decode(value, Charsets.UTF_8)
            }
            val relays = params.filter { it.first == "relay" }.mapNotNull { runCatching { it.second.normalizeRelayUrl() }.getOrNull() }.toSet()
            val secret = params.firstOrNull { it.first == "secret" }?.second?.lowercase()?.takeIf { it.isHex(64) } ?: return null
            if (relays.isEmpty()) return null
            NwcConnection(wallet, relays, secret, params.firstOrNull { it.first == "lud16" }?.second)
        }.getOrNull()

        private fun String.isHex(length: Int) = this.length == length && all { it in '0'..'9' || it in 'a'..'f' }
    }
}

class NwcException(val code: String, message: String) : Exception(message)
