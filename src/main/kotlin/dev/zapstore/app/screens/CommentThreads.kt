package dev.zapstore.app.screens

import dev.zapstore.iolite.AppCoordinate
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.QueryState
import dev.zapstore.iolite.RelayStatus
import dev.zapstore.iolite.ZapRecord

data class CommentThread(
    val comment: CommentRecord,
    val replies: List<CommentThread>,
)

enum class CommentsError {
    RelaysUnreachable,
    TimedOut,
}

/** An app's comments in two lists: the [main] one, and those [other] coordinates carry. */
data class CommentSplit(val main: List<CommentRecord>, val other: List<CommentRecord>)

/**
 * Splits the comments read for an app (by `#A` on its known coordinates and by `#i`) as `specs/catalog.md`
 * says: a comment whose root is one of [known] is in the main list, a comment on any other coordinate, such
 * as another catalog's, in the other. One with no root is in neither. A reply carries its thread's root, so a
 * thread stays whole.
 */
fun splitComments(items: List<CommentRecord>, known: List<AppCoordinate>): CommentSplit {
    val coordinates = known.mapTo(hashSetOf(), AppCoordinate::toString)
    val (main, other) = items.filter { it.root != null }.partition { it.root in coordinates }
    return CommentSplit(main, other)
}

/** Roots newest-first; replies stay under their parent, oldest first. Missing parents become roots. */
fun threadComments(items: List<CommentRecord>): List<CommentThread> {
    val ids = items.mapTo(hashSetOf()) { it.eventId }
    val children = items.groupBy { comment ->
        comment.parentEventId?.takeIf { it in ids }
    }
    fun build(comment: CommentRecord, trail: Set<String>): CommentThread {
        if (comment.eventId in trail) return CommentThread(comment, emptyList())
        val next = trail + comment.eventId
        val replies = children[comment.eventId].orEmpty()
            .sortedBy { it.createdAt }
            .map { build(it, next) }
        return CommentThread(comment, replies)
    }
    return children[null].orEmpty()
        .sortedByDescending { it.createdAt }
        .map { build(it, emptySet()) }
}

/** One row of the feed under an app: a comment at its depth, or a zap as an entry of its own. */
sealed interface FeedEntry {
    val key: String

    data class Comment(val comment: CommentRecord, val depth: Int) : FeedEntry {
        override val key: String get() = "comment:${comment.eventId}"
    }

    data class Zap(val zap: ZapRecord) : FeedEntry {
        override val key: String get() = "zap:${zap.eventId}"
    }
}

/**
 * Comments and zaps as one list, newest first, as the web page shows them: each root comment (with
 * its replies under it, oldest first) and each zap sorts by its own date. A zap is never threaded.
 */
fun feedEntries(threads: List<CommentThread>, zaps: List<ZapRecord>, maxDepth: Int = 4): List<FeedEntry> {
    val roots = threads.map { thread -> thread.comment.createdAt to thread.flattenToEntries(0, maxDepth) }
    val singles = zaps.map { zap -> zap.createdAt to listOf<FeedEntry>(FeedEntry.Zap(zap)) }
    return (roots + singles).sortedByDescending { it.first }.flatMap { it.second }
}

private fun CommentThread.flattenToEntries(depth: Int, maxDepth: Int): List<FeedEntry> =
    listOf<FeedEntry>(FeedEntry.Comment(comment, depth.coerceAtMost(maxDepth))) +
        replies.flatMap { it.flattenToEntries(depth + 1, maxDepth) }

/** Only when every relay failed: one failing relay among several is not an error. */
internal fun QueryState<*>?.toCommentsError(): CommentsError? {
    if (this == null || !unreachable) return null
    val silent = relays.values.all { (it as? RelayStatus.Failed)?.reason in TIMEOUT_REASONS }
    return if (silent) CommentsError.TimedOut else CommentsError.RelaysUnreachable
}

private val TIMEOUT_REASONS = setOf("timed out", "no response")

/** The first relay's failure, only when every relay failed. */
internal fun QueryState<*>.relayError(): String? =
    if (unreachable) relays.values.firstNotNullOfOrNull { (it as? RelayStatus.Failed)?.reason } else null
