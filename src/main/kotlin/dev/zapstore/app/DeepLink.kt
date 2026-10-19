package dev.zapstore.app

import dev.zapstore.iolite.Kinds
import dev.zapstore.iolite.decodeNaddr
import dev.zapstore.iolite.decodeNprofile
import dev.zapstore.iolite.decodeNpub
import java.net.URI
import java.net.URLDecoder

/** Where an incoming link opens. */
sealed interface DeepLink {
    data class App(val appId: String) : DeepLink
    data class Stack(val pubkey: String, val identifier: String) : DeepLink
    data class Profile(val pubkey: String) : DeepLink
    /** The My apps tab, from the updates notification. */
    data object MyApps : DeepLink

    companion object {
        private val hosts = setOf("zapstore.dev", "www.zapstore.dev")

        /**
         * `https://zapstore.dev/apps/<app-id|naddr>`, `/stacks/<naddr>` or `/stacks/<npub>/<id>`,
         * `/profile/<npub|nprofile|hex>`, and `market://details?id=<app-id>`. Anything else is null.
         */
        fun parse(link: String): DeepLink? = runCatching {
            val uri = URI(link.trim())
            when (uri.scheme?.lowercase()) {
                "market" -> uri.query(name = "id")?.takeIf { uri.host == "details" }?.let(::App)
                "https" -> if (uri.host?.lowercase() in hosts) web(uri.rawPath.orEmpty()) else null
                else -> null
            }
        }.getOrNull()

        private fun web(path: String): DeepLink? {
            val parts = path.split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, Charsets.UTF_8) }
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
            ?.let { URLDecoder.decode(it[1], Charsets.UTF_8) }
            ?.takeIf { it.isNotBlank() }
    }
}
