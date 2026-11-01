package dev.zapstore.app.screens

import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.QueryState
import dev.zapstore.iolite.RelayStatus
import dev.zapstore.iolite.RelayUrl
import dev.zapstore.iolite.ZapRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

        val flat = feedEntries(threads, emptyList()).filterIsInstance<FeedEntry.Comment>()
        assertEquals(
            listOf("root-new" to 0, "root-old" to 0, "reply-early" to 1, "reply-late" to 1),
            flat.map { it.comment.eventId to it.depth },
        )
    }

    @Test
    fun zapsSortAmongTheRootsAndNeverThread() {
        val old = comment("root-old", createdAt = 100)
        val new = comment("root-new", createdAt = 500)
        val reply = comment("reply", createdAt = 900, parent = "root-old")
        val between = zap("zap-between", createdAt = 300)
        val latest = zap("zap-latest", createdAt = 700)

        val feed = feedEntries(threadComments(listOf(old, new, reply)), listOf(between, latest))
        assertEquals(
            listOf("zap:zap-latest", "comment:root-new", "zap:zap-between", "comment:root-old", "comment:reply"),
            feed.map(FeedEntry::key),
        )
        assertEquals(1, (feed.last() as FeedEntry.Comment).depth)
    }

    @Test
    fun missingParentBecomesRoot() {
        val orphan = comment("orphan", createdAt = 50, parent = "missing")
        val threads = threadComments(listOf(orphan))
        assertEquals(listOf("orphan"), threads.map { it.comment.eventId })
        assertEquals(0, threads.single().replies.size)
    }

    @Test
    fun commentsErrorOnlyWhenEveryRelayFailed() {
        val one = RelayUrl("wss://relay.zapstore.dev")
        val two = RelayUrl("wss://nos.lol")
        fun state(vararg relays: Pair<RelayUrl, RelayStatus>) = QueryState(emptyList<CommentRecord>(), mapOf(*relays))
        assertEquals(CommentsError.RelaysUnreachable, state(one to RelayStatus.Failed("SOCKS"), two to RelayStatus.Failed("timed out")).toCommentsError())
        assertEquals(CommentsError.TimedOut, state(one to RelayStatus.Failed("no response"), two to RelayStatus.Failed("timed out")).toCommentsError())
        assertNull(state(one to RelayStatus.Failed("SOCKS"), two to RelayStatus.Answered).toCommentsError())
        assertNull(state().toCommentsError())
        assertNull((null as QueryState<*>?).toCommentsError())
    }

    private fun zap(id: String, createdAt: Long, note: String = "") = ZapRecord(
        eventId = id,
        pubkey = "z".repeat(64),
        amountSats = 21,
        createdAt = createdAt,
        appId = "dev.zap",
        note = note,
    )

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
