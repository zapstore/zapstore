package dev.zapstore.iolite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun readsNamesAndNpubsWithQuotedCommas() {
        val trust = Trust.parse("1700000000,\"Alice, Inc.\",${a.toNpub()},,${b.toNpub()}\n")!!
        assertEquals(1_700_000_000L, trust.at)
        assertFalse(trust.leaked)
        assertEquals(listOf(TrustFollower("Alice, Inc.", a), TrustFollower("", b)), trust.followers)
    }

    @Test
    fun acceptsTheTrustMarkerTheWriterAddsAndEscapedQuotes() {
        val trust = Trust.parse("trust,1700000000,\"The \"\"Real\"\" Bob\",${a.toNpub()}")!!
        assertEquals(listOf(TrustFollower("The \"Real\" Bob", a)), trust.followers)
    }

    @Test
    fun aLeakedKeyHasNoFollowers() {
        val trust = Trust.parse("leaked,1700000000")!!
        assertTrue(trust.leaked)
        assertTrue(trust.followers.isEmpty())
    }

    @Test
    fun skipsBadNpubsAndDuplicatesAndRejectsGarbage() {
        val trust = Trust.parse("1700000000,x,npub1nope,y,${a.toNpub()},z,${a.toNpub()}")!!
        assertEquals(listOf(a), trust.followers.map { it.pubkey })
        assertNull(Trust.parse(""))
        assertNull(Trust.parse("soon,${a.toNpub()}"))
        assertNull(Trust.parse("1700000000,\"open,${a.toNpub()}"))
        assertNull(Trust.parse("leaked,never"))
    }

    @Test
    fun bundleMemberLandsOnItsAppAndABadOneIsSkipped() {
        val good = "1700000000,Al,${a.toNpub()}"
        val members = mapOf(
            "dev.good/trust" to TarMember("dev.good/trust", good.toByteArray()),
            "dev.bad/trust" to TarMember("dev.bad/trust", "nonsense".toByteArray()),
        )
        val skipped = ArrayList<String>()
        val result = CatalogArtifacts.fromMembers(members, maxIconBytes = 1_000, skipped = skipped)
        assertEquals(listOf("dev.good" to good), result.apps.map { it.appId to it.trust })
        assertEquals(1, skipped.size)
        assertTrue(CatalogArtifacts.allowedMember("dev.good/trust"))
    }
}
