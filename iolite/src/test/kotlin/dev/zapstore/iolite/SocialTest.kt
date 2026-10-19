package dev.zapstore.iolite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialTest {
    private val catalog = "a".repeat(64)
    private val anchor = AppCoordinate(catalog, "dev.zap")

    @Test
    fun topLevelCommentAddressesTheAppWithItsId() {
        val tags = Social.commentTags(anchor)
        val root = "32267:$catalog:dev.zap"
        assertEquals(
            listOf(
                listOf("A", root), listOf("K", "32267"), listOf("P", catalog),
                listOf("a", root), listOf("k", "32267"), listOf("p", catalog),
                listOf("i", "dev.zap"),
            ),
            tags,
        )
    }

    @Test
    fun replyNamesItsParentComment() {
        val parent = CommentRecord("e".repeat(64), "b".repeat(64), 1, "hi", "dev.zap", null, null)
        val tags = Social.commentTags(anchor, parent)
        assertTrue(listOf("e", "e".repeat(64)) in tags)
        assertTrue(listOf("k", "1111") in tags)
        assertTrue(listOf("p", "b".repeat(64)) in tags)
        assertTrue(listOf("A", "32267:$catalog:dev.zap") in tags)
    }

    @Test
    fun reportCarriesItsTypeOnTheAddress() {
        assertEquals(
            listOf(listOf("a", "32267:$catalog:dev.zap", "malware"), listOf("i", "dev.zap")),
            Social.reportTags(anchor, "malware"),
        )
    }

    @Test
    fun zapRequestPaysTheOwnerOnTheCatalogCoordinate() {
        val owner = "c".repeat(64)
        val tags = Social.zapRequestTags(anchor, owner, 21_000, listOf("wss://relay.test".normalizeRelayUrl()))
        assertEquals(
            listOf(
                listOf("relays", "wss://relay.test"),
                listOf("amount", "21000"),
                listOf("p", owner),
                listOf("a", "32267:$catalog:dev.zap"),
                listOf("i", "dev.zap"),
            ),
            tags,
        )
    }
}
