package dev.zapstore.iolite

/**
 * NIP-65 outbox routing as pure functions of what SQLite and relay health say right now. Callers
 * recompute on every change, so a plan widens as relay lists arrive and narrows as relays fail.
 */
internal object RelayRouting {
    /**
     * Which relays a read goes to, and what each is asked. [base] relays always get every filter.
     * Authors' events are read from their outboxes, chosen by [selectOptimalRelays], and each relay
     * is asked only about the authors picked for it; authors without a usable relay list use [base].
     * Events addressed to [inboxAuthors] (replies, mentions) are read from their inboxes with every
     * filter. A relay [skip] rejects is left out, unless that would leave no base relay at all.
     */
    fun read(
        filters: List<Filter>,
        base: Collection<RelayUrl>,
        outboxAuthors: List<String>,
        inboxAuthors: List<String>,
        mailboxes: Map<String, Mailboxes>,
        skip: (RelayUrl) -> Boolean,
        maxConnections: Int,
        maxRelaysPerUser: Int,
    ): Map<RelayUrl, List<Filter>> {
        val fallback = base.filterNot(skip).ifEmpty { base.toList() }
        val plan = LinkedHashMap<RelayUrl, List<Filter>>()
        fallback.forEach { plan[it] = filters }

        if (outboxAuthors.isNotEmpty()) {
            val users = outboxAuthors.distinct().map { RelayPointer(it, mailboxes[it]?.outboxes.orEmpty().filterNot(skip)) }
            val selected = selectOptimalRelays(users.withFallbackRelays(fallback), maxConnections, maxRelaysPerUser)
            for ((relay, pubkeys) in selected.groupByRelay()) {
                if (relay in plan) continue
                val wanted = pubkeys.toSet()
                val narrowed = filters.mapNotNull { filter ->
                    val authors = filter.authors ?: return@mapNotNull null
                    authors.filter { it in wanted }.takeIf { it.isNotEmpty() }?.let { filter.copy(authors = it) }
                }
                if (narrowed.isNotEmpty()) plan[relay] = narrowed
            }
        }

        for (pubkey in inboxAuthors.distinct()) {
            mailboxes[pubkey]?.inboxes.orEmpty().filterNot(skip).take(maxRelaysPerUser).forEach { plan[it] = filters }
        }
        return plan
    }

    /**
     * Where [event] is published: the relays the caller asked for, the author's outboxes (or
     * [fallback] without a relay list), and the inboxes of the `p`-tagged users. Relays [skip]
     * rejects are left out unless every destination is rejected.
     */
    fun write(
        event: Event,
        requested: Collection<RelayUrl>,
        mailboxes: Map<String, Mailboxes>,
        fallback: Collection<RelayUrl>,
        skip: (RelayUrl) -> Boolean,
        maxRelaysPerUser: Int,
    ): Set<RelayUrl> {
        val destinations = LinkedHashSet<RelayUrl>(requested)
        destinations += mailboxes[event.pubkey]?.outboxes?.takeIf { it.isNotEmpty() } ?: fallback
        for (pubkey in recipients(event)) {
            destinations += mailboxes[pubkey]?.inboxes.orEmpty().filterNot(skip).take(maxRelaysPerUser)
        }
        return destinations.filterNot(skip).toSet().ifEmpty { destinations }
    }

    /** Users the event is addressed to: its `p` tags, except the author. */
    fun recipients(event: Event): List<String> =
        event.tagValues("p").filter { it.length == 64 && it != event.pubkey }.distinct().take(MAX_RECIPIENTS)

    private const val MAX_RECIPIENTS = 20
}
