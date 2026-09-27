package dev.zapstore.app.screens

import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.QueryError
import dev.zapstore.iolite.RelayUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class CommentThreadTest {
    @Test
    fun repliesNestUnderParentNewestRootsFirst() {
        val olderRoot = comment("root-old", createdAt = 100, content = "older")
        val newerRoot = comment("root-new", createdAt = 300, content = "newer")
        val lateReply = comment("reply-late", createdAt = 400, content = "later", parent = "root-old")
        val earlyReply = comment("reply-early", createdAt = 200, content = "earlier", parent = "root-old")

        val threads = threadComments(listOf(lateReply, newerRoot, earlyReply, olderRoot))
        assertEquals(listOf("root-new", "root-old"), threads.map { it.comment.eventId })
        assertEquals(listOf("reply-early", "reply-late"), threads[1].replies.map { it.comment.eventId })

        val flat = threads.flattenComments()
        assertEquals(
            listOf("root-new" to 0, "root-old" to 0, "reply-early" to 1, "reply-late" to 1),
            flat.map { it.comment.eventId to it.depth },
        )
    }

    @Test
    fun missingParentBecomesRoot() {
        val orphan = comment("orphan", createdAt = 50, parent = "missing")
        val threads = threadComments(listOf(orphan))
        assertEquals(listOf("orphan"), threads.map { it.comment.eventId })
        assertEquals(0, threads.single().replies.size)
    }

    @Test
    fun queryErrorsMapToActionableCommentsErrors() {
        val relay = "wss://relay.zapstore.dev".let { RelayUrl(it) }
        assertEquals(CommentsError.RelaysUnreachable, QueryError.RelayFailure(relay, "SOCKS").toCommentsError())
        assertEquals(CommentsError.TimedOut, QueryError.Timeout(5.seconds, "timed out").toCommentsError())
        assertNull(QueryError.VerificationRejected(relay, "e", "bad").toCommentsError())
        assertNull((null as QueryError?).toCommentsError())
    }

    private fun comment(
        id: String,
        createdAt: Long,
        content: String = id,
        parent: String? = null,
    ) = CommentRecord(
        eventId = id,
        pubkey = "p".repeat(64),
        createdAt = createdAt,
        content = content,
        appId = "dev.zap",
        stack = null,
        parentEventId = parent,
    )
}
