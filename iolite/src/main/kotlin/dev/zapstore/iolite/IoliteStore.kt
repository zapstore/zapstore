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
    /** Matches the current NIP-C1 proof owner; signing a listing is not authorship. */
    val author: String? = null,
    /**
     * Case-insensitive exact or prefix match on the name or app ID.
     * With [queryVector], listings are also ranked by vector similarity.
     * Null when the query was only fact phrases.
     */
    val search: String? = null,
    /** leaf-ir-v1 query vector, [VECTOR_DIMS] int8 bytes. Ignored unless [search] is set. */
    val queryVector: ByteArray? = null,
    /** Required fact bits. Unknown and the opposite value both fail. */
    val hard: Int = 0,
    /** Fact bits that must be clear, as in "no google services". */
    val exclude: Int = 0,
    /** While typing: name or app ID starts with this, newest first. No ranking, no vector. */
    val prefix: String? = null,
    /** A normalized repository URL ([normalizeRepositoryUrl]). Matches only that repository; sorted by app ID. */
    val repository: String? = null,
    /** Fact bits that raise a listing above a soft preference. A missing bit stays in the list. */
    val boost: Int = 0,
    /** Fact bits that raise a listing when set. */
    val soft: Int = 0,
    /** Fact bits that lower a listing when set. */
    val penalty: Int = 0,
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
 * Reads return typed records. No event JSON is rehydrated for reads. Reads use their own WAL connection,
 * so a long catalog import never holds up a screen.
 */
