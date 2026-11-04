package dev.zapstore.iolite

/**
 * A catalog as a person names it: its key, and relays that may hold its kind 10067. Typed as hex,
 * an `npub` (no hints), or an `nprofile` (its relay hints).
 */
data class CatalogReference(val pubkey: String, val hints: Set<RelayUrl> = emptySet()) {
    companion object {
        /** Null when [input] is none of those, so the caller can say the key is not valid. */
        fun parse(input: String): CatalogReference? {
            val value = input.trim().removePrefix("nostr:").trim()
            if (value.isEmpty()) return null
            val hex = value.lowercase()
            if (Hex.isHex(hex, 64)) return CatalogReference(hex)
            value.decodeNpub()?.let { return CatalogReference(it) }
            val (pubkey, relays) = value.decodeNprofileWithRelays() ?: return null
            return CatalogReference(pubkey, relays.mapNotNull { it.relayUrlOrNull() }.toCollection(LinkedHashSet()))
        }
    }
}
