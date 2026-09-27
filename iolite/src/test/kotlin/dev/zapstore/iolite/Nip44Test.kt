package dev.zapstore.iolite

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip44Test {
    private val vectors = JSONObject(
        javaClass.classLoader!!.getResourceAsStream("nip44.vectors.json")!!.reader().readText(),
    ).getJSONObject("v2")

    @Test
    fun conversationKeysMatchOfficialVectors() {
        val cases = vectors.getJSONObject("valid").getJSONArray("get_conversation_key")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val key = Nip44.conversationKey(c.getString("sec1").hex(), c.getString("pub2").hex())
            assertEquals("case $i", c.getString("conversation_key"), Hex.encode(key))
        }
    }

    @Test
    fun messageKeysMatchOfficialVectors() {
        val block = vectors.getJSONObject("valid").getJSONObject("get_message_keys")
        val conversationKey = block.getString("conversation_key").hex()
        val keys = block.getJSONArray("keys")
        for (i in 0 until keys.length()) {
            val c = keys.getJSONObject(i)
            val (chachaKey, chachaNonce, hmacKey) = Nip44.messageKeys(conversationKey, c.getString("nonce").hex())
            assertEquals(c.getString("chacha_key"), Hex.encode(chachaKey))
            assertEquals(c.getString("chacha_nonce"), Hex.encode(chachaNonce))
            assertEquals(c.getString("hmac_key"), Hex.encode(hmacKey))
        }
    }

    @Test
    fun paddedLengthsMatchOfficialVectors() {
        val cases = vectors.getJSONObject("valid").getJSONArray("calc_padded_len")
        for (i in 0 until cases.length()) {
            val pair = cases.getJSONArray(i)
            assertEquals(pair.getInt(1), Nip44.paddedLength(pair.getInt(0)))
        }
    }

    @Test
    fun encryptDecryptMatchesOfficialVectors() {
        val cases = vectors.getJSONObject("valid").getJSONArray("encrypt_decrypt")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val sec1 = c.getString("sec1").hex()
            val sec2 = c.getString("sec2").hex()
            val pub2 = Crypto.xOnlyPubkey(sec2)
            val conversationKey = Nip44.conversationKey(sec1, pub2)
            assertEquals(c.getString("conversation_key"), Hex.encode(conversationKey))
            val payload = Nip44.encrypt(c.getString("plaintext"), conversationKey, c.getString("nonce").hex())
            assertEquals("encrypt $i", c.getString("payload"), payload)
            val pub1 = Crypto.xOnlyPubkey(sec1)
            val roundTripKey = Nip44.conversationKey(sec2, pub1)
            assertEquals(c.getString("plaintext"), Nip44.decrypt(payload, roundTripKey))
        }
    }

    @Test
    fun invalidConversationKeysAreRejected() {
        val cases = vectors.getJSONObject("invalid").getJSONArray("get_conversation_key")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val failed = runCatching {
                Nip44.conversationKey(c.getString("sec1").hex(), c.getString("pub2").hex())
            }.isFailure
            assertTrue("case $i should fail", failed)
        }
    }

    @Test
    fun invalidPayloadsAreRejected() {
        val cases = vectors.getJSONObject("invalid").getJSONArray("decrypt")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val failed = runCatching {
                Nip44.decrypt(c.getString("payload"), c.getString("conversation_key").hex())
            }.isFailure
            assertTrue("case $i ${c.optString("note")} should fail", failed)
        }
    }

    private fun String.hex(): ByteArray = Hex.decode(this)
}
