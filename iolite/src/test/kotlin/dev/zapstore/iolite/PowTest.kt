package dev.zapstore.iolite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowTest {
    @Test
    fun leadingZeroBitsCountsNibbles() {
        assertEquals(0, Pow.leadingZeroBits("f0"))
        assertEquals(3, Pow.leadingZeroBits("1f"))
        assertEquals(4, Pow.leadingZeroBits("0f"))
        assertEquals(20, Pow.leadingZeroBits("00000f"))
        assertEquals(21, Pow.leadingZeroBits("000007"))
    }

    @Test
    fun requiredMatchesTheRelayRule() {
        assertEquals(20, Pow.required(Kinds.Preferences, ""))
        assertEquals(20, Pow.required(Kinds.AppStack, "ciphertext"))
        assertEquals(0, Pow.required(Kinds.AppStack, ""))
        assertEquals(0, Pow.required(Kinds.CatalogRelayList, ""))
    }

    @Test
    fun minedEventMeetsDifficultyAndVerifies() = runBlocking {
        val secret = Hex.decode("0000000000000000000000000000000000000000000000000000000000000003")
        val pubkey = Hex.encode(Crypto.xOnlyPubkey(secret))
        val tags = listOf(listOf("d", "zapstore-device-state"), listOf("nonce", "7", "20"))
        val content = "encrypted \"content\"\n/"
        val mined = Pow.mine(pubkey, 1_700_000_000, Kinds.Preferences, tags, content, 12)
        assertEquals(1, mined.count { it.first() == "nonce" })
        assertEquals("12", mined.last()[2])
        val event = Event.sign(secret, 1_700_000_000, Kinds.Preferences, mined, content)
        assertTrue(event.verify())
        assertTrue(Pow.meets(event, 12))
    }
}
