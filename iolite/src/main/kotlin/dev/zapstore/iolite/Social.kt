package dev.zapstore.iolite

/** Tags for user content about an app, on its write anchor (`specs/catalog.md`, comments, zaps, and reports). */
object Social {
    /** NIP-56 report types offered for an app. */
    val reportTypes = listOf("malware", "spam", "impersonation", "illegal", "other")

    /**
     * A NIP-22 comment. A top-level comment's parent is the app; a reply's parent is [parent],
     * which must belong to the same app.
     */
    fun commentTags(anchor: AppCoordinate, parent: CommentRecord? = null, version: String? = null): List<List<String>> = buildList {
        val root = anchor.toString()
        add(listOf("A", root))
        add(listOf("K", Kinds.App.toString()))
        add(listOf("P", anchor.pubkey))
        if (parent == null) {
            add(listOf("a", root))
            add(listOf("k", Kinds.App.toString()))
            add(listOf("p", anchor.pubkey))
        } else {
            add(listOf("e", parent.eventId))
            add(listOf("k", Kinds.Comment.toString()))
            add(listOf("p", parent.pubkey))
        }
        add(listOf("i", anchor.appId))
        version?.takeIf { it.isNotBlank() }?.let { add(listOf("v", it)) }
    }

    /**
     * A NIP-22 comment on a stack: its root is the stack's `30267:<curator>:<identifier>` coordinate. A
     * top-level comment's parent is the stack; a reply's parent is [parent], on the same stack.
     */
    fun stackCommentTags(curator: String, identifier: String, parent: CommentRecord? = null): List<List<String>> = buildList {
        val root = "${Kinds.AppStack}:$curator:$identifier"
        add(listOf("A", root))
        add(listOf("K", Kinds.AppStack.toString()))
        add(listOf("P", curator))
        if (parent == null) {
            add(listOf("a", root))
            add(listOf("k", Kinds.AppStack.toString()))
            add(listOf("p", curator))
        } else {
            add(listOf("e", parent.eventId))
            add(listOf("k", Kinds.Comment.toString()))
            add(listOf("p", parent.pubkey))
        }
    }

    /**
     * A NIP-57 zap request for the app. It carries the `a` tag only: the recipient's Lightning
     * server writes the receipt, and the relay ignores `i` on it. [recipient] is the C1 owner,
     * or else the catalog key; the receipt lands on [relays], which must include where the app's zaps are read.
     */
    fun zapRequestTags(anchor: AppCoordinate, recipient: String, amountMsat: Long, relays: Collection<RelayUrl>): List<List<String>> {
        require(amountMsat > 0) { "amount must be positive" }
        require(relays.isNotEmpty()) { "a zap request needs relays" }
        return listOf(
            listOf("relays") + relays.map { it.url },
            listOf("amount", amountMsat.toString()),
            listOf("p", recipient),
            listOf("a", anchor.toString()),
        )
    }

    /** A NIP-56 report on the app's anchor; [type] is one of [reportTypes]. */
    fun reportTags(anchor: AppCoordinate, type: String): List<List<String>> {
        require(type in reportTypes) { "unknown report type $type" }
        return listOf(listOf("a", anchor.toString(), type), listOf("i", anchor.appId))
    }
}
