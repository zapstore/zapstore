package dev.zapstore.iolite

import org.json.JSONObject

interface Signer {
    val publicKey: String
    suspend fun sign(createdAt: Long, kind: Int, tags: List<List<String>>, content: String): Event
}

class LocalSigner(secretKey: ByteArray) : Signer {
    private val secret = secretKey.copyOf()
    private val selfConversationKey: ByteArray =
        Nip44.conversationKey(secret, Crypto.xOnlyPubkey(secret))

    override val publicKey: String = Hex.encode(Crypto.xOnlyPubkey(secret))

    override suspend fun sign(
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): Event = Event.sign(secret, createdAt, kind, tags, content)

    fun encryptToSelf(plaintext: String): String = Nip44.encrypt(plaintext, selfConversationKey)

    fun decryptFromSelf(payload: String): String = Nip44.decrypt(payload, selfConversationKey)
}

/**
 * NIP-55 signer. Android supplies [requestSignedEvent] by talking to a signer
 * app such as Amber. Iolite never holds the user's nsec for user content.
 */
class Nip55Signer(
    override val publicKey: String,
    private val requestSignedEvent: suspend (unsignedJson: String) -> String,
) : Signer {
    override suspend fun sign(
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): Event {
        val unsigned = JSONObject()
            .put("created_at", createdAt)
            .put("kind", kind)
            .put("tags", tags.toJsonArray())
            .put("content", content)
            .put("pubkey", publicKey)
        val signed = Event.parse(requestSignedEvent(unsigned.toString()))
        require(signed.verify()) { "NIP-55 returned an invalid signature" }
        require(signed.pubkey == publicKey) { "NIP-55 pubkey does not match the requested signer" }
        require(signed.kind == kind && signed.createdAt == createdAt && signed.content == content) {
            "NIP-55 returned a different event template"
        }
        return signed
    }
}
