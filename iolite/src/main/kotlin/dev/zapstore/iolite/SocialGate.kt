package dev.zapstore.iolite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The runtime switch behind a user turning social off. Reads, routing and ingest consult it, so the
 * choice takes effect for open sessions and for events already on the wire, not only for new queries.
 */
internal class SocialGate(initial: Boolean) {
    private val state = MutableStateFlow(initial)
    val enabled: StateFlow<Boolean> get() = state

    fun set(value: Boolean) {
        state.value = value
    }

    /** Whether [event] may be stored now. With social off, only what the catalog and the device itself signed. */
    fun admits(event: Event, devicePubkey: String?): Boolean =
        enabled.value || !isSocial(event, devicePubkey)

    companion object {
        /**
         * What social reads bring in: comments, zap receipts, other people's stacks, and the relay lists and
         * reports that route to them. The device's own events are never social, whatever their kind.
         */
        fun isSocial(event: Event, devicePubkey: String?): Boolean = when (event.kind) {
            Kinds.Comment, Kinds.Zap, Kinds.RelayList, Kinds.RelayDiscovery -> true
            Kinds.AppStack -> event.pubkey != devicePubkey
            else -> false
        }
    }
}
