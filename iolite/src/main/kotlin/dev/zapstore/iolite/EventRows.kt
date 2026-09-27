package dev.zapstore.iolite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import org.json.JSONArray
import org.json.JSONObject

/** Per-process facts every ingest needs: which device this is and how to decrypt device-private payloads. */
internal class IngestContext(
    val device: DeviceProfile,
    val deviceSigner: LocalSigner?,
)

/**
 * Derives SQLite rows from verified relay events (kinds 0, 10002, 30267, 9735, 1111, 10067, 30078).
 * Event JSON is not retained beyond what rehydration needs.
 */
internal object EventRows {
    /** Key of the `preferences` row that stores the device's kind 10067. */
    const val CATALOG_RELAY_LIST_KEY = "\u000010067"
    val STACK_MODELED_TAGS = setOf("d", "name", "description", "a", "f")

    /** Writes [event] if it is admissible and newer than the stored row. Returns whether a row changed. */
    fun persist(tx: Tx, event: Event, context: IngestContext): Boolean {
        val db = tx.db
        return when (event.kind) {
            Kinds.Profile -> ifNewer(event, profileVersion(db, event.pubkey)) {
                upsertProfile(db, event)
                tx.touch(Table.Profiles)
            }
            Kinds.RelayList -> ifNewer(event, relayListVersion(db, event.pubkey)) {
                upsertRelayList(db, event)
                tx.touch(Table.Profiles)
            }
            Kinds.AppStack -> {
                val identifier = event.dTag() ?: return false
                if (!stackMatchesDevice(event, context.device)) return false
                ifNewer(event, stackVersion(db, event.pubkey, identifier)) {
                    upsertStack(db, event, identifier, context.deviceSigner)
                    tx.touch(Table.Stacks)
                }
            }
            Kinds.Zap -> {
                val appId = zapAppId(event) ?: return false
                val amount = zapAmountSats(event)
                if (amount <= 0) return false
                insertZap(db, event, appId, amount)
                tx.touch(Table.Zaps)
                true
            }
            Kinds.Comment -> {
                insertComment(db, event)
                tx.touch(Table.Comments)
                true
            }
            Kinds.CatalogRelayList -> {
                if (context.deviceSigner?.publicKey != event.pubkey) return false
                ifNewer(event, preferenceVersion(db, CATALOG_RELAY_LIST_KEY)) {
                    upsertCatalogRelayList(db, event, context.deviceSigner)
                    tx.touch(Table.Catalogs, Table.Preferences)
                }
            }
            Kinds.Preferences -> {
                val identifier = event.dTag() ?: return false
                if (isCatalogManifest(event)) return false
                ifNewer(event, preferenceVersion(db, identifier)) {
                    upsertPreference(db, event, identifier)
                    tx.touch(Table.Preferences)
                }
            }
            else -> false
        }
    }

    fun insertOutbox(tx: Tx, event: Event) {
        tx.db.prepare("INSERT OR REPLACE INTO outbox (event_id, event_json) VALUES (?, ?)").use { statement ->
            statement.bindBlob(1, Hex.decode(event.id))
            statement.bindText(2, event.toJson())
            statement.step()
        }
        tx.touch(Table.Outbox)
    }

    // --- replaceable-event versions -------------------------------------------------------------

    private class Version(val createdAt: Long, val id: String)

    private inline fun ifNewer(event: Event, current: Version?, write: () -> Unit): Boolean {
        if (current != null && !supersedes(event.createdAt, event.id, current.createdAt, current.id)) return false
        write()
        return true
    }

    private fun profileVersion(db: SQLiteConnection, pubkey: String): Version? =
        profileMetadata(db, pubkey)?.let { (updatedAt, metadata) ->
            metadata.string("id")?.let { Version(updatedAt, it) }
        }

    private fun relayListVersion(db: SQLiteConnection, pubkey: String): Version? =
        profileMetadata(db, pubkey)?.let { (_, metadata) ->
            metadata.string("nip65_id")?.let { Version(metadata.optLong("nip65_created_at"), it) }
        }

    private fun stackVersion(db: SQLiteConnection, pubkey: String, identifier: String): Version? =
        db.prepare("SELECT updated_at, event_id FROM stacks WHERE pubkey = ? AND identifier = ?").use { statement ->
            statement.bindBlob(1, Hex.decode(pubkey))
            statement.bindText(2, identifier)
            if (statement.step()) Version(statement.getLong(0), Hex.encode(statement.getBlob(1))) else null
        }

    private fun preferenceVersion(db: SQLiteConnection, identifier: String): Version? =
        db.prepare("SELECT updated_at, metadata FROM preferences WHERE identifier = ?").use { statement ->
            statement.bindText(1, identifier)
            if (!statement.step()) return null
            JSONObject(statement.getText(1)).string("id")?.let { Version(statement.getLong(0), it) }
        }

