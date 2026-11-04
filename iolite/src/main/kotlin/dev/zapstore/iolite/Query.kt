package dev.zapstore.iolite

/**
 * A typed query: the relay filters that fetch it, the tables whose commits re-run it, and the SQLite read
 * that produces its items. Relay events flow through the store; the read never sees event JSON.
 */
class Query<T> internal constructor(
    val filters: List<Filter>,
    internal val tables: Set<Table>,
    /** Authors of the events asked for; with `QueryOptions.useAuthorRelays`, read from their NIP-65 outboxes. */
    internal val outboxAuthors: List<String>,
    /** Users the events are addressed to; with `QueryOptions.useAuthorRelays`, read from their NIP-65 inboxes. */
    internal val inboxAuthors: List<String>,
    /** Items shown by a `Remote` query before the relays answer. */
    internal val empty: T,
    internal val read: (IoliteStore, LocalSigner?) -> T,
) {
    companion object {
        fun profiles(pubkeys: Collection<String>): Query<Map<String, ProfileRecord>> {
            val distinct = pubkeys.distinct()
            return Query(
                filters = listOf(Filter(authors = distinct, kinds = listOf(Kinds.Profile))),
                tables = setOf(Table.Profiles),
                outboxAuthors = distinct,
                inboxAuthors = emptyList(),
                empty = emptyMap(),
                read = { store, _ -> store.profiles(distinct) },
            )
        }

        fun profile(pubkey: String): Query<ProfileRecord?> = profiles(listOf(pubkey)).map { it[pubkey] }

        fun mailboxes(pubkey: String): Query<Mailboxes?> = Query(
            filters = listOf(Filter(authors = listOf(pubkey), kinds = listOf(Kinds.RelayList), limit = 1)),
            tables = setOf(Table.Profiles),
            outboxAuthors = emptyList(),
            inboxAuthors = emptyList(),
            empty = null,
            read = { store, _ -> store.mailboxes(listOf(pubkey))[pubkey] },
        )

        fun stacks(author: String, limit: Int? = null): Query<List<StackRecord>> = Query(
            filters = listOf(Filter(authors = listOf(author), kinds = listOf(Kinds.AppStack), limit = limit)),
            tables = setOf(Table.Stacks),
            outboxAuthors = listOf(author),
            inboxAuthors = emptyList(),
            empty = emptyList(),
            read = { store, signer -> store.stacks(author, limit = limit, deviceSigner = signer) },
        )

        /** The stacks every catalog in [authors] ships, newest first. No keys means no stacks. */
        fun catalogStacks(authors: List<String>, limit: Int? = null): Query<List<StackRecord>> = Query(
            filters = if (authors.isEmpty()) emptyList() else listOf(Filter(authors = authors, kinds = listOf(Kinds.AppStack), limit = limit)),
            tables = setOf(Table.Stacks),
            outboxAuthors = authors,
            inboxAuthors = emptyList(),
            empty = emptyList(),
            read = { store, _ -> store.catalogStacks(authors, limit) },
        )

        /**
         * Public stacks from anyone: kind `30267` tagged for this device's [platforms].
         * Private lists (empty public part) and the device's own are skipped.
         */
        fun socialStacks(platforms: List<String>, limit: Int, until: Long? = null): Query<List<StackRecord>> = Query(
            filters = listOf(
                Filter(
                    kinds = listOf(Kinds.AppStack),
                    tags = mapOf("f" to platforms),
                    until = until,
                    limit = limit,
                ),
            ),
            tables = setOf(Table.Stacks),
            outboxAuthors = emptyList(),
            inboxAuthors = emptyList(),
            empty = emptyList(),
            read = { store, signer -> store.publicStacks(limit = limit, until = until, excluding = signer?.publicKey) },
        )

        fun stack(author: String, identifier: String): Query<StackRecord?> = Query(
            filters = listOf(
                Filter(authors = listOf(author), kinds = listOf(Kinds.AppStack), tags = mapOf("d" to listOf(identifier)), limit = 1),
            ),
            tables = setOf(Table.Stacks),
            outboxAuthors = listOf(author),
            inboxAuthors = emptyList(),
            empty = null,
            read = { store, signer -> store.stacks(author, identifier, limit = 1, deviceSigner = signer).firstOrNull() },
        )

        /** Public stacks whose `a` tags list one of [anchors], one app's coordinates. Newest first. */
        fun stackedBy(anchors: List<AppCoordinate>, limit: Int): Query<List<StackRecord>> = Query(
            filters = listOf(
                Filter(kinds = listOf(Kinds.AppStack), tags = mapOf("a" to anchors.map(AppCoordinate::toString)), limit = limit),
            ),
            tables = setOf(Table.Stacks),
            outboxAuthors = emptyList(),
            inboxAuthors = emptyList(),
            empty = emptyList(),
            read = { store, _ -> store.stacksListing(anchors, limit) },
        )

        /** The device key's private events, fetched once when a key is restored. */
        fun devicePrivate(devicePubkey: String): Query<String?> = Query(
            filters = listOf(
                Filter(
                    authors = listOf(devicePubkey),
                    kinds = listOf(Kinds.Preferences),
                    tags = mapOf("d" to listOf(DeviceEvents.STATE)),
                    limit = 1,
                ),
                Filter(
                    authors = listOf(devicePubkey),
                    kinds = listOf(Kinds.AppStack),
                    tags = mapOf("d" to listOf(DeviceEvents.BOOKMARKS, DeviceEvents.UPDATE_IGNORE)),
                ),
                // The catalogs this device uses, in order.
                Filter(authors = listOf(devicePubkey), kinds = listOf(Kinds.CatalogRelayList), limit = 1),
            ),
            tables = setOf(Table.Preferences, Table.Stacks, Table.Catalogs),
            outboxAuthors = emptyList(),
            inboxAuthors = emptyList(),
            empty = null,
            read = { store, signer -> store.devicePreference(DeviceEvents.STATE, signer) },
        )

        /** Receipts read by `p` for [recipients], per relay. A recipient may be paid for every app. */
        const val RECEIPT_LIMIT = 2000

        /**
         * Zap receipts for one app. Most Lightning servers drop the `a` tag from the receipt, so a
         * filter by `a` misses them: a second filter reads receipts by the pubkeys they are paid to
         * ([recipients]), and storing one takes the `a` tag of the zap request inside it. [lists]
         * are the pubkeys whose NIP-65 inboxes are asked as well, with `useAuthorRelays`.
         */
        fun zaps(
            anchors: List<AppCoordinate>,
            recipients: List<String> = emptyList(),
            lists: List<String> = emptyList(),
            limit: Int? = null,
        ): Query<List<ZapRecord>> = Query(
            filters = listOfNotNull(
                Filter(kinds = listOf(Kinds.Zap), tags = mapOf("a" to anchors.map(AppCoordinate::toString)), limit = limit),
                recipients.distinct().takeIf { it.isNotEmpty() }?.let {
                    Filter(kinds = listOf(Kinds.Zap), tags = mapOf("p" to it), limit = RECEIPT_LIMIT)
                },
            ),
            tables = setOf(Table.Zaps),
            outboxAuthors = emptyList(),
            inboxAuthors = lists.distinct(),
            empty = emptyList(),
            read = { store, _ -> store.zaps(anchors.first().appId, limit) },
        )

        /**
         * Comments on one app, as `specs/catalog.md` reads them: two filters in one REQ, `#A` with the
         * coordinates of the known pubkeys ([anchors]), and `#i` with the app id, which finds comments on
         * any other coordinate (another catalog's) and is why a read returns two lists to tell apart by
         * [CommentRecord.rootPubkey]. [lists] are the pubkeys whose NIP-65 inboxes are asked as well, with `useAuthorRelays`.
         */
        fun comments(
            anchors: List<AppCoordinate>,
            lists: List<String> = anchors.map(AppCoordinate::pubkey),
            limit: Int? = null,
        ): Query<List<CommentRecord>> = Query(
            filters = listOf(
                Filter(kinds = listOf(Kinds.Comment), tags = mapOf("A" to anchors.map(AppCoordinate::toString)), limit = limit),
                Filter(kinds = listOf(Kinds.Comment), tags = mapOf("i" to listOf(anchors.first().appId)), limit = limit),
            ),
            tables = setOf(Table.Comments),
            outboxAuthors = emptyList(),
            inboxAuthors = lists.distinct(),
            empty = emptyList(),
            read = { store, _ -> store.comments(anchors.first().appId) },
        )

        /**
         * Comments on one stack: kind `1111` whose root `A` is [root], the stack's coordinate. Zaps on a
         * stack are not read. [lists] are the pubkeys whose NIP-65 inboxes are asked as well, with
         * `useAuthorRelays`: the curator, whom a comment on the stack tells, and the catalog key.
         */
        fun stackComments(root: String, lists: List<String>, limit: Int? = null): Query<List<CommentRecord>> = Query(
            filters = listOf(Filter(kinds = listOf(Kinds.Comment), tags = mapOf("A" to listOf(root)), limit = limit)),
            tables = setOf(Table.Comments),
            outboxAuthors = emptyList(),
            inboxAuthors = lists.distinct(),
            empty = emptyList(),
            read = { store, _ -> store.commentsOnRoot(root, limit) },
        )

        /**
         * What one person did on apps: their comments (NIP-22 root kind `K` = app), read from their outbox
         * relays, and the receipts of their zaps (`#P`, the sender), whose app is the receipt's `a` tag or
         * the zap request's. Newest first.
         */
        fun authorActivity(author: String, limit: Int): Query<ActivityRecord> = Query(
            filters = listOf(
                Filter(authors = listOf(author), kinds = listOf(Kinds.Comment), tags = mapOf("K" to listOf(Kinds.App.toString())), limit = limit),
                Filter(kinds = listOf(Kinds.Zap), tags = mapOf("P" to listOf(author)), limit = limit),
            ),
            tables = setOf(Table.Comments, Table.Zaps),
            outboxAuthors = listOf(author),
            inboxAuthors = emptyList(),
            empty = ActivityRecord(),
            read = { store, _ -> ActivityRecord(store.commentsBy(author, limit), store.zapsBy(author, limit)) },
        )

        /**
         * Newest comments on any app (NIP-22 root kind `K` = app) and zaps, [limit] of each per relay.
         * Zaps are read two ways, as the web page's Activity does: by `a` for [zapAnchors], such as the
         * latest releases, and by `p` for [recipients] (the catalog key), since most Lightning servers
         * drop the `a` tag from the receipt and storing one takes the `a` tag of the zap request inside
         * it. [lists] are the pubkeys whose NIP-65 inboxes are asked as well, with `useAuthorRelays`.
         */
        fun activity(
            zapAnchors: List<AppCoordinate>,
            limit: Int,
            recipients: List<String> = emptyList(),
            lists: List<String> = emptyList(),
        ): Query<ActivityRecord> = Query(
            filters = listOfNotNull(
                Filter(kinds = listOf(Kinds.Comment), tags = mapOf("K" to listOf(Kinds.App.toString())), limit = limit),
                zapAnchors.takeIf { it.isNotEmpty() }?.let {
                    Filter(kinds = listOf(Kinds.Zap), tags = mapOf("a" to it.map(AppCoordinate::toString)), limit = limit)
                },
                recipients.distinct().takeIf { it.isNotEmpty() }?.let {
                    Filter(kinds = listOf(Kinds.Zap), tags = mapOf("p" to it), limit = limit)
                },
            ),
            tables = setOf(Table.Comments, Table.Zaps),
            outboxAuthors = emptyList(),
            inboxAuthors = lists.distinct(),
            empty = ActivityRecord(),
            read = { store, _ -> ActivityRecord(store.recentComments(limit), store.recentZaps(limit)) },
        )
    }

    fun <R> map(transform: (T) -> R): Query<R> =
        Query(filters, tables, outboxAuthors, inboxAuthors, transform(empty)) { store, signer -> transform(read(store, signer)) }
}
