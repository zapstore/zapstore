package dev.zapstore.iolite

/** One NIP-66 kind 30166 report: a monitor saw [relay] at [createdAt]. */
data class RelayReport(
    val relay: RelayUrl,
    val monitor: String,
    val createdAt: Long,
    val requiresPayment: Boolean,
) {
    companion object {
        /** Null unless [event] is a kind 30166 whose `d` tag is a WebSocket URL. */
        fun parse(event: Event): RelayReport? {
            if (event.kind != Kinds.RelayDiscovery) return null
            val relay = event.tagValue("d")?.relayUrlOrNull() ?: return null
            return RelayReport(
                relay = relay,
                monitor = event.pubkey,
                createdAt = event.createdAt,
                requiresPayment = event.tags.any { it.getOrNull(0) == "R" && it.getOrNull(1) == "payment" },
            )
        }
    }
}

/** What the trusted monitors last said about one relay. */
data class RelayStanding(
    /** Newest report from any trusted monitor, epoch seconds. */
    val lastSeen: Long,
    val requiresPayment: Boolean,
) {
    /**
     * Monitors report relays they can reach, so one reported [maxAgeSeconds] before [newestSeconds],
     * the newest report on hand, is down. Measured against reports rather than the clock, so a phone
     * that has not fetched reports for days does not skip every monitored relay.
     */
    fun excludes(newestSeconds: Long, maxAgeSeconds: Long): Boolean =
        requiresPayment || newestSeconds - lastSeen > maxAgeSeconds

    companion object {
        fun of(reports: List<RelayReport>): RelayStanding? {
            if (reports.isEmpty()) return null
            return RelayStanding(
                lastSeen = reports.maxOf { it.createdAt },
                requiresPayment = reports.any { it.requiresPayment },
            )
        }
    }
}
