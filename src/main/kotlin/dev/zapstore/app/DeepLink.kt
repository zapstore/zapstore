package dev.zapstore.app

import dev.zapstore.iolite.Kinds
import dev.zapstore.iolite.decodeNaddr
import dev.zapstore.iolite.decodeNevent
import dev.zapstore.iolite.decodeNote
import dev.zapstore.iolite.decodeNprofile
import dev.zapstore.iolite.decodeNpub
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Where an incoming link opens. */
sealed interface DeepLink {
    data class App(val appId: String) : DeepLink
    data class Stack(val pubkey: String, val identifier: String) : DeepLink
    data class Profile(val pubkey: String) : DeepLink
    /** Home search. An empty [query] opens the screen without replacing what is already typed. */
    data class Search(val query: String) : DeepLink
    /** The full stacks list. */
    data object Stacks : DeepLink
    /** The My apps tab, from the updates notification. */
    data object MyApps : DeepLink
    /** A note, nevent, or other address, opened in the browser. */
    data class External(val url: String) : DeepLink

    companion object {
        private val hosts = setOf("zapstore.dev", "www.zapstore.dev")
        private const val WEB = "https://zapstore.dev"

        /**
         * `https://zapstore.dev/apps`, `/apps?q=`, `/apps/<app-id|naddr>`, `/stacks`, `/stacks/<naddr>`
         * or `/stacks/<npub>/<id>`, `/profile/<npub|nprofile|hex>`, `nostr:<npub|nprofile|naddr|note|nevent>`,
         * `market://details?id=<app-id>`, and `market://search?q=`. Anything else is null.
         */
        fun parse(link: String): DeepLink? = runCatching {
            val uri = URI(link.trim())
            when (uri.scheme?.lowercase()) {
                "market" -> when (uri.host?.lowercase()) {
                    "details" -> uri.query(name = "id")?.let(::App)
                    "search" -> Search(uri.query("q").orEmpty().trim())
                    else -> null
                }
                "nostr" -> nostr(uri.schemeSpecificPart)
                "https" -> if (uri.host?.lowercase() in hosts) web(uri) else null
                else -> null
            }
        }.getOrNull()

        /** A NIP-21 `nostr:` URI: profile, app, stack, or a browser page for a note or other address. */
        private fun nostr(part: String?): DeepLink? {
            val token = part.orEmpty().substringBefore('?').substringBefore('#').removePrefix("//").trim()
            val href = nip19Href(token) ?: return null
            token.decodeNpub()?.let { return Profile(it) }
            token.decodeNprofile()?.let { return Profile(it) }
            token.decodeNaddr()?.let { addr ->
                return when (addr.kind) {
                    Kinds.App -> App(addr.identifier)
                    Kinds.AppStack -> Stack(addr.pubkey, addr.identifier)
                    else -> External(href)
                }
            }
            return External(href)
        }

        /**
         * Https link for a `nostr:`-optional NIP-19 token: a profile, an app, a stack, or njump for a
         * note, an nevent, or an address this app does not open. Null when the token does not decode.
         */
        fun nip19Href(entity: String): String? {
            val token = entity.trim().lowercase().removePrefix("nostr:")
            if (token.decodeNpub() != null || token.decodeNprofile() != null) return "$WEB/profile/$token"
            token.decodeNaddr()?.let { addr ->
                val page = when (addr.kind) {
                    Kinds.App -> "apps"
                    Kinds.AppStack -> "stacks"
                    else -> null
                }
                return if (page != null) "$WEB/$page/$token" else "https://njump.me/$token"
            }
            if (token.decodeNote() != null || token.decodeNevent() != null) return "https://njump.me/$token"
            return null
        }

        /** Home search for a hashtag, without the `#`. */
        fun hashtagHref(tag: String): String = "$WEB/apps?q=${URLEncoder.encode(tag, Charsets.UTF_8.name())}"

        private fun web(uri: URI): DeepLink? {
            val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }
            if (parts.size == 1) {
                return when (parts[0]) {
                    "apps" -> Search(uri.query("q").orEmpty().trim())
                    "stacks" -> Stacks
                    else -> null
                }
            }
            val first = parts.getOrNull(1) ?: return null
            return when (parts[0]) {
                "apps" -> first.decodeNaddr()?.takeIf { it.kind == Kinds.App }?.let { App(it.identifier) }
                    ?: first.takeIf { '.' in it && !it.startsWith("naddr1") }?.let(::App)
                "stacks" -> first.decodeNaddr()?.takeIf { it.kind == Kinds.AppStack }?.let { Stack(it.pubkey, it.identifier) }
                    ?: parts.getOrNull(2)?.let { id -> pubkey(first)?.let { Stack(it, id) } }
                "profile" -> pubkey(first)?.let(::Profile)
                else -> null
            }
        }

        private fun pubkey(value: String): String? = value.decodeNpub()
            ?: value.decodeNprofile()
            ?: value.lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

        private fun URI.query(name: String): String? = rawQuery.orEmpty().split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.let { URLDecoder.decode(it[1], "UTF-8") }
            ?.takeIf { it.isNotBlank() }
    }
}
