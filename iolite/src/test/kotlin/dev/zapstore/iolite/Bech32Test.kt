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

    @Test
    fun neventMatchesNak() {
        val id = "a".repeat(64)
        val author = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        assertEquals(
            "nevent1qqs242424242424242424242424242424242424242424242424242szyqalp33lewf5vdq847t6te0wvnags0gs0mu72kz8938tn24wlfze6a5j555",
            neventOf(id, author),
        )
        assertNull(neventOf("abc", author))
    }
}
