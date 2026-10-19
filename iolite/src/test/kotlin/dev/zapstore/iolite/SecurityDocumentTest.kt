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
    fun jsonFileSuppliesFactsPermissionsAndKeywords() {
        val text = """
            {
              "facts": {"google_services": false, "tracking": true, "offline_capable": true},
              "notes": {"tracking": "Firebase Analytics sends usage."},
              "permissions": ["INTERNET", "READ_SMS"],
              "notice": "Usage leaves the phone.",
              "body": "Internet is used to fetch video data.",
              "keywords": ["nostr", "zaps"]
            }
        """.trimIndent()
        val doc = parseSecurityDocument(text)
        assertTrue(doc.combined)
        assertEquals(listOf("nostr", "zaps"), securityKeywords(text))
        assertEquals(listOf("INTERNET", "READ_SMS"), doc.permissions.split(',').map { it.trim() })
        assertEquals("Usage leaves the phone.\n---\nInternet is used to fetch video data.", doc.prose)
        val rows = parseFactRows(doc.csv).associateBy { it.key }
        assertEquals(false, rows["google_services"]?.yes)
        assertEquals(true, rows["tracking"]?.yes)
        assertEquals("Firebase Analytics sends usage.", rows["tracking"]?.reason)
        assertEquals(FACT_OFFLINE, factBits(doc.csv))
    }

    @Test
    fun jsonFileSuppliesSourceNotesAndFlags() {
        val text = """
            {
              "facts": {"source_available": true},
              "notes": {"source_available": "GPL-3.0", "read_sms": "READ_SMS: reads one-time codes."},
              "permissions": ["READ_SMS"],
              "flags": [{"id": "source_mismatch", "note": "APK differs from the tagged source."}, {"note": "no id"}]
            }
        """.trimIndent()
        val doc = parseSecurityDocument(text)
        assertEquals(listOf(AppFact(SOURCE_AVAILABLE, true, "GPL-3.0")), parseFactRows(doc.csv))
        assertEquals(FACT_SOURCE_AVAILABLE, factBits(doc.csv))
        assertEquals("READ_SMS: reads one-time codes.", doc.notes["read_sms"])
        assertEquals(listOf(SecurityFlag("source_mismatch", "APK differs from the tagged source.")), doc.flags)
    }

    @Test
    fun openSourceIsReadAsSourceAvailable() {
        val json = parseSecurityDocument("""{"facts": {"open_source": false}, "notes": {"open_source": "None"}}""")
        assertEquals(listOf(AppFact(SOURCE_AVAILABLE, false, "None")), parseFactRows(json.csv))
        assertEquals("None", json.notes[SOURCE_AVAILABLE])
        assertEquals(listOf(SOURCE_AVAILABLE), parseFactRows("\"open_source\",\"yes\",\"\"").map { it.key })
    }

    @Test
    fun bothSourcePhrasesSelectTheSameFact() {
        assertEquals(FACT_SOURCE_AVAILABLE, parseSearchQuery("source available maps").boost)
        assertEquals(FACT_SOURCE_AVAILABLE, parseSearchQuery("open source maps").boost)
        assertEquals("maps", parseSearchQuery("open source maps").residual)
    }

    @Test
    fun dashFileHasNoKeywords() {
        val text = """
            "offline_capable","yes",""
            ---
            INTERNET
            ---
            Works offline.
        """.trimIndent()
        assertEquals(emptyList<String>(), securityKeywords(text))
    }

    @Test
    fun proseNoteIsNotASecurityFile() {
        val text = "Contacts leave the phone.\n---\nThe address book is read."
        val doc = parseSecurityDocument(text)
        assertFalse(doc.combined)
        assertNull(securityFactsCsv(text))
        assertEquals("", doc.csv)
        assertEquals("", doc.prose)
    }
}
