package dev.zapstore.iolite

/** One account that follows a publisher. [name] is blank when the catalog could not resolve it. */
data class TrustFollower(val name: String, val pubkey: String)

/**
 * The `<app-id>/trust` member: a publisher's top followers by global PageRank, as of [at].
 * [leaked] means the publisher's own key is a known leak, and there is no follower list then.
 */
data class Trust(val at: Long, val leaked: Boolean, val followers: List<TrustFollower>) {
    companion object {
        private const val MAX_FOLLOWERS = 10

        /**
         * Reads one quoted CSV line: `<unix>,name,npub,name,npub,...` or `leaked,<unix>`. A leading
         * `trust` marker is accepted. Null when the line is neither. A pair whose npub is not valid is skipped.
         */
        fun parse(line: String): Trust? {
            val fields = csv(line.trim()) ?: return null
            if (fields.isEmpty()) return null
            if (fields[0] == "leaked") {
                val at = fields.getOrNull(1)?.toLongOrNull() ?: return null
                return Trust(at, leaked = true, followers = emptyList())
            }
            val start = if (fields[0] == "trust") 1 else 0
            val at = fields.getOrNull(start)?.toLongOrNull() ?: return null
            val followers = fields.drop(start + 1).chunked(2)
                .filter { it.size == 2 }
                .mapNotNull { (name, npub) -> npub.decodeNpub()?.let { TrustFollower(name.trim(), it) } }
                .distinctBy { it.pubkey }
                .take(MAX_FOLLOWERS)
            return Trust(at, leaked = false, followers = followers)
        }

        /** RFC 4180 for one record. Null when a quote is left open. */
        private fun csv(line: String): List<String>? {
            val fields = ArrayList<String>()
            val field = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { fields += field.toString(); field.clear() }
                    c == '\n' || c == '\r' -> if (quoted) field.append(c)
                    else -> field.append(c)
                }
                i++
            }
            if (quoted) return null
            fields += field.toString()
            return fields
        }
    }
}
