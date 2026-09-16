package dev.zapstore.app.catalogsync

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.isAddressable
import com.vitorpamplona.quartz.nip01Core.core.isReplaceable
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.store.FtsReindexProgress
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip40Expiration.isExpired
import dev.zapstore.app.Catalog
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

class CompactEventStore(
    private val database: ZapstoreDatabase,
) : IEventStore {
    override val relay: NormalizedRelayUrl? = null
    private val closed = AtomicBoolean(false)

    override suspend fun insert(event: Event) {
        database.transaction { insertUnlocked(it, event) }
    }

    override suspend fun transaction(body: IEventStore.ITransaction.() -> Unit) {
        database.transaction { connection ->
            body(object : IEventStore.ITransaction {
                override fun insert(event: Event) {
                    insertUnlocked(connection, event)
                }
            })
        }
    }

    override suspend fun <T : Event> query(filter: Filter): List<T> = query(listOf(filter))

    override suspend fun <T : Event> query(filters: List<Filter>): List<T> {
        if (filters.isEmpty()) return emptyList()
        val seen = linkedSetOf<String>()
        val matches = mutableListOf<T>()
        database.withConnection { connection ->
            filters.forEach { filter ->
                selectCandidates(connection, filter).forEach { event ->
                    @Suppress("UNCHECKED_CAST")
                    if (filter.match(event) && seen.add(event.id)) {
                        matches += event as T
                    }
                }
            }
        }
        return matches
    }

    override suspend fun <T : Event> query(filter: Filter, onEach: (T) -> Unit) {
        query<T>(filter).forEach(onEach)
    }

    override suspend fun <T : Event> query(filters: List<Filter>, onEach: (T) -> Unit) {
        query<T>(filters).forEach(onEach)
    }

    override suspend fun count(filter: Filter): Int = query<Event>(filter).size

    override suspend fun count(filters: List<Filter>): Int = query<Event>(filters).size

    override suspend fun delete(filter: Filter) {
        delete(listOf(filter))
    }

    override suspend fun delete(filters: List<Filter>) {
        database.transaction { connection ->
            filters.forEach { filter ->
                selectCandidates(connection, filter)
                    .filter(filter::match)
                    .forEach { deleteEvent(connection, it.id.hexToBytes()) }
            }
        }
    }

    override suspend fun deleteExpiredEvents() {
        database.transaction { connection ->
            selectAll(connection)
                .filter { it.isExpired() }
                .forEach { deleteEvent(connection, it.id.hexToBytes()) }
        }
    }

    override suspend fun reindexFullTextSearch() = Unit

    override suspend fun reindexFullTextSearch(resumeFrom: String?, batchSize: Int): FtsReindexProgress =
        FtsReindexProgress(cursor = null, processedThisBatch = 0, done = true)

    override fun close() {
        closed.set(true)
    }

    fun insertAll(events: List<Event>) {
        database.transaction { connection ->
            events.forEach { insertUnlocked(connection, it) }
        }
    }

    fun deleteByIds(ids: List<String>) {
        if (ids.isEmpty()) return
        database.transaction { connection ->
            ids.forEach { deleteEvent(connection, it.hexToBytes()) }
        }
    }

    fun applyDelta(events: List<Event>, deletedIds: List<String>, next: CatalogState) {
        database.transaction { connection ->
            deletedIds.forEach { deleteEvent(connection, it.hexToBytes()) }
            events.forEach { insertUnlocked(connection, it) }
            ZapstoreDatabase.upsertCatalogState(connection, next)
        }
    }

    private fun insertUnlocked(connection: SQLiteConnection, event: Event) {
        val id = event.id.hexToBytes()
        val pubkey = event.pubKey.hexToBytes()
        val dTag = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1).orEmpty()
        if (event.kind.isAddressable()) {
            deleteOlderAddressable(connection, event.kind, pubkey, dTag, event.createdAt, event.id)
        } else if (event.kind.isReplaceable()) {
            deleteOlderReplaceable(connection, event.kind, pubkey, event.createdAt, event.id)
        }
        connection.prepare(
            """
            INSERT OR REPLACE INTO events(id, pubkey, created_at, kind, d_tag, content, tags)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.bindBlob(1, id)
            statement.bindBlob(2, pubkey)
            statement.bindLong(3, event.createdAt)
            statement.bindLong(4, event.kind.toLong())
            statement.bindText(5, dTag)
            statement.bindText(6, event.content)
            statement.bindText(7, encodeTags(event.tags))
            statement.step()
        }
        connection.prepare("DELETE FROM event_tags WHERE event_id = ?").use { statement ->
            statement.bindBlob(1, id)
            statement.step()
        }
        event.tags.forEach { tag ->
            val key = tag.getOrNull(0) ?: return@forEach
            val value = tag.getOrNull(1) ?: return@forEach
            connection.prepare("INSERT OR IGNORE INTO event_tags(event_id, key, value) VALUES (?, ?, ?)").use { statement ->
                statement.bindBlob(1, id)
                statement.bindText(2, key)
                statement.bindText(3, value)
                statement.step()
            }
        }
        upsertDerived(connection, event, id, pubkey)
    }

    private fun upsertDerived(connection: SQLiteConnection, event: Event, id: ByteArray, pubkey: ByteArray) {
        when (event.kind) {
            Catalog.appKind -> {
                val appId = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: return
                val name = event.tags.firstOrNull { it.firstOrNull() == "name" }?.getOrNull(1) ?: appId
                connection.prepare(
                    """
                    INSERT INTO apps(app_id, event_id, pubkey, name, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(app_id) DO UPDATE SET
                        event_id = excluded.event_id,
                        pubkey = excluded.pubkey,
                        name = excluded.name,
                        created_at = excluded.created_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindText(1, appId)
                    statement.bindBlob(2, id)
                    statement.bindBlob(3, pubkey)
                    statement.bindText(4, name)
                    statement.bindLong(5, event.createdAt)
                    statement.step()
                }
            }
            Catalog.releaseKind -> {
                val appId = event.tags.firstOrNull { it.firstOrNull() == "i" }?.getOrNull(1)
                    ?: event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)?.substringBefore('@')
                    ?: return
                val version = event.tags.firstOrNull { it.firstOrNull() == "version" }?.getOrNull(1) ?: return
                val channel = event.tags.firstOrNull { it.firstOrNull() == "c" }?.getOrNull(1)
                connection.prepare(
                    """
                    INSERT INTO releases(app_id, version, event_id, channel, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(app_id, version) DO UPDATE SET
                        event_id = excluded.event_id,
                        channel = excluded.channel,
                        created_at = excluded.created_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindText(1, appId)
                    statement.bindText(2, version)
                    statement.bindBlob(3, id)
                    if (channel == null) statement.bindNull(4) else statement.bindText(4, channel)
                    statement.bindLong(5, event.createdAt)
                    statement.step()
                }
            }
            Catalog.assetKind -> {
                val appId = event.tags.firstOrNull { it.firstOrNull() == "i" }?.getOrNull(1) ?: return
                connection.prepare(
                    """
                    INSERT INTO assets(
                        event_id, app_id, version_code, version, mime, platform, variant,
                        certificate_hash, file_hash, url, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(event_id) DO UPDATE SET
                        app_id = excluded.app_id,
                        version_code = excluded.version_code,
                        version = excluded.version,
                        mime = excluded.mime,
                        platform = excluded.platform,
                        variant = excluded.variant,
                        certificate_hash = excluded.certificate_hash,
                        file_hash = excluded.file_hash,
                        url = excluded.url,
                        created_at = excluded.created_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindBlob(1, id)
                    statement.bindText(2, appId)
                    event.tags.firstOrNull { it.firstOrNull() == "version_code" }?.getOrNull(1)?.toLongOrNull()
                        ?.let { statement.bindLong(3, it) }
                        ?: statement.bindNull(3)
                    bindOptional(statement, 4, event.tag("version"))
                    bindOptional(statement, 5, event.tag("m"))
                    bindOptional(statement, 6, event.tags.filter { it.firstOrNull() == "f" }.mapNotNull { it.getOrNull(1) }.joinToString(",").ifEmpty { null })
                    bindOptional(statement, 7, event.tag("variant"))
                    bindOptional(statement, 8, event.tag("apk_certificate_hash"))
                    bindOptional(statement, 9, event.tag("x"))
                    bindOptional(statement, 10, event.tag("url"))
                    statement.bindLong(11, event.createdAt)
                    statement.step()
                }
                val version = event.tag("version") ?: return
                connection.prepare(
                    """
                    INSERT INTO releases(app_id, version, event_id, channel, created_at)
                    VALUES (?, ?, ?, NULL, ?)
                    ON CONFLICT(app_id, version) DO NOTHING
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindText(1, appId)
                    statement.bindText(2, version)
                    statement.bindBlob(3, id)
                    statement.bindLong(4, event.createdAt)
                    statement.step()
                }
            }
        }
    }

    private fun deleteOlderAddressable(
        connection: SQLiteConnection,
        kind: Int,
        pubkey: ByteArray,
        dTag: String,
        createdAt: Long,
        idHex: String,
    ) {
        connection.prepare(
            """
            SELECT id, created_at FROM events
            WHERE kind = ? AND pubkey = ? AND d_tag = ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, kind.toLong())
            statement.bindBlob(2, pubkey)
            statement.bindText(3, dTag)
            val obsolete = mutableListOf<ByteArray>()
            while (statement.step()) {
                val existingId = statement.getBlob(0)
                val existingCreated = statement.getLong(1)
                val existingHex = existingId.toHex()
                if (existingCreated < createdAt || existingCreated == createdAt && existingHex > idHex) {
                    obsolete += existingId
                }
            }
            obsolete.forEach { deleteEvent(connection, it) }
        }
    }

    private fun deleteOlderReplaceable(
        connection: SQLiteConnection,
        kind: Int,
        pubkey: ByteArray,
        createdAt: Long,
        idHex: String,
    ) {
        connection.prepare("SELECT id, created_at FROM events WHERE kind = ? AND pubkey = ?").use { statement ->
            statement.bindLong(1, kind.toLong())
            statement.bindBlob(2, pubkey)
            val obsolete = mutableListOf<ByteArray>()
            while (statement.step()) {
                val existingId = statement.getBlob(0)
                val existingCreated = statement.getLong(1)
                val existingHex = existingId.toHex()
                if (existingCreated < createdAt || existingCreated == createdAt && existingHex > idHex) {
                    obsolete += existingId
                }
            }
            obsolete.forEach { deleteEvent(connection, it) }
        }
    }

    private fun deleteEvent(connection: SQLiteConnection, id: ByteArray) {
        connection.prepare("DELETE FROM event_tags WHERE event_id = ?").use {
            it.bindBlob(1, id)
            it.step()
        }
        connection.prepare("DELETE FROM apps WHERE event_id = ?").use {
            it.bindBlob(1, id)
            it.step()
        }
        connection.prepare("DELETE FROM releases WHERE event_id = ?").use {
            it.bindBlob(1, id)
            it.step()
        }
        connection.prepare("DELETE FROM assets WHERE event_id = ?").use {
            it.bindBlob(1, id)
            it.step()
        }
        connection.prepare("DELETE FROM events WHERE id = ?").use {
            it.bindBlob(1, id)
            it.step()
        }
    }

    private fun selectCandidates(connection: SQLiteConnection, filter: Filter): List<Event> {
        val clauses = mutableListOf<String>()
        val binders = mutableListOf<(SQLiteStatement) -> Unit>()
        var index = 1
        filter.ids?.takeIf { it.isNotEmpty() }?.let { ids ->
            clauses += "id IN (${ids.joinToString(",") { "?" }})"
            ids.forEach { id ->
                val current = index++
                binders += { it.bindBlob(current, id.hexToBytes()) }
            }
        }
        filter.authors?.takeIf { it.isNotEmpty() }?.let { authors ->
            clauses += "pubkey IN (${authors.joinToString(",") { "?" }})"
            authors.forEach { author ->
                val current = index++
                binders += { it.bindBlob(current, author.hexToBytes()) }
            }
        }
        filter.kinds?.takeIf { it.isNotEmpty() }?.let { kinds ->
            clauses += "kind IN (${kinds.joinToString(",") { "?" }})"
            kinds.forEach { kind ->
                val current = index++
                binders += { it.bindLong(current, kind.toLong()) }
            }
        }
        filter.since?.let { since ->
            clauses += "created_at >= ?"
            val current = index++
            binders += { it.bindLong(current, since) }
        }
        filter.until?.let { until ->
            clauses += "created_at <= ?"
            val current = index++
            binders += { it.bindLong(current, until) }
        }
        filter.tags?.forEach { (key, values) ->
            if (values.isEmpty()) return@forEach
            val placeholders = values.joinToString(",") { "?" }
            clauses += "id IN (SELECT event_id FROM event_tags WHERE key = ? AND value IN ($placeholders))"
            val keyIndex = index++
            binders += { it.bindText(keyIndex, key) }
            values.forEach { value ->
                val current = index++
                binders += { it.bindText(current, value) }
            }
        }
        filter.search?.takeIf { it.isNotBlank() }?.let { search ->
            clauses += "(content LIKE ? OR id IN (SELECT event_id FROM event_tags WHERE key IN ('name','summary') AND value LIKE ?))"
            val pattern = "%$search%"
            val first = index++
            val second = index++
            binders += {
                it.bindText(first, pattern)
                it.bindText(second, pattern)
            }
        }
        val sql = buildString {
            append("SELECT id, pubkey, created_at, kind, content, tags FROM events")
            if (clauses.isNotEmpty()) append(" WHERE ").append(clauses.joinToString(" AND "))
            append(" ORDER BY created_at DESC, id ASC")
            filter.limit?.let { append(" LIMIT ").append(it) }
        }
        return connection.prepare(sql).use { statement ->
            binders.forEach { it(statement) }
            readEvents(statement)
        }
    }

    private fun selectAll(connection: SQLiteConnection): List<Event> =
        connection.prepare("SELECT id, pubkey, created_at, kind, content, tags FROM events").use(::readEvents)

    private fun readEvents(statement: SQLiteStatement): List<Event> {
        val events = mutableListOf<Event>()
        while (statement.step()) {
            events += Event(
                id = statement.getBlob(0).toHex(),
                pubKey = statement.getBlob(1).toHex(),
                createdAt = statement.getLong(2),
                kind = statement.getInt(3),
                tags = decodeTags(statement.getText(5)),
                content = statement.getText(4),
                sig = "",
            )
        }
        return events
    }

    private fun Event.tag(name: String): String? =
        tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

    private fun bindOptional(statement: SQLiteStatement, index: Int, value: String?) {
        if (value == null) statement.bindNull(index) else statement.bindText(index, value)
    }

    companion object {
        fun encodeTags(tags: Array<Array<String>>): String {
            val array = JSONArray()
            tags.forEach { tag ->
                val row = JSONArray()
                tag.forEach(row::put)
                array.put(row)
            }
            return array.toString()
        }

        fun decodeTags(raw: String): Array<Array<String>> {
            val array = JSONArray(raw)
            return Array(array.length()) { index ->
                val row = array.getJSONArray(index)
                Array(row.length()) { row.getString(it) }
            }
        }
    }
}
