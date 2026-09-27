package dev.zapstore.app.screens

import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.QueryError

data class CommentThread(
    val comment: CommentRecord,
    val replies: List<CommentThread>,
)

data class ThreadedComment(
    val comment: CommentRecord,
    val depth: Int,
)

enum class CommentsError {
    RelaysUnreachable,
    TimedOut,
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

fun List<CommentThread>.flattenComments(maxDepth: Int = 4): List<ThreadedComment> =
    flatMap { it.flatten(0, maxDepth) }

private fun CommentThread.flatten(depth: Int, maxDepth: Int): List<ThreadedComment> =
    listOf(ThreadedComment(comment, depth.coerceAtMost(maxDepth))) +
        replies.flatMap { it.flatten(depth + 1, maxDepth) }

internal fun QueryError?.toCommentsError(): CommentsError? = when (this) {
    is QueryError.Timeout -> CommentsError.TimedOut
    is QueryError.RelayFailure, is QueryError.IncompatibleDependency -> CommentsError.RelaysUnreachable
    else -> null
}
