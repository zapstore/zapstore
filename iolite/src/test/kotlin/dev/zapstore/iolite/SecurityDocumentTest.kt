package dev.zapstore.iolite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityDocumentTest {
    @Test
    fun combinedFileSplitsFactsPermissionsAndProse() {
        val text = """
            "offline_capable","no",""
            "google_services","no",""
            ---
            INTERNET, READ_SMS
            ---
            Internet is used to fetch video data.
            ---
            The player asks for the network.
        """.trimIndent()
        val doc = parseSecurityDocument(text)
        assertTrue(doc.combined)
        assertEquals("\"offline_capable\",\"no\",\"\"\n\"google_services\",\"no\",\"\"", doc.csv)
        assertEquals("INTERNET, READ_SMS", doc.permissions)
        assertEquals("Internet is used to fetch video data.\n---\nThe player asks for the network.", doc.prose)
        assertEquals(doc.csv, securityFactsCsv(text))
    }

    @Test
    fun olderNoteStaysProse() {
        val text = "Contacts leave the phone.\n---\nThe address book is read."
        val doc = parseSecurityDocument(text)
        assertFalse(doc.combined)
        assertNull(securityFactsCsv(text))
        assertEquals(text, doc.prose)
    }
}
