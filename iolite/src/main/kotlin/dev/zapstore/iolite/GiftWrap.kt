package dev.zapstore.iolite

import org.json.JSONObject
import kotlin.random.asKotlinRandom

/** An unsigned event inside a seal (NIP-59). */
data class Rumor(val pubkey: String, val createdAt: Long, val kind: Int, val tags: List<List<String>>, val content: String)

/**
 * NIP-59 gift wrap: rumor → kind 13 seal signed by [sender] → kind 1059 wrap signed by a one-time
 * key. Both layers are NIP-44 to the recipient. Seal and wrap timestamps are moved up to two days
 * back so the relay cannot tell when the rumor was written.
 */
object GiftWrap {
    private const val TWO_DAYS = 2 * 24 * 60 * 60L

    /** [difficulty] is NIP-13 work on the wrap's id, which is all the relay sees. */
    suspend fun wrap(
        rumorKind: Int,
        rumorTags: List<List<String>>,
        content: String,
        recipient: String,
        sender: ByteArray,
        now: Long,
        difficulty: Int = 0,
        wrapKey: ByteArray = Crypto.randomBytes(32),
        tweak: () -> Long = { java.security.SecureRandom().asKotlinRandom().nextLong(TWO_DAYS) },
    ): Event {
        val recipientKey = Hex.decode(recipient)
        val senderPubkey = Hex.encode(Crypto.xOnlyPubkey(sender))
        val rumorId = Event.computeId(senderPubkey, now, rumorKind, rumorTags, content)
        val rumor = JSONObject()
            .put("id", rumorId)
            .put("pubkey", senderPubkey)
            .put("created_at", now)
            .put("kind", rumorKind)
            .put("tags", rumorTags.toJsonArray())
            .put("content", content)
        val seal = Event.sign(
            sender,
            now - tweak(),
            Kinds.Seal,
            emptyList(),
            Nip44.encrypt(rumor.toString(), Nip44.conversationKey(sender, recipientKey)),
        )
        val wrapContent = Nip44.encrypt(seal.toJson(), Nip44.conversationKey(wrapKey, recipientKey))
        val wrapPubkey = Hex.encode(Crypto.xOnlyPubkey(wrapKey))
        val createdAt = now - tweak()
        val tags = listOf(listOf("p", recipient))
        val mined = if (difficulty > 0) Pow.mine(wrapPubkey, createdAt, Kinds.GiftWrap, tags, wrapContent, difficulty) else tags
        return Event.sign(wrapKey, createdAt, Kinds.GiftWrap, mined, wrapContent)
    }

    /** Opens a wrap addressed to [recipientSecret]. Throws when a layer does not verify. */
    fun unwrap(wrap: Event, recipientSecret: ByteArray): Rumor {
        require(wrap.kind == Kinds.GiftWrap && wrap.verify()) { "not a valid gift wrap" }
        val seal = Event.parse(Nip44.decrypt(wrap.content, Nip44.conversationKey(recipientSecret, Hex.decode(wrap.pubkey))))
        require(seal.kind == Kinds.Seal && seal.verify()) { "not a valid seal" }
        val rumor = JSONObject(Nip44.decrypt(seal.content, Nip44.conversationKey(recipientSecret, Hex.decode(seal.pubkey))))
        require(rumor.getString("pubkey") == seal.pubkey) { "rumor author does not match the seal" }
        return Rumor(
            pubkey = rumor.getString("pubkey"),
            createdAt = rumor.getLong("created_at"),
            kind = rumor.getInt("kind"),
            tags = rumor.getJSONArray("tags").toStringLists(),
            content = rumor.optString("content"),
        )
    }
}
