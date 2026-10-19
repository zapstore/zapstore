package dev.zapstore.iolite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GiftWrapTest {
    private val recipientSecret = Hex.decode("0000000000000000000000000000000000000000000000000000000000000003")
    private val recipient = Hex.encode(Crypto.xOnlyPubkey(recipientSecret))
    private val sender = Hex.decode("0000000000000000000000000000000000000000000000000000000000000005")

    @Test
    fun recipientOpensTheRumor() = runBlocking {
        val tags = listOf(listOf("p", recipient), listOf("subject", "report"))
        val wrap = GiftWrap.wrap(Kinds.ChatMessage, tags, "it broke", recipient, sender, now = 1_800_000_000, difficulty = 8)

        assertEquals(Kinds.GiftWrap, wrap.kind)
        assertTrue(wrap.verify())
        assertTrue(Pow.meets(wrap, 8))
        assertEquals(listOf(recipient), wrap.tags.filter { it[0] == "p" }.map { it[1] })
        assertNotEquals(Hex.encode(Crypto.xOnlyPubkey(sender)), wrap.pubkey)
        assertTrue(wrap.createdAt <= 1_800_000_000)

        val rumor = GiftWrap.unwrap(wrap, recipientSecret)
        assertEquals(Kinds.ChatMessage, rumor.kind)
        assertEquals("it broke", rumor.content)
        assertEquals(tags, rumor.tags)
        assertEquals(1_800_000_000, rumor.createdAt)
        assertEquals(Hex.encode(Crypto.xOnlyPubkey(sender)), rumor.pubkey)
    }

    @Test
    fun anotherKeyCannotOpenIt() = runBlocking {
        val wrap = GiftWrap.wrap(Kinds.ChatMessage, emptyList(), "secret", recipient, sender, now = 1_800_000_000)
        assertThrows(Exception::class.java) { GiftWrap.unwrap(wrap, sender) }
        Unit
    }
}
