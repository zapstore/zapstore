package dev.zapstore.app.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionNoteTest {
    @Test
    fun leadingIdIsDropped() {
        assertEquals("Used for P2P share QR scanner", "CAMERA used for P2P share QR scanner".withoutLeadingId("CAMERA"))
        assertEquals("Has no shown use", "QUERY_ALL_PACKAGES has no shown use".withoutLeadingId(null))
    }

    @Test
    fun plainNotesAreKept() {
        assertEquals("GPS for maps", "GPS for maps".withoutLeadingId(null))
        assertEquals("Reads the camera roll", "Reads the camera roll".withoutLeadingId("CAMERA"))
    }
}
