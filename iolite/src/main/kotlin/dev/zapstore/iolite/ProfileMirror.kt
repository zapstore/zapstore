package dev.zapstore.iolite

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Copies the kind 0 profiles a read stored to one relay, so that relay keeps an up to date set of the
 * people in the feed. Only a profile that was new to this phone is sent, never one that came from
 * [relay], and each event at most once. A refusal is logged and not retried.
 */
internal class ProfileMirror(
    private val relay: RelayUrl,
    private val pool: RelayPool,
    private val timeout: Duration,
    private val log: IoliteLog,
) {
    /** Profile event id to whether [relay] itself sent it, from the relay callback until the batch flushes. */
    private val heardFrom = ConcurrentHashMap<String, Boolean>()

    private val sent: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > SENT_MEMORY
        }),
    )

    /** Notes where [event] came from. Call for every event a relay delivers, before it is queued for ingest. */
    fun heard(event: Event, from: RelayUrl) {
        if (event.kind == Kinds.Profile) heardFrom.merge(event.id, from == relay, Boolean::or)
    }

    /**
     * A flush finished: [batch] is everything it held and [written] what it stored. Sends the newest stored
     * profile of each pubkey when [enabled]; always forgets where the batch came from.
     */
    fun flushed(batch: List<Event>, written: List<Event>, enabled: Boolean) {
        val origins = batch.mapNotNull { event -> heardFrom.remove(event.id)?.let { event.id to it } }.toMap()
        if (!enabled) return
        written.asSequence()
            .filter { it.kind == Kinds.Profile && origins[it.id] == false }
            .groupBy(Event::pubkey)
            .values
            .map { profiles -> profiles.maxBy(Event::createdAt) }
            .filter { sent.add(it.id) }
            .forEach(::send)
    }

    private fun send(event: Event) {
        pool.publishOnce(event, relay, timeout) { accepted, reason ->
            if (!accepted) log.log("profile copy to ${relay.url} refused: ${reason.take(MAX_REASON)}", null)
        }
    }

    private companion object {
        const val SENT_MEMORY = 2_048
        const val MAX_REASON = 120
    }
}
