package dev.zapstore.iolite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Bech32Test {
    @Test
    fun nsecRoundTripsAndMatchesNak() {
        val hex = "974daf40683f692a8f328c03f6c1bc1927a0b6eb4e328c097cbb7262194caaba"
        assertEquals("nsec1jax67srg8a5j4rej3spldsduryn6pdhtfcegcztuhdexyx2v42aq0t4sv6", hex.toNsec())
        assertEquals(hex, "nsec1jax67srg8a5j4rej3spldsduryn6pdhtfcegcztuhdexyx2v42aq0t4sv6".decodeNsec())
        assertNull("nsec1jax67srg8a5j4rej3spldsduryn6pdhtfcegcztuhdexyx2v42aq0t4sv6".decodeNpub())
        assertNull(hex.toNpub().decodeNsec())
    }
}
