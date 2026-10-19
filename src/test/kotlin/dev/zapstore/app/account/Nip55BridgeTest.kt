package dev.zapstore.app.account

import dev.zapstore.iolite.toNpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Nip55BridgeTest {
    private val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"

    @Test
    fun acceptsHexAndNpub() {
        assertEquals(hex, Nip55Bridge.normalizePubkey(hex))
        assertEquals(hex, Nip55Bridge.normalizePubkey(hex.uppercase()))
        assertEquals(hex, Nip55Bridge.normalizePubkey(hex.toNpub()))
    }

    @Test
    fun rejectsOtherText() {
        assertNull(Nip55Bridge.normalizePubkey(""))
        assertNull(Nip55Bridge.normalizePubkey("npub1notreal"))
    }
}
