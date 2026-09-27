package dev.zapstore.iolite

/**
 * A typed query: the relay filters that fetch it, the tables whose commits re-run it, and the SQLite read
 * that produces its items. Relay events flow through the store; the read never sees event JSON.
 */
class Query<T> internal constructor(
    val filters: List<Filter>,
    internal val tables: Set<Table>,
    /** Authors whose NIP-65 read relays are added when `QueryOptions.useAuthorRelays` is set. */
    internal val authors: List<String>,
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
                authors = distinct,
                empty = emptyMap(),
                read = { store, _ -> store.profiles(distinct) },
            )
        }

        fun profile(pubkey: String): Query<ProfileRecord?> = profiles(listOf(pubkey)).map { it[pubkey] }

        fun relayList(pubkey: String): Query<Set<RelayUrl>?> = Query(
            filters = listOf(Filter(authors = listOf(pubkey), kinds = listOf(Kinds.RelayList), limit = 1)),
            tables = setOf(Table.Profiles),
            authors = emptyList(),
            empty = null,
            read = { store, _ -> store.readRelays(pubkey) },
        )

        fun stacks(author: String, limit: Int? = null): Query<List<StackRecord>> = Query(
            filters = listOf(Filter(authors = listOf(author), kinds = listOf(Kinds.AppStack), limit = limit)),
            tables = setOf(Table.Stacks),
            authors = listOf(author),
            empty = emptyList(),
            read = { store, signer -> store.stacks(author, limit = limit, deviceSigner = signer) },
        )

        fun stack(author: String, identifier: String): Query<StackRecord?> = Query(
            filters = listOf(
                Filter(authors = listOf(author), kinds = listOf(Kinds.AppStack), tags = mapOf("d" to listOf(identifier)), limit = 1),
            ),
            tables = setOf(Table.Stacks),
            authors = listOf(author),
            empty = null,
            read = { store, signer -> store.stacks(author, identifier, limit = 1, deviceSigner = signer).firstOrNull() },
        )

        fun zaps(app: AppCoordinate, limit: Int? = null): Query<List<ZapRecord>> = Query(
            filters = listOf(
                Filter(kinds = listOf(Kinds.Zap), tags = mapOf("a" to listOf(app.toString())), limit = limit),
            ),
            tables = setOf(Table.Zaps),
            authors = emptyList(),
            empty = emptyList(),
            read = { store, _ -> store.zaps(app.appId, limit) },
        )

        fun comments(app: AppCoordinate, limit: Int? = null): Query<List<CommentRecord>> = Query(
            filters = listOf(
                Filter(kinds = listOf(Kinds.Comment), tags = mapOf("A" to listOf(app.toString())), limit = limit),
            ),
            tables = setOf(Table.Comments),
            authors = listOf(app.pubkey),
            empty = emptyList(),
            read = { store, _ -> store.comments(app.appId) },
        )
    }

    fun <R> map(transform: (T) -> R): Query<R> =
        Query(filters, tables, authors, transform(empty)) { store, signer -> transform(read(store, signer)) }
}