class IoliteStore internal constructor(
    path: String,
    private val iconsDir: File = File(File(path).parentFile, "icons"),
    private val avatarsDir: File = File(File(path).parentFile, "avatars"),
    openConnection: (String) -> SQLiteConnection = ::openBundled,
    log: IoliteLog = IoliteLog { _, _ -> },
) : AutoCloseable {
    private class Listener(val tables: Set<Table>, val notify: () -> Unit)

    private val lock = ReentrantLock()
    private val connection: SQLiteConnection = open(path, openConnection, log)
    private val readLock = ReentrantLock()
    private val reader: SQLiteConnection = openReader(path, openConnection)
    private val listeners = CopyOnWriteArrayList<Listener>()

    // --- change notification -----------------------------------------------------------------------

    /** Emits once on subscription and after every commit that touched any of [tables]. Conflated. */
    fun changes(tables: Set<Table>): Flow<Unit> = callbackFlow {
        val listener = Listener(tables) { trySend(Unit) }
        listeners += listener
        trySend(Unit)
        awaitClose { listeners -= listener }
    }.buffer(Channel.CONFLATED)

    /** One snapshot: a block sees the last commit before it began, whatever is written meanwhile. */
    fun <T> read(block: (SQLiteConnection) -> T): T = readLock.withLock {
        if (readLock.holdCount > 1) return@withLock block(reader)
        reader.execSQL("BEGIN")
        try {
            block(reader)
        } finally {
            runCatching { reader.execSQL("COMMIT") }
        }
    }

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
        readLock.withLock { reader.close() }
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
        if (admissible.any { it.kind == Kinds.CatalogRelayList }) pruneIconDirs()
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
        skipped: MutableList<String> = ArrayList(),
    ): Set<String> {
        val stackPubkey = catalog.manifestPubkey ?: throw CatalogImportException("catalog manifest pubkey is missing")
        return importCatalog(catalog.id, stackPubkey, toEpoch, device, now, skipped) { writer ->
            for (event in events) {
                if (event.kind != Kinds.AppStack) continue
                if (event.pubkey != stackPubkey || event.dTag().isNullOrBlank()) {
                    skipped += "stack ${event.id} is not a catalog stack"
                }
            }
            deletes.forEach(writer::delete)
            val listings = events.filter { ListingRows.isListingKind(it.kind) }.groupBy(ListingRows::listingAppId)
            for ((appId, listing) in listings) {
                if (appId != null) writer.listing(appId, listing)
            }
            writer.events(events.filter { !ListingRows.isListingKind(it.kind) }, replaceAll)
            writer.artifacts(artifacts)
        }
    }

    /**
     * Catalog path: one epoch in one transaction. [block] hands the bundle's parts to the [CatalogWriter]
     * as they stream out, so the import never holds the whole catalog. The catalog row is created when
     * missing and its manifest key pinned; the epoch moves to [toEpoch] at the end. Returns the app IDs
     * whose listings were deleted.
     */
    internal fun importCatalog(
        catalogId: Long,
        manifestPubkey: String,
        toEpoch: Long,
        device: DeviceProfile,
        now: Long,
        skipped: MutableList<String>,
        block: (CatalogWriter) -> Unit,
    ): Set<String> {
        val removed = LinkedHashSet<String>()
        write { tx ->
            tx.db.prepare(
                """
                INSERT INTO catalogs (id, position, is_private, epoch)
                VALUES (?, (SELECT COALESCE(MAX(position), -1) + 1 FROM catalogs), 0, 0)
                ON CONFLICT(id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.bindLong(1, catalogId)
                statement.step()
            }
            tx.db.prepare("UPDATE catalogs SET manifest_pubkey = ? WHERE id = ? AND manifest_pubkey IS NULL").use { statement ->
                statement.bindBlob(1, Hex.decode(manifestPubkey))
                statement.bindLong(2, catalogId)
                statement.step()
            }
            val catalog = tx.db.prepare("$SELECT_CATALOG WHERE id = ?").use { statement ->
                statement.bindLong(1, catalogId)
                statement.rowOrNull { it.toCatalog() }
            } ?: throw CatalogImportException("unknown catalog $catalogId")
            val writer = CatalogWriter(tx, catalog, device, now, skipped)
            block(writer)
            removed += writer.removed
            ListingRows.recomputeAppPubkeys(tx, catalog.id, now)
            writer.storeTrust()
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

    /**
     * Writes one catalog import inside its transaction. One bad item is rolled back to its savepoint and
     * skipped; the items around it stay. [events] runs once, after every [listing].
     */
    internal inner class CatalogWriter(
        private val tx: Tx,
        val catalog: CatalogRecord,
        private val device: DeviceProfile,
        private val now: Long,
        private val skipped: MutableList<String>,
    ) {
        private val stackPubkey = checkNotNull(catalog.manifestPubkey)
        private val listed = HashSet<String>()
        /** Each app's `trust` line, held until [storeTrust]: the publisher is only known once the proofs are applied. */
        private val trust = LinkedHashMap<String, String>()
        val removed = LinkedHashSet<String>()

        fun guard(what: String, block: () -> Unit): Boolean {
            tx.db.execSQL("SAVEPOINT catalog_item")
            return try {
                block()
                tx.db.execSQL("RELEASE catalog_item")
                true
            } catch (failure: Exception) {
                tx.db.execSQL("ROLLBACK TO catalog_item")
                tx.db.execSQL("RELEASE catalog_item")
                skipped += "$what: ${failure.message ?: failure.javaClass.simpleName}"
                false
            }
        }

        /** Every listing event that arrived for [appId]. */
        fun listing(appId: String, events: List<Event>) {
            listed += appId
            guard("listing $appId") { ListingRows.persistListing(tx, catalog, appId, events, device, now) }
        }

        fun delete(delete: CatalogDelete) {
            if (delete is CatalogDelete.Coordinate && delete.kind == Kinds.AppStack && delete.pubkey != stackPubkey) {
                skipped += "stack delete is not for the catalog key"
                return
            }
            if (guard("delete $delete") { ListingRows.delete(tx, catalog, delete) } && delete is CatalogDelete.Listing) {
                removed += delete.appId
            }
        }

        /** Proofs, profiles, and stacks. With [replaceAll], stacks and listings this import did not carry are removed. */
        fun events(events: List<Event>, replaceAll: Boolean) {
            events.filter { it.kind == Kinds.IdentityProof }.forEach { proof ->
                guard("proof ${proof.id}") { ListingRows.persistProof(tx, catalog, proof) }
            }
            val keptStacks = LinkedHashSet<String>()
            val context = IngestContext(device, null)
            for (event in events) {
                if (event.kind == Kinds.Profile) guard("profile ${event.pubkey}") { EventRows.persist(tx, event, context) }
            }
            for (event in events) {
                if (event.kind != Kinds.AppStack || event.pubkey != stackPubkey) continue
                if (!EventRows.stackMatchesDevice(event, device)) continue
                val identifier = event.dTag()?.takeIf { it.isNotBlank() } ?: continue
                if (guard("stack $identifier") { EventRows.persist(tx, event, context) }) keptStacks += identifier
            }
            if (!replaceAll) return
            EventRows.retainCatalogStacks(tx, stackPubkey, keptStacks)
            for (appId in ListingRows.staleAppIds(tx.db, catalog, listed)) {
                ListingRows.delete(tx, catalog, CatalogDelete.Listing(appId))
                removed += appId
            }
        }

        fun artifacts(artifacts: CatalogArtifact) = applyArtifacts(tx, catalog, artifacts, trust, ::guard)

        /**
         * Followers belong to the C1 publisher, which `apps.pubkey` names only after [ListingRows.recomputeAppPubkeys].
         * An app with no proof, or one the catalog key signed, has no publisher, so nothing is kept for it.
         */
        fun storeTrust() {
            for ((appId, line) in trust) {
                val publisher = tx.db.prepare("SELECT pubkey FROM apps WHERE catalog_id = ? AND app_id = ?").use { statement ->
                    statement.bindLong(1, catalog.id)
                    statement.bindText(2, appId)
                    if (statement.step()) statement.blobHexOrNull(0) else null
                } ?: continue
                if (publisher == stackPubkey) continue
                guard("trust for $appId") { EventRows.putTrust(tx.db, publisher, line) }
                tx.touch(Table.Profiles)
            }
            trust.clear()
        }

        fun relays(relays: List<RelayUrl>) = writeCatalogRelays(tx, catalog.id, relays, replaceIdentity = true)
    }

    /** Local avatar WebP for [pubkey], whether or not the file has been written yet. */
    fun avatarFile(pubkey: String): File {
        require(Hex.isHex(pubkey, 64)) { "pubkey is not 32-byte hex" }
        return File(avatarsDir, "${pubkey.lowercase()}.webp")
    }

    /**
     * Writes the vector and about and security for apps that already have a listing row.
     * A missing about or security member means that note did not change, and the stored text stays.
     * A missing vector or icon does the same. Icon and avatar bytes land on disk for Coil.
     */
    private fun applyArtifacts(
        tx: Tx,
        catalog: CatalogRecord,
        artifacts: CatalogArtifact,
        trust: MutableMap<String, String>,
        guard: (String, () -> Unit) -> Boolean,
    ) {
        if (artifacts.apps.isEmpty() && artifacts.avatars.isEmpty()) return
        val db = tx.db
        for (artifact in artifacts.apps) guard("files for ${artifact.appId}") appFiles@{
            val appRow = db.prepare("SELECT id FROM apps WHERE catalog_id = ? AND app_id = ?").use { statement ->
                statement.bindLong(1, catalog.id)
                statement.bindText(2, artifact.appId)
                if (statement.step()) statement.getBlob(0) else null
            } ?: return@appFiles
            // The publisher is not known yet on a full import: apps.pubkey is derived after the proofs land.
            artifact.trust?.let { trust[artifact.appId] = it }
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
                db.prepare(
                    """
                    INSERT INTO apps_search (id, keywords)
                    VALUES (?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        keywords = excluded.keywords
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindBlob(1, appRow)
                    statement.bindText(2, securityKeywords(security).joinToString("\n"))
                    statement.step()
                }
                securityFactsCsv(security)?.let { facts ->
                    db.prepare("UPDATE apps SET facts = ?, fact_bits = ? WHERE catalog_id = ? AND app_id = ?").use { statement ->
                        statement.bindText(1, facts)
                        statement.bindLong(2, factBits(facts).toLong())
                        statement.bindLong(3, catalog.id)
                        statement.bindText(4, artifact.appId)
                        statement.step()
                    }
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
            guard("avatar ${avatar.pubkey}") { writeBytes(avatarFile(avatar.pubkey), avatar.webp) }
        }
        if (artifacts.apps.isNotEmpty()) tx.touch(Table.Apps)
    }

    /** Re-derives `apps.pubkey` for every catalog; run at open and at each proof expiry. */
    fun recomputeProofs(now: Long) {
        val catalogs = allCatalogs()
        write { tx ->
            catalogs.forEach { ListingRows.recomputeAppPubkeys(tx, it.id, now) }
        }
    }

    /** Signing path: derived rows and the publish queue row commit together. */
    internal fun commitPublish(event: Event, context: IngestContext) {
        require(event.kind != Kinds.Auth) { "NIP-42 AUTH is never added to the publish queue" }
        require(event.verify()) { "published event failed verification" }
        write { tx ->
            EventRows.persist(tx, event, context)
            EventRows.enqueuePublish(tx, event)
        }
    }

    /** Swaps a queued draft for its mined form; the derived row follows the mined event. */
    internal fun replaceQueuedPublish(draftId: String, mined: Event, context: IngestContext) {
        require(mined.verify()) { "mined event failed verification" }
        write { tx ->
            tx.db.prepare("DELETE FROM publish_queue WHERE event_id = ?").use { statement ->
                statement.bindBlob(1, Hex.decode(draftId))
                statement.step()
            }
            EventRows.persist(tx, mined, context)
            EventRows.enqueuePublish(tx, mined)
        }
    }

    /** Decrypted content of a kind 30078 signed by [deviceSigner], or null. */
    /** `created_at` of the stored kind 30078 or 30267 for this author and `d`, or null. */
    fun storedCreatedAt(kind: Int, pubkey: String, identifier: String): Long? = read { db ->
        when (kind) {
            Kinds.Preferences -> db.prepare("SELECT updated_at, metadata FROM preferences WHERE identifier = ?").use { statement ->
                statement.bindText(1, identifier)
                statement.rowOrNull { row -> row.getLong(0).takeIf { JSONObject(row.getText(1)).optString("pubkey") == pubkey } }
            }
            Kinds.AppStack -> db.prepare("SELECT updated_at FROM stacks WHERE pubkey = ? AND identifier = ?").use { statement ->
                statement.bindBlob(1, Hex.decode(pubkey))
                statement.bindText(2, identifier)
                statement.rowOrNull { it.getLong(0) }
            }
            else -> null
        }
    }

    /** The followers the catalog listed for [publisher], or null. A kind 0 update leaves them be. */
    fun trust(publisher: String): Trust? =
        read { db -> EventRows.profileMetadata(db, publisher)?.second?.optString("trust") }
            ?.takeIf(String::isNotEmpty)
            ?.let(Trust::parse)

    fun devicePreference(identifier: String, deviceSigner: LocalSigner?): String? {
        deviceSigner ?: return null
        val row = read { db ->
            db.prepare("SELECT metadata FROM preferences WHERE identifier = ?").use { statement ->
                statement.bindText(1, identifier)
                statement.rowOrNull { JSONObject(it.getText(0)) }
            }
        } ?: return null
        if (row.optString("pubkey") != deviceSigner.publicKey) return null
        return runCatching { deviceSigner.decryptFromSelf(row.optString("content")) }.getOrNull()
    }

    /**
     * Drops everything downloaded. The user's catalog list stays exactly as it is, empty included, with
     * each catalog's key: they are choices, not cache. Every catalog is fetched again from epoch 0.
     */
    /**
     * Drops everything that is fetched again: the catalog and social reads. The catalog list, the
     * device's own lists (`preferences`), and events not yet published stay, since they may exist only here.
     */
    fun wipe() {
        write { tx ->
            listOf("comments", "zaps", "stacks", "profiles", "relay_reports", "certificate_proofs", "apps", "query_refresh")
                .forEach { tx.db.execSQL("DELETE FROM $it") }
            tx.db.execSQL("UPDATE catalogs SET epoch = 0")
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

    /** Writes the catalog endpoint. [replaceIdentity] replaces the canonical relay URL. */
    fun upsertCatalogRelays(catalogId: Long, relays: List<RelayUrl>, replaceIdentity: Boolean) {
        write { tx -> writeCatalogRelays(tx, catalogId, relays, replaceIdentity) }
    }

    private fun writeCatalogRelays(tx: Tx, catalogId: Long, relays: List<RelayUrl>, replaceIdentity: Boolean) {
        val canonical = relays.firstOrNull { !it.isOnion } ?: relays.first()
        val encoded = JSONArray().apply { relays.forEach { put(it.url) } }.toString()
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

    fun catalogByPubkey(pubkey: String): CatalogRecord? = read { db -> CatalogRows.byPubkey(db, pubkey) }

    /**
     * True once the device's catalog list is decided: its kind 10067 is stored, whether it came from
     * another device or was published here. Until then the phone has never had a say, and the bundled
     * default is used. An empty list is a decision too.
     */
    fun catalogListDecided(): Boolean = catalogListUpdatedAt() != null

    /** `created_at` of the stored catalog list event, or null when none is stored. */
    fun catalogListUpdatedAt(): Long? = read { db ->
        db.prepare("SELECT updated_at FROM preferences WHERE identifier = ?").use { statement ->
            statement.bindText(1, EventRows.CATALOG_RELAY_LIST_KEY)
            statement.rowOrNull { it.getLong(0) }
        }
    }

    /**
     * Adds a catalog to the end of the list, or selects it again when it is stored but unselected.
     * [relays] are its addresses when known; [hints] are relays to ask for them otherwise.
     */
    fun addCatalog(pubkey: String, relays: List<RelayUrl>, hints: List<RelayUrl> = emptyList(), isPrivate: Boolean = false): CatalogRecord {
        var added: CatalogRecord? = null
        write { tx ->
            val id = CatalogRows.upsert(tx, pubkey, relays, hints, isPrivate)
            val selected = CatalogRows.all(tx.db).filter { it.position != null }.map { it.id }
            if (id !in selected) CatalogRows.setOrder(tx, selected + id)
            added = CatalogRows.byPubkey(tx.db, pubkey)
        }
        return added!!
    }

    /**
     * Puts the bundled default on the list when the user has never chosen one. Returns it, or null when
     * the list was already decided or a catalog is stored.
     */
    fun seedCatalog(pubkey: String, relay: RelayUrl): CatalogRecord? {
        var seeded: CatalogRecord? = null
        write { tx ->
            if (catalogListDecided() || CatalogRows.all(tx.db).isNotEmpty()) return@write
            val id = CatalogRows.upsert(tx, pubkey, listOf(relay), emptyList(), isPrivate = false)
            CatalogRows.setOrder(tx, listOf(id))
            seeded = CatalogRows.byPubkey(tx.db, pubkey)
        }
        return seeded
    }

    /** Removes the catalog and everything downloaded from it. False when there was no such catalog. */
    fun removeCatalog(id: Long): Boolean {
        var removed = false
        write { tx ->
            val catalog = CatalogRows.all(tx.db).firstOrNull { it.id == id } ?: return@write
            CatalogRows.remove(tx, catalog)
            removed = true
        }
        if (removed) pruneIconDirs()
        return removed
    }

    /** Writes the addresses read from the catalog's kind 10067. */
    fun setCatalogRelays(id: Long, relays: List<RelayUrl>) {
        require(relays.isNotEmpty()) { "a catalog needs an address" }
        upsertCatalogRelays(id, relays, replaceIdentity = true)
    }

    /** Deletes icon folders of catalogs that are gone. */
    internal fun pruneIconDirs() {
        val live = read { db -> CatalogRows.all(db).map { it.id.toString() }.toSet() }
        iconsDir.listFiles().orEmpty().filter { it.isDirectory && it.name !in live }.forEach { it.deleteRecursively() }
    }

    // --- apps --------------------------------------------------------------------------------------

    /** Visible listings: for each app ID, the row from the first selected catalog. */
    /** How many listings [apps] returns with no filter, without loading them. */
    fun appCount(): Int = read { db ->
        db.prepare("SELECT COUNT(*) FROM ($SELECT_VISIBLE_APP)").use { statement ->
            statement.step()
            statement.getLong(0).toInt()
        }
    }

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
            where.append(" AND a.pubkey = ?")
            binds += { s, i -> s.bindBlob(i, bytes) }
        }
        if (filter.hard != 0) {
            where.append(" AND (a.fact_bits & ?) = ?")
            val hard = filter.hard.toLong()
            binds += { s, i -> s.bindLong(i, hard) }
            binds += { s, i -> s.bindLong(i, hard) }
        }
        if (filter.exclude != 0) {
            where.append(" AND (a.fact_bits & ?) = 0")
            val exclude = filter.exclude.toLong()
            binds += { s, i -> s.bindLong(i, exclude) }
        }
        filter.repository?.let { repository ->
            val ids = db.prepare("SELECT DISTINCT app_id, repository FROM apps WHERE repository IS NOT NULL").use { statement ->
                buildList {
                    while (statement.step()) {
                        if (normalizeRepositoryUrl(statement.getText(1)) == repository) add(statement.getText(0))
                    }
                }
            }
            if (ids.isEmpty()) return@read emptyList()
            where.append(" AND a.app_id IN (").append(ids.joinToString(",") { "?" }).append(")")
            ids.forEach { id -> binds += { s, i -> s.bindText(i, id) } }
            val limit = filter.limit?.let { " LIMIT $it" } ?: ""
            return@read db.prepare("$SELECT_VISIBLE_APP$where ORDER BY a.app_id ASC$limit").use { statement ->
                binds.forEachIndexed { index, bind -> bind(statement, index + 1) }
                statement.rows { toApp(iconsDir) }
            }
        }
        filter.prefix?.let(::normalizeSearchQuery)?.takeIf { it.isNotEmpty() }?.let { prefix ->
            val pattern = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            where.append(" AND (lower(a.name) LIKE ? ESCAPE '\\' OR lower(a.app_id) LIKE ? ESCAPE '\\')")
            binds += { s, i -> s.bindText(i, pattern) }
            binds += { s, i -> s.bindText(i, pattern) }
            val limit = filter.limit?.let { " LIMIT $it" } ?: ""
            return@read db.prepare("$SELECT_VISIBLE_APP$where ORDER BY a.app_event_created_at DESC, a.app_id ASC$limit").use { statement ->
                binds.forEachIndexed { index, bind -> bind(statement, index + 1) }
                statement.rows { toApp(iconsDir) }
            }
        }
        val query = filter.search?.let(::normalizeSearchQuery)?.takeIf { it.isNotEmpty() }
        val rankFacts = filter.boost != 0 || filter.soft != 0 || filter.penalty != 0
        if (query == null && !rankFacts) {
            val limit = filter.limit?.let { " LIMIT $it" } ?: ""
            return@read db.prepare("$SELECT_VISIBLE_APP$where ORDER BY a.app_event_created_at DESC, a.app_id ASC$limit").use { statement ->
                binds.forEachIndexed { index, bind -> bind(statement, index + 1) }
                statement.rows { toApp(iconsDir) }
            }
        }
        val ranker = SearchRanker(
            query = query.orEmpty(),
            queryVector = if (query == null) null else filter.queryVector,
            limit = filter.limit,
            boost = filter.boost,
            soft = filter.soft,
            penalty = filter.penalty,
            admitAll = query == null,
        )
        db.prepare("$SELECT_SEARCH_KEY$where").use { statement ->
            binds.forEachIndexed { index, bind -> bind(statement, index + 1) }
            while (statement.step()) {
                ranker.consider(
                    id = statement.getBlob(0),
                    appId = statement.getText(1),
                    name = statement.getText(2),
                    docVector = if (statement.isNull(3)) null else statement.getBlob(3),
                    factBits = statement.getLong(4).toInt(),
                    keywords = if (statement.isNull(5)) emptyList() else statement.getText(5).lines().filter { it.isNotEmpty() },
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

    /** The stored proof of [pubkey] for [certificateHash] in [catalogId], revoked or not. */
    fun proof(catalogId: Long, certificateHash: String, pubkey: String): CertificateProof? = read { db ->
        db.prepare(
            """
            SELECT created_at, expiry, revoked, signature FROM certificate_proofs
            WHERE catalog_id = ? AND certificate_hash = ? AND pubkey = ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, catalogId)
            statement.bindBlob(2, Hex.decode(certificateHash))
            statement.bindBlob(3, Hex.decode(pubkey))
            if (!statement.step()) return@use null
            CertificateProof(
                pubkey = pubkey.lowercase(),
                certificateHash = certificateHash.lowercase(),
                createdAt = statement.getLong(0),
                expiry = statement.getLong(1),
                revoked = statement.getLong(2) != 0L,
                signature = statement.getText(3),
            )
        }
    }

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
                            lud16 = content.string("lud16"),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Developers of the visible listings, best match first, as hex keys: the owners of a current NIP-C1 proof,
     * other than a catalog key, whose stored profile name or display name holds [query], or whose npub does.
     * A developer with no stored profile can still be found by npub.
     */
    fun developers(query: String, limit: Int): List<String> {
        val needle = normalizeSearchQuery(query)
        if (needle.length < MIN_DEVELOPER_QUERY) return emptyList()
        val authors = read { db ->
            db.prepare(
                "SELECT DISTINCT a.pubkey, c.manifest_pubkey FROM apps a JOIN catalogs c ON c.id = a.catalog_id " +
                    "WHERE c.position IS NOT NULL AND a.pubkey IS NOT NULL AND $VISIBLE_LISTING",
            ).use { statement ->
                buildSet {
                    while (statement.step()) {
                        val key = Hex.encode(statement.getBlob(0))
                        if (key != statement.blobHexOrNull(1)) add(key)
                    }
                }
            }
        }
        if (authors.isEmpty()) return emptyList()
        val profiles = profiles(authors)
        return authors.mapNotNull { key ->
            val profile = profiles[key]
            val rank = developerRank(listOfNotNull(profile?.name, profile?.displayName), key.toNpub(), needle)
            if (rank < 0) null else Triple(rank, profile?.displayNameOrNpub ?: key.toNpub(), key)
        }.sortedWith(compareBy({ it.first }, { it.second.length }, { it.third })).take(limit).map { it.third }
    }

    /** Stored NIP-65 lists by pubkey; a pubkey without a stored kind 10002 is absent. */
    fun mailboxes(pubkeys: Collection<String>): Map<String, Mailboxes> = read { db ->
        buildMap {
            for (pubkey in pubkeys.distinct()) {
                val metadata = EventRows.profileMetadata(db, pubkey)?.second?.takeIf { it.has("nip65_id") } ?: continue
                put(pubkey, Mailboxes.parse(metadata.optJSONArray("nip65_tags").toStringLists()))
            }
        }
    }

    /** What trusted monitors last said about each of [relays]; unreported relays are absent. */
    fun relayStandings(relays: Collection<RelayUrl>): Map<RelayUrl, RelayStanding> = read { db ->
        val reports = ArrayList<RelayReport>()
        relays.distinct().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.prepare(
                "SELECT relay_url, monitor, created_at, payment FROM relay_reports WHERE relay_url IN ($placeholders)",
            ).use { statement ->
                chunk.forEachIndexed { index, relay -> statement.bindText(index + 1, relay.url) }
                while (statement.step()) {
                    reports += RelayReport(
                        relay = RelayUrl(statement.getText(0)),
                        monitor = Hex.encode(statement.getBlob(1)),
                        createdAt = statement.getLong(2),
                        requiresPayment = statement.getLong(3) == 1L,
                    )
                }
            }
        }
        reports.groupBy { it.relay }.mapNotNull { (relay, list) -> RelayStanding.of(list)?.let { relay to it } }.toMap()
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

    /** The stacks of every key in [authors], newest first across all of them. The device's own private apps are not added. */
    fun catalogStacks(authors: List<String>, limit: Int? = null): List<StackRecord> =
        authors.distinct().flatMap { stacks(it, limit = limit) }
            .sortedByDescending(StackRecord::updatedAt)
            .let { all -> if (limit == null) all else all.take(limit) }

    /** Stacks with at least one public app, newest first, optionally older than [until] and not signed by [excluding]. */
    fun publicStacks(limit: Int, until: Long? = null, excluding: String? = null): List<StackRecord> = read { db ->
        val clauses = buildList {
            add("public_apps <> '[]'")
            if (until != null) add("updated_at <= ?")
            if (excluding != null) add("pubkey <> ?")
        }
        db.prepare(
            "SELECT pubkey, identifier, event_id, name, description, public_apps, updated_at FROM stacks " +
                "WHERE ${clauses.joinToString(" AND ")} ORDER BY updated_at DESC LIMIT $limit",
        ).use { statement ->
            var index = 1
            if (until != null) statement.bindLong(index++, until)
            if (excluding != null) statement.bindBlob(index, Hex.decode(excluding))
            statement.rows {
                StackRecord(
                    pubkey = Hex.encode(getBlob(0)),
                    identifier = getText(1),
                    eventId = Hex.encode(getBlob(2)),
                    name = getText(3),
                    description = textOrNull(4).orEmpty(),
                    apps = JSONArray(getText(5)).toStringList().mapNotNull(AppCoordinate::parse),
                    updatedAt = getLong(6),
                )
            }
        }
    }

    /** Public stacks listing any of [anchors], newest first. */
    fun stacksListing(anchors: List<AppCoordinate>, limit: Int): List<StackRecord> = read { db ->
        val wanted = anchors.toSet()
        val appIds = wanted.map(AppCoordinate::appId).distinct()
        if (appIds.isEmpty()) return@read emptyList()
        db.prepare(
            "SELECT pubkey, identifier, event_id, name, description, public_apps, updated_at FROM stacks " +
                "WHERE ${appIds.joinToString(" OR ") { "public_apps LIKE ?" }} ORDER BY updated_at DESC",
        ).use { statement ->
            appIds.forEachIndexed { index, appId -> statement.bindText(index + 1, "%:$appId\"%") }
            statement.rows {
                StackRecord(
                    pubkey = Hex.encode(getBlob(0)),
                    identifier = getText(1),
                    eventId = Hex.encode(getBlob(2)),
                    name = getText(3),
                    description = textOrNull(4).orEmpty(),
                    apps = JSONArray(getText(5)).toStringList().mapNotNull(AppCoordinate::parse),
                    updatedAt = getLong(6),
                )
            }.filter { stack -> stack.apps.any(wanted::contains) }.take(limit)
        }
    }

    fun zaps(appId: String, limit: Int? = null): List<ZapRecord> = read { db ->
        val limitSql = limit?.let { " LIMIT $it" } ?: ""
        db.prepare(
            "SELECT event_id, pubkey, amount_sats, created_at, note FROM zaps WHERE app_id = ? ORDER BY created_at DESC$limitSql",
        ).use { statement ->
            statement.bindText(1, appId)
            statement.rows {
                ZapRecord(Hex.encode(getBlob(0)), Hex.encode(getBlob(1)), getLong(2), getLong(3), appId, textOrNull(4).orEmpty())
            }
        }
    }

    fun comments(appId: String, limit: Int? = null): List<CommentRecord> = read { db ->
        val limitSql = limit?.let { " LIMIT $it" } ?: ""
        db.prepare(
            "SELECT event_id, pubkey, created_at, content, root, parent_event_id, version FROM comments " +
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
                    root = textOrNull(4),
                    parentEventId = blobHexOrNull(5),
                    version = textOrNull(6),
                )
            }
        }
    }

    /** Comments whose root coordinate is [root], newest first. Their `i` tag, if any, is not read. */
    fun commentsOnRoot(root: String, limit: Int? = null): List<CommentRecord> = read { db ->
        val limitSql = limit?.let { " LIMIT $it" } ?: ""
        db.prepare(
            "SELECT event_id, pubkey, created_at, content, app_id, parent_event_id FROM comments " +
                "WHERE root = ? ORDER BY created_at DESC$limitSql",
        ).use { statement ->
            statement.bindText(1, root)
            statement.rows {
                CommentRecord(
                    eventId = Hex.encode(getBlob(0)),
                    pubkey = Hex.encode(getBlob(1)),
                    createdAt = getLong(2),
                    content = getText(3),
                    appId = textOrNull(4),
                    root = root,
                    parentEventId = blobHexOrNull(5),
                )
            }
        }
    }

    /** What [pubkey] wrote on apps, newest first. */
    fun commentsBy(pubkey: String, limit: Int): List<CommentRecord> = read { db ->
        db.prepare(
            "SELECT event_id, pubkey, created_at, content, app_id, root, parent_event_id FROM comments " +
                "WHERE pubkey = ? AND app_id IS NOT NULL ORDER BY created_at DESC LIMIT $limit",
        ).use { statement ->
            statement.bindBlob(1, Hex.decode(pubkey))
            statement.rows {
                CommentRecord(
                    eventId = Hex.encode(getBlob(0)),
                    pubkey = Hex.encode(getBlob(1)),
                    createdAt = getLong(2),
                    content = getText(3),
                    appId = textOrNull(4),
                    root = textOrNull(5),
                    parentEventId = blobHexOrNull(6),
                )
            }
        }
    }

    /** Zaps [pubkey] sent, newest first; a receipt's sender is the pubkey of its zap request. */
    fun zapsBy(pubkey: String, limit: Int): List<ZapRecord> = read { db ->
        db.prepare(
            "SELECT event_id, pubkey, amount_sats, created_at, app_id FROM zaps WHERE pubkey = ? ORDER BY created_at DESC LIMIT $limit",
        ).use { statement ->
            statement.bindBlob(1, Hex.decode(pubkey))
            statement.rows { ZapRecord(Hex.encode(getBlob(0)), Hex.encode(getBlob(1)), getLong(2), getLong(3), getText(4)) }
        }
    }

    /** Newest comments on any app, replies included. */
    fun recentComments(limit: Int): List<CommentRecord> = read { db ->
        db.prepare(
            "SELECT event_id, pubkey, created_at, content, app_id, root, parent_event_id FROM comments " +
                "WHERE app_id IS NOT NULL ORDER BY created_at DESC LIMIT $limit",
        ).use { statement ->
            statement.rows {
                CommentRecord(
                    eventId = Hex.encode(getBlob(0)),
                    pubkey = Hex.encode(getBlob(1)),
                    createdAt = getLong(2),
                    content = getText(3),
                    appId = textOrNull(4),
                    root = textOrNull(5),
                    parentEventId = blobHexOrNull(6),
                )
            }
        }
    }

    /** Newest zaps on any app. */
    fun recentZaps(limit: Int): List<ZapRecord> = read { db ->
        db.prepare(
            "SELECT event_id, pubkey, amount_sats, created_at, app_id FROM zaps WHERE app_id IS NOT NULL ORDER BY created_at DESC LIMIT $limit",
        ).use { statement ->
            statement.rows { ZapRecord(Hex.encode(getBlob(0)), Hex.encode(getBlob(1)), getLong(2), getLong(3), getText(4)) }
        }
    }

    // --- publish queue -----------------------------------------------------------------------------

    fun pendingPublishes(): List<Event> = read { db ->
        db.prepare("SELECT event_json FROM publish_queue").use { it.rows { Event.parse(getText(0)) } }
    }

    fun dequeuePublish(eventId: String) {
        write { tx ->
            tx.db.prepare("DELETE FROM publish_queue WHERE event_id = ?").use { statement ->
                statement.bindBlob(1, Hex.decode(eventId))
                statement.step()
            }
            tx.touch(Table.PublishQueue)
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
        // No listener cares about this table, so `touch` is not called and nobody is notified.
        write { tx ->
            tx.db.prepare("INSERT OR REPLACE INTO query_refresh (fingerprint, refreshed_at) VALUES (?, ?)").use { statement ->
                statement.bindText(1, fingerprint)
                statement.bindLong(2, epochMillis)
                statement.step()
            }
        }
    }

    // --- rehydration templates ---------------------------------------------------------------------

    fun catalogRelayListTemplate(deviceSigner: LocalSigner): Pair<List<List<String>>, String> {
        // A catalog whose key is not pinned yet cannot be named; it joins the list once it is.
        val selected = selectedCatalogs().filter { it.manifestPubkey != null }
        fun entry(catalog: CatalogRecord): List<String> =
            listOfNotNull("p", catalog.manifestPubkey, (catalog.hints.firstOrNull() ?: catalog.relayUrl)?.url)
        val tags = selected.filter { !it.isPrivate }.map(::entry)
        val privateEntries = selected.filter { it.isPrivate }.map(::entry)
        val content = if (privateEntries.isEmpty()) {
            ""
        } else {
            deviceSigner.encryptToSelf(JSONArray(privateEntries.map { JSONArray(it) }).toString())
        }
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

    /** The stored tags and content of one stack, or null when it is not stored. */
    fun storedStack(pubkey: String, identifier: String): Pair<List<List<String>>, String>? = read { db ->
        db.prepare("SELECT metadata FROM stacks WHERE pubkey = ? AND identifier = ?").use { statement ->
            statement.bindBlob(1, Hex.decode(pubkey))
            statement.bindText(2, identifier)
            statement.rowOrNull {
                val metadata = JSONObject(it.getText(0))
                metadata.optJSONArray("tags").toStringLists() to metadata.optString("content")
            }
        }
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
        private const val SELECT_CATALOG = CatalogRows.SELECT

        /** A listing is shown once it has a name and the asset's certificate hash. */
        private const val MIN_DEVELOPER_QUERY = 2
        private const val VISIBLE_LISTING = "a.name <> '' AND a.certificate_hash IS NOT NULL"

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
              AND $VISIBLE_LISTING
              AND a.catalog_id = (
                  SELECT a2.catalog_id FROM apps a2 JOIN catalogs c2 ON c2.id = a2.catalog_id
                  WHERE a2.app_id = a.app_id AND c2.position IS NOT NULL
                    AND a2.name <> '' AND a2.certificate_hash IS NOT NULL
                  ORDER BY c2.position ASC LIMIT 1
              )
        """.trimIndent()

        /** Columns [SearchRanker] needs. Full listings are loaded only for the ids it keeps. */
        private val SELECT_SEARCH_KEY = """
            SELECT a.id, a.app_id, a.name, s.vector, a.fact_bits, s.keywords
            FROM apps a
            JOIN catalogs c ON c.id = a.catalog_id
            LEFT JOIN apps_search s ON s.id = a.id
            WHERE c.position IS NOT NULL
              AND $VISIBLE_LISTING
              AND a.catalog_id = (
                  SELECT a2.catalog_id FROM apps a2 JOIN catalogs c2 ON c2.id = a2.catalog_id
                  WHERE a2.app_id = a.app_id AND c2.position IS NOT NULL
                    AND a2.name <> '' AND a2.certificate_hash IS NOT NULL
                  ORDER BY c2.position ASC LIMIT 1
              )
        """.trimIndent()

        fun open(
            path: String,
            openConnection: (String) -> SQLiteConnection = ::openBundled,
            log: IoliteLog = IoliteLog { _, _ -> },
        ): SQLiteConnection {
            File(path).parentFile?.mkdirs()
            // A crash here could not be fixed by an update, since updates come through this database.
            var connection = openConnection(path)
            var version = try {
                connection.userVersion()
            } catch (unreadable: Exception) {
                connection.close()
                log.log("database unreadable; moved aside as .corrupt and starting empty", unreadable)
                moveAside(path, "corrupt")
                connection = openConnection(path)
                connection.userVersion()
            }
            if (version != 0 && version != Schema.USER_VERSION) {
                connection.close()
                log.log("database schema $version is not ${Schema.USER_VERSION}; moved aside as .v$version and starting empty", null)
                moveAside(path, "v$version")
                connection = openConnection(path)
                version = connection.userVersion()
            }
            connection.execSQL("PRAGMA foreign_keys = ON")
            connection.execSQL("PRAGMA journal_mode = WAL")
            connection.execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS")
            if (version == 0) applySchema(connection) { Schema.statements.forEach(connection::execSQL) }
            runCatching {
                connection.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { it.step() }
            }
            return connection
        }

        /** Opened after [open], so the schema is there. Query-only: a write through it fails. */
        private fun openReader(path: String, openConnection: (String) -> SQLiteConnection): SQLiteConnection =
            openConnection(path).apply {
                execSQL("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS")
                execSQL("PRAGMA query_only = ON")
            }

        private const val BUSY_TIMEOUT_MS = 5_000

        private fun SQLiteConnection.userVersion(): Int = prepare("PRAGMA user_version").use { statement ->
            statement.step()
            statement.getLong(0).toInt()
        }

        /**
         * Keeps one copy of a database this build cannot read, named by [label], and clears the path.
         * Icon folders are named by catalog id, which the new database reuses, so they go too.
         */
        private fun moveAside(path: String, label: String) {
            File(File(path).parentFile, "icons").deleteRecursively()
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                val file = File(path + suffix)
                if (!file.exists()) return@forEach
                val kept = File("$path.$label$suffix")
                kept.delete()
                if (!file.renameTo(kept)) file.delete()
            }
        }

        private fun applySchema(connection: SQLiteConnection, create: () -> Unit) {
            connection.execSQL("BEGIN IMMEDIATE")
            try {
                create()
                connection.execSQL("PRAGMA user_version = ${Schema.USER_VERSION}")
                connection.execSQL("COMMIT")
            } catch (failure: Throwable) {
                runCatching { connection.execSQL("ROLLBACK") }
                throw failure
            }
        }
    }
}

internal fun openBundled(path: String): SQLiteConnection = BundledSQLiteDriver().open(path)

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
    certificateHash = blobHexOrNull(3),
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

internal fun <T> SQLiteStatement.rows(map: SQLiteStatement.() -> T): List<T> = buildList {
    while (step()) add(map())
}
