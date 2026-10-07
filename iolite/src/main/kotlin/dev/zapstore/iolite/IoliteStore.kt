package dev.zapstore.iolite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject

/** Selects visible catalog listings. All fields are optional and combine with AND. */
data class AppFilter(
    val appIds: Collection<String>? = null,
    /** Matches the proof owner or the app event signer. */
    val author: String? = null,
    /**
     * Case-insensitive exact or prefix match on the name or app ID.
     * With [queryVector], listings are also ranked by vector similarity.
     */
    val search: String? = null,
    /** leaf-ir-v1 query vector, [VECTOR_DIMS] int8 bytes. Ignored unless [search] is set. */
    val queryVector: ByteArray? = null,
    val limit: Int? = null,
)

/** One open transaction. Row writers record which tables they touch so observers can be notified precisely. */
class Tx internal constructor(val db: SQLiteConnection) {
    internal val touched: EnumSet<Table> = EnumSet.noneOf(Table::class.java)

    fun touch(vararg tables: Table) {
        touched += tables
    }
}

/**
 * The SQLite file. Every write is one transaction; every commit notifies listeners of the touched tables.
 * Reads return typed records. No event JSON is rehydrated for reads.
 */
class IoliteStore internal constructor(
    path: String,
    private val iconsDir: File = File(File(path).parentFile, "icons"),
    private val avatarsDir: File = File(File(path).parentFile, "avatars"),
    openConnection: (String) -> SQLiteConnection = ::openBundledOrJdbc,
) : AutoCloseable {
    private class Listener(val tables: Set<Table>, val notify: () -> Unit)

    private val lock = ReentrantLock()
    private val connection: SQLiteConnection = open(path, openConnection)
    private val listeners = CopyOnWriteArrayList<Listener>()

    // --- change notification -----------------------------------------------------------------------

    /** Emits once on subscription and after every commit that touched any of [tables]. Conflated. */
    fun changes(tables: Set<Table>): Flow<Unit> = callbackFlow {
        val listener = Listener(tables) { trySend(Unit) }
        listeners += listener
        trySend(Unit)
        awaitClose { listeners -= listener }
    }.buffer(Channel.CONFLATED)

    fun <T> read(block: (SQLiteConnection) -> T): T = lock.withLock { block(connection) }

    fun write(block: (Tx) -> Unit) {
        val touched = lock.withLock {
            val tx = Tx(connection)
            connection.execSQL("BEGIN IMMEDIATE")
            try {
                block(tx)
                connection.execSQL("COMMIT")
            } catch (failure: Throwable) {
                runCatching { connection.execSQL("ROLLBACK") }
                throw failure
            }
            tx.touched
        }
        if (touched.isEmpty()) return
        listeners.forEach { listener ->
            if (listener.tables.any(touched::contains)) runCatching(listener.notify)
        }
    }

    override fun close() {
        lock.withLock {
            checkpointWalLocked()
            connection.close()
        }
    }

    /** Folds the WAL into the main file so a large import does not leave a second copy on disk. */
    private fun checkpointWal() {
        lock.withLock { checkpointWalLocked() }
    }

    private fun checkpointWalLocked() {
        runCatching {
            connection.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { it.step() }
        }
    }

    // --- ingest ------------------------------------------------------------------------------------

    /** Relay path: verified non-catalog events, one transaction. Returns how many rows changed. */
    internal fun ingest(events: List<Event>, context: IngestContext): Int {
        val admissible = events.filter { it.kind !in ListingRows.KINDS && it.verify() }
        if (admissible.isEmpty()) return 0
        var written = 0
        write { tx -> admissible.forEach { if (EventRows.persist(tx, it, context)) written++ } }
        return written
    }

    /**
     * Catalog path: one epoch's verified events and deletes, one transaction. When [replaceAll] is set,
     * listings absent from [events] are removed. Returns the app IDs whose listings were deleted.
     */
    internal fun importCatalog(
        catalog: CatalogRecord,
        events: List<Event>,
        deletes: List<CatalogDelete>,
        replaceAll: Boolean,
        toEpoch: Long,
        device: DeviceProfile,
        now: Long,
        artifacts: CatalogArtifact = CatalogArtifact.EMPTY,
        stackPubkey: String = CatalogStackPubkey,
    ): Set<String> {
        val removed = LinkedHashSet<String>()
        write { tx ->
            for (event in events) {
                if (event.kind != Kinds.AppStack) continue
                if (event.pubkey != stackPubkey) {
                    throw CatalogImportException("stack is not signed by the curator")
                }
                if (event.dTag().isNullOrBlank()) throw CatalogImportException("stack missing d")
            }
            for (delete in deletes) {
                if (delete is CatalogDelete.Coordinate && delete.kind == Kinds.AppStack && delete.pubkey != stackPubkey) {
                    throw CatalogImportException("stack is not signed by the curator")
                }
                ListingRows.delete(tx, catalog, delete)
                if (delete is CatalogDelete.Listing) removed += delete.appId
            }
            val listings = events.filter { ListingRows.isListingKind(it.kind) }.groupBy(ListingRows::listingAppId)
            for ((appId, listing) in listings) {
                if (appId == null) continue
                ListingRows.persistListing(tx, catalog, appId, listing, device, now)
            }
            events.filter { it.kind == Kinds.IdentityProof }.forEach { ListingRows.persistProof(tx, catalog, it) }
            val keptStacks = LinkedHashSet<String>()
            val context = IngestContext(device, null)
            for (event in events) {
                if (event.kind == Kinds.Profile) EventRows.persist(tx, event, context)
            }
            for (event in events) {
                if (event.kind != Kinds.AppStack || !EventRows.stackMatchesDevice(event, device)) continue
                val identifier = event.dTag() ?: continue
                keptStacks += identifier
                EventRows.persist(tx, event, context)
            }
            if (replaceAll) EventRows.retainCatalogStacks(tx, stackPubkey, keptStacks)
            if (replaceAll) {
                for (appId in ListingRows.staleAppIds(tx.db, catalog, listings.keys.filterNotNull().toSet())) {
                    ListingRows.delete(tx, catalog, CatalogDelete.Listing(appId))
                    removed += appId
                }
            }
            applyArtifacts(tx, catalog, artifacts)
            ListingRows.recomputeAppPubkeys(tx, catalog.id, now)
            tx.db.prepare("UPDATE catalogs SET epoch = ? WHERE id = ?").use { statement ->
                statement.bindLong(1, toEpoch)
                statement.bindLong(2, catalog.id)
                statement.step()
            }
            tx.touch(Table.Catalogs)
        }
        checkpointWal()
        return removed
    }

    /** Local avatar WebP for [pubkey], whether or not the file has been written yet. */
    fun avatarFile(pubkey: String): File {
        require(Hex.isHex(pubkey, 64)) { "pubkey is not 32-byte hex" }
        return File(avatarsDir, "${pubkey.lowercase()}.webp")
    }

    /**
     * Writes the vector and about, security, and facts for apps that already have a listing row.
     * A record that omits one of those keeps the value stored earlier.
     * Icon and avatar bytes land on disk for Coil.
     */
    private fun applyArtifacts(tx: Tx, catalog: CatalogRecord, artifacts: CatalogArtifact) {
        if (artifacts.apps.isEmpty() && artifacts.avatars.isEmpty()) return
        val db = tx.db
        for (artifact in artifacts.apps) {
            val appRow = db.prepare("SELECT id FROM apps WHERE catalog_id = ? AND app_id = ?").use { statement ->
                statement.bindLong(1, catalog.id)
                statement.bindText(2, artifact.appId)
                if (statement.step()) statement.getBlob(0) else null
            } ?: continue
            artifact.about?.let { about ->
                db.prepare("UPDATE apps SET about = ? WHERE catalog_id = ? AND app_id = ?").use { statement ->
                    statement.bindText(1, about)
                    statement.bindLong(2, catalog.id)
                    statement.bindText(3, artifact.appId)
                    statement.step()
                }
            }
            artifact.security?.let { security ->
                db.prepare("UPDATE apps SET security = ? WHERE catalog_id = ? AND app_id = ?").use { statement ->
                    statement.bindText(1, security)
                    statement.bindLong(2, catalog.id)
                    statement.bindText(3, artifact.appId)
                    statement.step()
                }
            }
            artifact.facts?.let { facts ->
                db.prepare("UPDATE apps SET facts = ? WHERE catalog_id = ? AND app_id = ?").use { statement ->
                    statement.bindText(1, facts)
                    statement.bindLong(2, catalog.id)
                    statement.bindText(3, artifact.appId)
                    statement.step()
                }
            }
            val vector = artifact.vector
            if (vector != null) {
                db.prepare(
                    """
                    INSERT INTO apps_search (id, vector)
                    VALUES (?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        vector = excluded.vector
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindBlob(1, appRow)
                    statement.bindBlob(2, vector)
                    statement.step()
                }
            }
            artifact.webp?.let { writeBytes(File(iconsDir, "${catalog.id}/${artifact.appId}.webp"), it) }
        }
        for (avatar in artifacts.avatars) {
            writeBytes(avatarFile(avatar.pubkey), avatar.webp)
        }
        if (artifacts.apps.isNotEmpty()) tx.touch(Table.Apps)
    }

    /** Re-derives `apps.pubkey` for every catalog; run at open and at each proof expiry. */
    fun recomputeProofs(now: Long) {
        write { tx ->
            allCatalogs().forEach { ListingRows.recomputeAppPubkeys(tx, it.id, now) }
        }
    }

    /** Signing path: derived rows and the outbox row commit together. */
    internal fun commitPublish(event: Event, context: IngestContext) {
        require(event.kind != Kinds.Auth) { "NIP-42 AUTH is never added to the outbox" }
        require(event.verify()) { "published event failed verification" }
        write { tx ->
            EventRows.persist(tx, event, context)
            EventRows.insertOutbox(tx, event)
        }
    }

    fun wipe() {
        write { tx ->
            listOf("comments", "zaps", "stacks", "profiles", "outbox", "preferences", "certificate_proofs", "apps", "query_refresh")
                .forEach { tx.db.execSQL("DELETE FROM $it") }
            tx.db.execSQL("UPDATE catalogs SET epoch = 0")
            tx.db.execSQL("UPDATE catalogs SET manifest_pubkey = NULL WHERE id = 1")
            tx.touch(*Table.values())
        }
        checkpointWal()
    }

    fun pruneOlderThan(kind: Int, createdBefore: Long) {
        val (table, touched) = when (kind) {
            Kinds.Zap -> "zaps" to Table.Zaps
            Kinds.Comment -> "comments" to Table.Comments
            else -> return
        }
        write { tx ->
            tx.db.prepare("DELETE FROM $table WHERE created_at < ?").use { statement ->
                statement.bindLong(1, createdBefore)
                statement.step()
            }
            tx.touch(touched)
        }
    }

    // --- catalogs ----------------------------------------------------------------------------------

    fun catalog(id: Long): CatalogRecord? = read { db ->
        db.prepare("$SELECT_CATALOG WHERE id = ?").use { statement ->
            statement.bindLong(1, id)
            statement.rowOrNull { it.toCatalog() }
        }
    }

    fun selectedCatalogs(): List<CatalogRecord> = read { db ->
        db.prepare("$SELECT_CATALOG WHERE position IS NOT NULL ORDER BY position ASC").use { it.rows { toCatalog() } }
    }

    fun allCatalogs(): List<CatalogRecord> = read { db ->
        db.prepare("$SELECT_CATALOG ORDER BY COALESCE(position, 999999), id").use { it.rows { toCatalog() } }
    }

    /** Writes the catalog endpoint. [replaceIdentity] updates the stable catalog URL used in app ids. */
    fun upsertCatalogRelays(catalogId: Long, relays: List<RelayUrl>, replaceIdentity: Boolean) {
        val canonical = relays.firstOrNull { !it.isOnion } ?: relays.first()
        val encoded = JSONArray().apply { relays.forEach { put(it.url) } }.toString()
        write { tx ->
            val exists = tx.db.prepare("SELECT 1 FROM catalogs WHERE id = ?").use { statement ->
                statement.bindLong(1, catalogId)
                statement.step()
            }
            if (!exists) {
                tx.db.prepare(
                    """
                    INSERT INTO catalogs (id, relay_url, position, is_private, epoch, endpoints)
                    VALUES (?, ?, (SELECT COALESCE(MAX(position), -1) + 1 FROM catalogs), 0, 0, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindLong(1, catalogId)
                    statement.bindText(2, canonical.url)
                    statement.bindText(3, encoded)
                    statement.step()
                }
            } else if (replaceIdentity) {
                tx.db.prepare("UPDATE catalogs SET relay_url = ?, endpoints = ? WHERE id = ?").use { statement ->
                    statement.bindText(1, canonical.url)
                    statement.bindText(2, encoded)
                    statement.bindLong(3, catalogId)
                    statement.step()
                }
            } else {
                tx.db.prepare("UPDATE catalogs SET endpoints = ? WHERE id = ?").use { statement ->
                    statement.bindText(1, encoded)
                    statement.bindLong(2, catalogId)
                    statement.step()
                }
            }
            tx.touch(Table.Catalogs)
        }
    }

    fun pinManifestPubkeyIfAbsent(catalogId: Long, pubkey: String) {
        write { tx ->
            tx.db.prepare("UPDATE catalogs SET manifest_pubkey = ? WHERE id = ? AND manifest_pubkey IS NULL").use { statement ->
                statement.bindBlob(1, Hex.decode(pubkey))
                statement.bindLong(2, catalogId)
                statement.step()
            }
            tx.touch(Table.Catalogs)
        }
    }

    // --- apps --------------------------------------------------------------------------------------

    /** Visible listings: for each app ID, the row from the first selected catalog. */
    fun apps(filter: AppFilter = AppFilter()): List<AppRecord> = read { db ->
        val where = StringBuilder()
        val binds = mutableListOf<(SQLiteStatement, Int) -> Unit>()
        filter.appIds?.let { ids ->
            if (ids.isEmpty()) return@read emptyList()
            where.append(" AND a.app_id IN (").append(ids.joinToString(",") { "?" }).append(")")
            ids.forEach { id -> binds += { s, i -> s.bindText(i, id) } }
        }
        filter.author?.let { author ->
            val bytes = Hex.decode(author)
            where.append(" AND (a.pubkey = ? OR a.event_pubkey = ?)")
            binds += { s, i -> s.bindBlob(i, bytes) }
            binds += { s, i -> s.bindBlob(i, bytes) }
        }
        val query = filter.search?.let(::normalizeSearchQuery)?.takeIf { it.isNotEmpty() }
        if (query == null) {
            val limit = filter.limit?.let { " LIMIT $it" } ?: ""
            return@read db.prepare("$SELECT_VISIBLE_APP$where ORDER BY a.app_event_created_at DESC, a.app_id ASC$limit").use { statement ->
                binds.forEachIndexed { index, bind -> bind(statement, index + 1) }
                statement.rows { toApp(iconsDir) }
            }
        }
        val ranker = SearchRanker(query, filter.queryVector, filter.limit)
        db.prepare("$SELECT_SEARCH_KEY$where").use { statement ->
            binds.forEachIndexed { index, bind -> bind(statement, index + 1) }
            while (statement.step()) {
                ranker.consider(
                    id = statement.getBlob(0),
                    appId = statement.getText(1),
                    name = statement.getText(2),
                    docVector = if (statement.isNull(3)) null else statement.getBlob(3),
                )
            }
        }
        val ids = ranker.ids()
        if (ids.isEmpty()) return@read emptyList()
        val sql = "$SELECT_VISIBLE_APP$where AND a.id IN (${ids.joinToString(",") { "?" }})"
        val byId = db.prepare(sql).use { statement ->
            binds.forEachIndexed { index, bind -> bind(statement, index + 1) }
            ids.forEachIndexed { index, id -> statement.bindBlob(binds.size + index + 1, id) }
            statement.rows { toApp(iconsDir) }.associateBy { Hex.encode(it.id) }
        }
        ids.mapNotNull { byId[Hex.encode(it)] }
    }

    fun app(appId: String): AppRecord? = apps(AppFilter(appIds = listOf(appId), limit = 1)).firstOrNull()

    fun nextProofExpiry(nowSeconds: Long): Long? = read { db ->
        db.prepare("SELECT MIN(expiry) FROM certificate_proofs WHERE revoked = 0 AND expiry > ?").use { statement ->
            statement.bindLong(1, nowSeconds)
            if (!statement.step() || statement.isNull(0)) null else statement.getLong(0)
        }
    }

    // --- profiles ----------------------------------------------------------------------------------

    /** Stored kind 0 projections for [pubkeys]; rows that only carry a relay list are omitted. */
    fun profiles(pubkeys: Collection<String>): Map<String, ProfileRecord> = read { db ->
        if (pubkeys.isEmpty()) return@read emptyMap()
        val sql = "SELECT pubkey, updated_at, name, picture_url, metadata FROM profiles WHERE pubkey IN (" +
            pubkeys.joinToString(",") { "?" } + ")"
        db.prepare(sql).use { statement ->
            pubkeys.forEachIndexed { index, pubkey -> statement.bindBlob(index + 1, Hex.decode(pubkey)) }
            buildMap {
                while (statement.step()) {
                    val metadata = JSONObject(statement.getText(4))
                    val eventId = metadata.string("id") ?: continue
                    val content = runCatching { JSONObject(metadata.optString("content")) }.getOrElse { JSONObject() }
                    val pubkey = Hex.encode(statement.getBlob(0))
                    put(
                        pubkey,
                        ProfileRecord(
                            pubkey = pubkey,
                            updatedAt = statement.getLong(1),
                            eventId = eventId,
                            name = statement.textOrNull(2),
                            displayName = content.string("display_name"),
                            about = content.string("about"),
                            picture = statement.textOrNull(3),
                            banner = content.string("banner"),
                            website = content.string("website"),
                            nip05 = content.string("nip05"),
                        ),
                    )
                }
            }
        }
    }

    /** NIP-65 read relays for [pubkey], or null when no relay list is stored. */
    fun readRelays(pubkey: String): Set<RelayUrl>? = read { db ->
        EventRows.profileMetadata(db, pubkey)?.second
            ?.takeIf { it.has("nip65_id") }
            ?.optJSONArray("nip65_tags").toStringLists()
            .takeIf { it.isNotEmpty() }
            ?.readRelayUrls()
    }

    // --- stacks, zaps, comments --------------------------------------------------------------------

    fun stacks(author: String, identifier: String? = null, limit: Int? = null, deviceSigner: LocalSigner? = null): List<StackRecord> =
        read { db ->
            val where = if (identifier == null) "" else " AND identifier = ?"
            val limitSql = limit?.let { " LIMIT $it" } ?: ""
            db.prepare(
                "SELECT pubkey, identifier, event_id, name, description, public_apps, updated_at, metadata FROM stacks " +
                    "WHERE pubkey = ?$where ORDER BY updated_at DESC$limitSql",
            ).use { statement ->
                statement.bindBlob(1, Hex.decode(author))
                if (identifier != null) statement.bindText(2, identifier)
                statement.rows {
                    val metadata = JSONObject(getText(7))
                    val privateApps = if (deviceSigner != null) metadata.stringList("private_apps") else emptyList()
                    StackRecord(
                        pubkey = Hex.encode(getBlob(0)),
                        identifier = getText(1),
                        eventId = Hex.encode(getBlob(2)),
                        name = getText(3),
                        description = textOrNull(4).orEmpty(),
                        apps = (JSONArray(getText(5)).toStringList() + privateApps).mapNotNull(AppCoordinate::parse),
                        updatedAt = getLong(6),
                    )
                }
            }
        }

    fun zaps(appId: String, limit: Int? = null): List<ZapRecord> = read { db ->
        val limitSql = limit?.let { " LIMIT $it" } ?: ""
        db.prepare(
            "SELECT event_id, pubkey, amount_sats, created_at FROM zaps WHERE app_id = ? ORDER BY created_at DESC$limitSql",
        ).use { statement ->
            statement.bindText(1, appId)
            statement.rows {
                ZapRecord(Hex.encode(getBlob(0)), Hex.encode(getBlob(1)), getLong(2), getLong(3), appId)
            }
        }
    }

    fun comments(appId: String, limit: Int? = null): List<CommentRecord> = read { db ->
        val limitSql = limit?.let { " LIMIT $it" } ?: ""
        db.prepare(
            "SELECT event_id, pubkey, created_at, content, stack, parent_event_id FROM comments " +
                "WHERE app_id = ? ORDER BY created_at DESC$limitSql",
        ).use { statement ->
            statement.bindText(1, appId)
            statement.rows {
                CommentRecord(
                    eventId = Hex.encode(getBlob(0)),
                    pubkey = Hex.encode(getBlob(1)),
                    createdAt = getLong(2),
                    content = getText(3),
                    appId = appId,
                    stack = textOrNull(4),
                    parentEventId = blobHexOrNull(5),
                )
            }
        }
    }

    // --- outbox ------------------------------------------------------------------------------------

    fun pendingOutbox(): List<Event> = read { db ->
        db.prepare("SELECT event_json FROM outbox").use { it.rows { Event.parse(getText(0)) } }
    }

    fun deleteOutbox(eventId: String) {
        write { tx ->
            tx.db.prepare("DELETE FROM outbox WHERE event_id = ?").use { statement ->
                statement.bindBlob(1, Hex.decode(eventId))
                statement.step()
            }
            tx.touch(Table.Outbox)
        }
    }

    // --- query refresh cache -----------------------------------------------------------------------

    fun lastRefresh(fingerprint: String): Long? = read { db ->
        db.prepare("SELECT refreshed_at FROM query_refresh WHERE fingerprint = ?").use { statement ->
            statement.bindText(1, fingerprint)
            if (statement.step()) statement.getLong(0) else null
        }
    }

    fun recordRefresh(fingerprint: String, epochMillis: Long) {
        if (epochMillis <= 0) return
        // No listener cares about this table, so no notification is needed.
        read { db ->
            db.prepare("INSERT OR REPLACE INTO query_refresh (fingerprint, refreshed_at) VALUES (?, ?)").use { statement ->
                statement.bindText(1, fingerprint)
                statement.bindLong(2, epochMillis)
                statement.step()
            }
        }
    }

    // --- rehydration templates ---------------------------------------------------------------------

    fun catalogRelayListTemplate(deviceSigner: LocalSigner): Pair<List<List<String>>, String> {
        val selected = selectedCatalogs()
        val tags = selected.filter { !it.isPrivate }.map { listOf("r", it.relayUrl.url) }
        val privateUrls = selected.filter { it.isPrivate }.map { it.relayUrl.url }
        val content = if (privateUrls.isEmpty()) "" else deviceSigner.encryptToSelf(JSONArray(privateUrls).toString())
        return tags to content
    }

    fun preferenceTemplate(identifier: String): Pair<List<List<String>>, String> {
        val row = read { db ->
            db.prepare("SELECT metadata FROM preferences WHERE identifier = ?").use { statement ->
                statement.bindText(1, identifier)
                statement.rowOrNull { JSONObject(it.getText(0)) }
            }
        } ?: error("missing preference $identifier")
        val leftovers = EventRows.leftoverTags(row.optJSONArray("tags").toStringLists(), setOf("d"))
        return (listOf(listOf("d", identifier)) + leftovers) to row.optString("content")
    }

    fun stackTemplate(pubkey: String, identifier: String, device: DeviceProfile, deviceSigner: LocalSigner?): Pair<List<List<String>>, String> {
        val (stack, storedTags) = read { db ->
            db.prepare("SELECT metadata FROM stacks WHERE pubkey = ? AND identifier = ?").use { statement ->
                statement.bindBlob(1, Hex.decode(pubkey))
                statement.bindText(2, identifier)
                statement.rowOrNull { JSONObject(it.getText(0)).optJSONArray("tags").toStringLists() }
            }
        }?.let { tags -> stacks(pubkey, identifier, deviceSigner = deviceSigner).first() to tags }
            ?: error("missing stack $identifier")
        val privateApps = if (deviceSigner == null) emptyList() else read { db ->
            db.prepare("SELECT metadata FROM stacks WHERE pubkey = ? AND identifier = ?").use { statement ->
                statement.bindBlob(1, Hex.decode(pubkey))
                statement.bindText(2, identifier)
                statement.rowOrNull { JSONObject(it.getText(0)).stringList("private_apps") }
            }
        }.orEmpty()
        val privateSet = privateApps.toSet()
        val tags = buildList {
            add(listOf("d", identifier))
            add(listOf("name", stack.name))
            stack.description.takeIf { it.isNotBlank() }?.let { add(listOf("description", it)) }
            stack.apps.map(AppCoordinate::toString).filter { it !in privateSet }.forEach { add(listOf("a", it)) }
            device.platforms.forEach { add(listOf("f", it)) }
            addAll(EventRows.leftoverTags(storedTags, EventRows.STACK_MODELED_TAGS))
        }
        val content = if (privateApps.isEmpty() || deviceSigner == null) {
            ""
        } else {
            deviceSigner.encryptToSelf(JSONArray(privateApps).toString())
        }
        return tags to content
    }

    companion object {
        private const val SELECT_CATALOG =
            "SELECT id, relay_url, position, is_private, manifest_pubkey, epoch, endpoints FROM catalogs"

        /**
         * Visible listings: join the catalog for its manifest key, keep only selected catalogs, and for each
         * app ID keep the row whose catalog comes first in selection order.
         */
        private val SELECT_VISIBLE_APP = """
            SELECT a.id, a.catalog_id, a.app_id, a.certificate_hash, a.app_event_created_at, a.pubkey, a.event_pubkey,
                   c.manifest_pubkey, a.name, a.summary, a.repository, a.version, a.version_code, a.channel, a.metadata,
                   a.about, a.security, a.facts
            FROM apps a
            JOIN catalogs c ON c.id = a.catalog_id
            WHERE c.position IS NOT NULL
              AND a.catalog_id = (
                  SELECT a2.catalog_id FROM apps a2 JOIN catalogs c2 ON c2.id = a2.catalog_id
                  WHERE a2.app_id = a.app_id AND c2.position IS NOT NULL
                  ORDER BY c2.position ASC LIMIT 1
              )
        """.trimIndent()

        /** Columns [SearchRanker] needs. Full listings are loaded only for the ids it keeps. */
        private val SELECT_SEARCH_KEY = """
            SELECT a.id, a.app_id, a.name, s.vector
            FROM apps a
            JOIN catalogs c ON c.id = a.catalog_id
            LEFT JOIN apps_search s ON s.id = a.id
            WHERE c.position IS NOT NULL
              AND a.catalog_id = (
                  SELECT a2.catalog_id FROM apps a2 JOIN catalogs c2 ON c2.id = a2.catalog_id
                  WHERE a2.app_id = a.app_id AND c2.position IS NOT NULL
                  ORDER BY c2.position ASC LIMIT 1
              )
        """.trimIndent()

        fun open(
            path: String,
            openConnection: (String) -> SQLiteConnection = ::openBundledOrJdbc,
        ): SQLiteConnection {
            File(path).parentFile?.mkdirs()
            val connection = openConnection(path)
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.execSQL("PRAGMA journal_mode = WAL")
            val version = connection.prepare("PRAGMA user_version").use { statement ->
                statement.step()
                statement.getLong(0).toInt()
            }
            when (version) {
                Schema.USER_VERSION -> Unit
                0 -> {
                    connection.execSQL("BEGIN IMMEDIATE")
                    try {
                        Schema.statements.forEach(connection::execSQL)
                        connection.execSQL("PRAGMA user_version = ${Schema.USER_VERSION}")
                        connection.execSQL("COMMIT")
                    } catch (failure: Throwable) {
                        runCatching { connection.execSQL("ROLLBACK") }
                        throw failure
                    }
                }
                else -> error("unsupported Iolite schema version $version")
            }
            runCatching {
                connection.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { it.step() }
            }
            return connection
        }
    }
}

internal fun openBundledOrJdbc(path: String): SQLiteConnection {
    if (runCatching { Class.forName("org.sqlite.JDBC") }.isSuccess) {
        return JdbcSqliteConnection(path)
    }
    return BundledSQLiteDriver().open(path)
}

private fun SQLiteStatement.toCatalog(): CatalogRecord {
    val relayUrl = getText(1).normalizeRelayUrl()
    val relays = decodeRelays(if (isNull(6)) "[]" else getText(6)).ifEmpty { listOf(relayUrl) }
    return CatalogRecord(
        id = getLong(0),
        relayUrl = relayUrl,
        relays = relays,
        position = if (isNull(2)) null else getInt(2),
        isPrivate = getLong(3) == 1L,
        manifestPubkey = blobHexOrNull(4),
        epoch = getLong(5),
    )
}

private fun decodeRelays(raw: String): List<RelayUrl> {
    if (raw.isBlank() || raw == "[]") return emptyList()
    val array = JSONArray(raw)
    return List(array.length()) { index -> array.getString(index).normalizeRelayUrl() }
}

private fun writeBytes(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) {
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }
}

private fun SQLiteStatement.toApp(iconsDir: File): AppRecord = AppRecord(
    id = getBlob(0),
    catalogId = getLong(1),
    appId = getText(2),
    certificateHash = Hex.encode(getBlob(3)),
    createdAt = getLong(4),
    proofPubkey = blobHexOrNull(5),
    eventPubkey = Hex.encode(getBlob(6)),
    catalogManifestPubkey = blobHexOrNull(7),
    name = getText(8),
    summary = getText(9),
    repository = textOrNull(10),
    version = getText(11),
    versionCode = getLong(12),
    channel = textOrNull(13),
    metadata = JSONObject(getText(14)),
    about = getText(15),
    security = getText(16),
    facts = getText(17),
    iconFile = File(iconsDir, "${getLong(1)}/${getText(2)}.webp").takeIf { it.isFile },
)

private fun <T> SQLiteStatement.rowOrNull(map: (SQLiteStatement) -> T): T? = if (step()) map(this) else null

private fun <T> SQLiteStatement.rows(map: SQLiteStatement.() -> T): List<T> = buildList {
    while (step()) add(map())
}
