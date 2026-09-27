package dev.zapstore.iolite

import org.json.JSONArray

object Nip42 {
    suspend fun auth(signer: Signer, relay: RelayUrl, challenge: String, createdAt: Long): Event {
        require(challenge.isNotEmpty()) { "NIP-42 challenge must not be empty" }
        val event = signer.sign(
            createdAt = createdAt,
            kind = Kinds.Auth,
            tags = listOf(
                listOf("relay", relay.url),
                listOf("challenge", challenge),
            ),
            content = "",
        )
        require(event.verify()) { "NIP-42 AUTH signature is invalid" }
        return event
    }

    fun wire(event: Event): String = JSONArray().put("AUTH").put(event.toJsonObject()).toString()
}