    /** `(updated_at, metadata)` of a profile row, or null. */
    fun profileMetadata(db: SQLiteConnection, pubkey: String): Pair<Long, JSONObject>? =
        db.prepare("SELECT updated_at, metadata FROM profiles WHERE pubkey = ?").use { statement ->
            statement.bindBlob(1, Hex.decode(pubkey))
            if (statement.step()) statement.getLong(0) to JSONObject(statement.getText(1)) else null
        }

    // --- writers -----------------------------------------------------------------------------------

    private fun upsertProfile(db: SQLiteConnection, event: Event) {
        val content = runCatching { JSONObject(event.content) }.getOrElse { JSONObject() }
        val metadata = profileMetadata(db, event.pubkey)?.second ?: JSONObject()
        metadata.put("id", event.id)
        metadata.put("content", event.content)
        db.prepare(
            """
            INSERT INTO profiles (pubkey, updated_at, name, picture_url, metadata)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(pubkey) DO UPDATE SET
                updated_at = excluded.updated_at,
                name = excluded.name,
                picture_url = excluded.picture_url,
                metadata = excluded.metadata
            """.trimIndent(),
        ).use { statement ->
            statement.bindBlob(1, Hex.decode(event.pubkey))
            statement.bindLong(2, event.createdAt)
            statement.bindTextOrNull(3, content.string("name"))
            statement.bindTextOrNull(4, content.string("picture") ?: content.string("image"))
            statement.bindText(5, metadata.toString())
            statement.step()
        }
    }

    private fun upsertRelayList(db: SQLiteConnection, event: Event) {
        val existing = profileMetadata(db, event.pubkey)
        val metadata = existing?.second ?: JSONObject()
        metadata.put("nip65_id", event.id)
        metadata.put("nip65_created_at", event.createdAt)
        metadata.put("nip65_tags", event.tags.toJsonArray())
        db.prepare(
            """
            INSERT INTO profiles (pubkey, updated_at, name, picture_url, metadata)
            VALUES (?, ?, NULL, NULL, ?)
            ON CONFLICT(pubkey) DO UPDATE SET metadata = excluded.metadata
            """.trimIndent(),
        ).use { statement ->
            statement.bindBlob(1, Hex.decode(event.pubkey))
            statement.bindLong(2, existing?.first ?: event.createdAt)
            statement.bindText(3, metadata.toString())
            statement.step()
        }
    }

