package dev.zapstore.iolite

/**
 * A NIP-65 relay list. [outboxes] are where the user writes, so their events are read there;
 * [inboxes] are where the user reads, so events for them (replies, mentions) are sent there.
 * Both keep tag order, without duplicates once normalized.
 */
data class Mailboxes(
    val outboxes: List<RelayUrl>,
    val inboxes: List<RelayUrl>,
) {
    companion object {
        /** Unmarked `r` tags count as both; `write` and `read` mark one side. Invalid URLs are skipped. */
        fun parse(tags: List<List<String>>): Mailboxes {
            val outboxes = LinkedHashSet<RelayUrl>()
            val inboxes = LinkedHashSet<RelayUrl>()
            for (tag in tags) {
                if (tag.getOrNull(0) != "r") continue
                val url = tag.getOrNull(1)?.relayUrlOrNull() ?: continue
                when (tag.getOrNull(2)) {
                    null -> {
                        outboxes += url
                        inboxes += url
                    }
                    "write" -> outboxes += url
                    "read" -> inboxes += url
                }
            }
            return Mailboxes(outboxes.toList(), inboxes.toList())
        }
    }
}
