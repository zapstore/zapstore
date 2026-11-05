package dev.zapstore.app.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShelfOrderTest {
    @Test
    fun oneHourMatchesTheWebShelfAndTheNextHourDiffers() {
        val ids = listOf("c", "a", "b", "e", "d", "h", "f", "g")
        assertEquals(listOf("c", "e", "f", "b", "a", "g", "d", "h"), shelfOrder(ids, 494000) { it })
        assertEquals(listOf("a", "f", "g", "c", "d", "e", "b", "h"), shelfOrder(ids, 494001) { it })
        assertNotEquals(shelfOrder(ids, 494000) { it }, shelfOrder(ids, 494001) { it })
    }

    @Test
    fun theSameHourKeepsTheSameOrder() {
        val ids = listOf("m", "a", "z", "b")
        assertEquals(shelfOrder(ids, 10) { it }, shelfOrder(ids.reversed(), 10) { it })
    }
}
