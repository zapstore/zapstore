package dev.zapstore.app.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class StackIdentifierTest {
    @Test
    fun namesBecomeLowercaseHyphenatedIdentifiers() {
        assertEquals("privacy-tools", stackIdentifier("  Privacy Tools! "))
        assertEquals("música-2026", stackIdentifier("Música / 2026"))
        assertEquals("stack", stackIdentifier("!!!"))
        assertEquals(48, stackIdentifier("a".repeat(80)).length)
    }
}