    private fun insertZap(db: SQLiteConnection, event: Event, appId: String, amountSats: Long) {
        db.prepare(
            """
            INSERT OR IGNORE INTO zaps (event_id, pubkey, amount_sats, created_at, app_id, metadata)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.bindBlob(1, Hex.decode(event.id))
            statement.bindBlob(2, Hex.decode(event.pubkey))
            statement.bindLong(3, amountSats)
            statement.bindLong(4, event.createdAt)
            statement.bindText(5, appId)
            statement.bindText(6, JSONObject().put("tags", event.tags.toJsonArray()).toString())
            statement.step()
        }
    }

    private fun insertComment(db: SQLiteConnection, event: Event) {
        val appId = event.tagValue("i")
            ?: event.tagValue("a")?.let { AppCoordinate.parse(it)?.appId }
            ?: event.tagValue("A")?.let { AppCoordinate.parse(it)?.appId }
        db.prepare(
            """
            INSERT OR IGNORE INTO comments (
                event_id, pubkey, created_at, content, app_id, stack, parent_event_id, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.bindBlob(1, Hex.decode(event.id))
            statement.bindBlob(2, Hex.decode(event.pubkey))
            statement.bindLong(3, event.createdAt)
            statement.bindText(4, event.content)
            statement.bindTextOrNull(5, appId)
            statement.bindTextOrNull(6, event.tagValue("A"))
            statement.bindBlobOrNull(7, event.tagValue("e")?.let(Hex::decode))
            statement.bindText(8, JSONObject().put("tags", event.tags.toJsonArray()).toString())
            statement.step()
        }
    }

    private fun upsertStack(db: SQLiteConnection, event: Event, identifier: String, deviceSigner: LocalSigner?) {
        val metadata = JSONObject()
            .put("tags", event.tags.toJsonArray())
            .put("content", event.content)
            .put("private_apps", JSONArray(decryptJsonArray(event.content, deviceSigner)))
        db.prepare(
            """
            INSERT INTO stacks (pubkey, identifier, event_id, name, description, public_apps, updated_at, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(pubkey, identifier) DO UPDATE SET
                event_id = excluded.event_id,
                name = excluded.name,
                description = excluded.description,
                public_apps = excluded.public_apps,
                updated_at = excluded.updated_at,
                metadata = excluded.metadata
            """.trimIndent(),
        ).use { statement ->
            statement.bindBlob(1, Hex.decode(event.pubkey))
            statement.bindText(2, identifier)
            statement.bindBlob(3, Hex.decode(event.id))
            statement.bindText(4, event.tagValue("name") ?: identifier)
            statement.bindTextOrNull(5, event.tagValue("description"))
            statement.bindText(6, JSONArray(event.tagValues("a")).toString())
            statement.bindLong(7, event.createdAt)
            statement.bindText(8, metadata.toString())
            statement.step()
        }
    }

    private fun upsertPreference(db: SQLiteConnection, event: Event, identifier: String) {
        upsertPreferenceRow(db, identifier, event)
    }

    private fun upsertCatalogRelayList(db: SQLiteConnection, event: Event, deviceSigner: LocalSigner?) {
        val publicUrls = event.tagValues("r").mapNotNull { runCatching { it.normalizeRelayUrl() }.getOrNull() }
        val privateUrls = decryptJsonArray(event.content, deviceSigner)
            .mapNotNull { runCatching { it.normalizeRelayUrl() }.getOrNull() }
        db.execSQL("UPDATE catalogs SET position = NULL")
        val seen = HashSet<String>()
        var position = 0
        fun select(url: RelayUrl, isPrivate: Boolean) {
            if (!seen.add(url.url)) return
            db.prepare(
                """
                INSERT INTO catalogs (relay_url, position, is_private, epoch)
                VALUES (?, ?, ?, 0)
                ON CONFLICT(relay_url) DO UPDATE SET
                    position = excluded.position,
                    is_private = excluded.is_private
                """.trimIndent(),
            ).use { statement ->
                statement.bindText(1, url.url)
                statement.bindLong(2, position.toLong())
                statement.bindLong(3, if (isPrivate) 1 else 0)
                statement.step()
            }
            position++
        }
        publicUrls.forEach { select(it, false) }
        privateUrls.forEach { select(it, true) }
        upsertPreferenceRow(db, CATALOG_RELAY_LIST_KEY, event)
    }

    private fun upsertPreferenceRow(db: SQLiteConnection, identifier: String, event: Event) {
        val metadata = JSONObject()
            .put("id", event.id)
            .put("pubkey", event.pubkey)
            .put("content", event.content)
            .put("tags", event.tags.toJsonArray())
        db.prepare(
            """
            INSERT INTO preferences (identifier, updated_at, metadata)
            VALUES (?, ?, ?)
            ON CONFLICT(identifier) DO UPDATE SET
                updated_at = excluded.updated_at,
                metadata = excluded.metadata
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, identifier)
            statement.bindLong(2, event.createdAt)
            statement.bindText(3, metadata.toString())
            statement.step()
        }
    }

    // --- event helpers -----------------------------------------------------------------------------

    fun stackMatchesDevice(event: Event, device: DeviceProfile): Boolean {
        val platforms = event.tagValues("f").filter { it.startsWith("android-") }
        return platforms.isEmpty() || platforms.any { it in device.platforms }
    }

    fun deleteStack(tx: Tx, pubkey: String, identifier: String) {
        tx.db.prepare("DELETE FROM stacks WHERE pubkey = ? AND identifier = ?").use { statement ->
            statement.bindBlob(1, Hex.decode(pubkey))
            statement.bindText(2, identifier)
            statement.step()
        }
        tx.touch(Table.Stacks)
    }

    /** Drops catalog stacks whose identifier is not in [keep]. Other authors stay. */
    fun retainCatalogStacks(tx: Tx, pubkey: String, keep: Set<String>) {
        val stale = tx.db.prepare("SELECT identifier FROM stacks WHERE pubkey = ?").use { statement ->
            statement.bindBlob(1, Hex.decode(pubkey))
            buildList {
                while (statement.step()) {
                    val identifier = statement.getText(0)
                    if (identifier !in keep) add(identifier)
                }
            }
        }
        stale.forEach { deleteStack(tx, pubkey, it) }
    }

    private fun zapAppId(event: Event): String? =
        event.tagValue("i") ?: event.tagValue("a")?.let { AppCoordinate.parse(it)?.appId }

    private fun zapAmountSats(event: Event): Long {
        event.tagValue("bolt11")?.let { invoice ->
            val amount = Bolt11.amountSats(invoice)
            if (amount > 0) return amount
        }
        return (event.tagValue("amount")?.toLongOrNull() ?: 0) / 1_000
    }

    fun isCatalogManifest(event: Event): Boolean =
        event.kind == Kinds.Preferences &&
            event.tagValue("from") != null &&
            event.tagValue("to") != null &&
            event.tagValue("v") != null

    fun decryptJsonArray(content: String, deviceSigner: LocalSigner?): List<String> {
        if (content.isBlank() || deviceSigner == null) return emptyList()
        return runCatching { JSONArray(deviceSigner.decryptFromSelf(content)).toStringList() }.getOrDefault(emptyList())
    }

    fun leftoverTags(stored: List<List<String>>, modeled: Set<String>): List<List<String>> =
        stored.filter { tag -> tag.firstOrNull() !in modeled }
}

internal fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

internal fun SQLiteStatement.bindBlobOrNull(index: Int, value: ByteArray?) {
    if (value == null) bindNull(index) else bindBlob(index, value)
}

internal fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

internal fun SQLiteStatement.blobHexOrNull(index: Int): String? = if (isNull(index)) null else Hex.encode(getBlob(index))
