package dev.zapstore.iolite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import org.json.JSONArray

/**
 * Row-level writes to `catalogs`. A catalog is identified by its manifest key; `position` is its place
 * in the user's list (NULL when unselected), and removing a catalog deletes its listings with it.
 */
internal object CatalogRows {
    const val SELECT = "SELECT id, relay_url, position, is_private, manifest_pubkey, epoch, endpoints, hints FROM catalogs"

    /** The first clearnet address, or the first one when every address is onion. */
    fun canonical(relays: List<RelayUrl>): RelayUrl? = relays.firstOrNull { !it.isOnion } ?: relays.firstOrNull()

    fun encode(relays: Collection<RelayUrl>): String = JSONArray().apply { relays.forEach { put(it.url) } }.toString()

    fun decode(raw: String): List<RelayUrl> {
        if (raw.isBlank() || raw == "[]") return emptyList()
        val array = JSONArray(raw)
        return List(array.length()) { index -> array.getString(index).normalizeRelayUrl() }
    }

    fun all(db: SQLiteConnection): List<CatalogRecord> =
        db.prepare("$SELECT ORDER BY COALESCE(position, 999999), id").use { it.rows { toCatalog() } }

    fun byPubkey(db: SQLiteConnection, pubkey: String): CatalogRecord? =
        db.prepare("$SELECT WHERE manifest_pubkey = ?").use { statement ->
            statement.bindBlob(1, Hex.decode(pubkey))
            if (statement.step()) statement.toCatalog() else null
        }

    /**
     * Finds the catalog with this key or creates it, unselected. [relays] fill in addresses the row does
     * not have yet; [hints] are kept for later lookups of the catalog's kind 10067. Returns its id.
     */
    fun upsert(tx: Tx, pubkey: String, relays: List<RelayUrl>, hints: List<RelayUrl>, isPrivate: Boolean): Long {
        val db = tx.db
        val existing = byPubkey(db, pubkey)
        if (existing == null) {
            db.prepare(
                """
                INSERT INTO catalogs (relay_url, position, is_private, manifest_pubkey, epoch, endpoints, hints)
                VALUES (?, NULL, ?, ?, 0, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.bindTextOrNull(1, canonical(relays)?.url)
                statement.bindLong(2, if (isPrivate) 1 else 0)
                statement.bindBlob(3, Hex.decode(pubkey))
                statement.bindText(4, encode(relays))
                statement.bindText(5, encode(hints.distinct()))
                statement.step()
            }
            tx.touch(Table.Catalogs)
            return byPubkey(db, pubkey)!!.id
        }
        val mergedHints = (existing.hints + hints).distinct()
        val addresses = existing.relays.ifEmpty { relays }
        if (mergedHints != existing.hints || isPrivate != existing.isPrivate || addresses != existing.relays) {
            db.prepare("UPDATE catalogs SET hints = ?, is_private = ?, endpoints = ?, relay_url = ? WHERE id = ?").use { statement ->
                statement.bindText(1, encode(mergedHints))
                statement.bindLong(2, if (isPrivate) 1 else 0)
                statement.bindText(3, encode(addresses))
                statement.bindTextOrNull(4, canonical(addresses)?.url ?: existing.relayUrl?.url)
                statement.bindLong(5, existing.id)
                statement.step()
            }
            tx.touch(Table.Catalogs)
        }
        return existing.id
    }

    /** Puts [ids] first, in this order, and unselects every other catalog. */
    fun setOrder(tx: Tx, ids: List<Long>) {
        tx.db.execSQL("UPDATE catalogs SET position = NULL")
        ids.forEachIndexed { index, id ->
            tx.db.prepare("UPDATE catalogs SET position = ? WHERE id = ?").use { statement ->
                statement.bindLong(1, index.toLong())
                statement.bindLong(2, id)
                statement.step()
            }
        }
        tx.touch(Table.Catalogs)
    }

    /**
     * Deletes the catalog, which cascades to its listings, search rows and proofs, and the stacks its key
     * signed. The remaining selection keeps its order.
     */
    fun remove(tx: Tx, catalog: CatalogRecord) {
        val kept = all(tx.db).filter { it.position != null && it.id != catalog.id }.map { it.id }
        catalog.manifestPubkey?.let { EventRows.retainCatalogStacks(tx, it, emptySet()) }
        tx.db.prepare("DELETE FROM catalogs WHERE id = ?").use { statement ->
            statement.bindLong(1, catalog.id)
            statement.step()
        }
        setOrder(tx, kept)
        tx.touch(Table.Apps, Table.Proofs, Table.Stacks)
    }
}

internal fun SQLiteStatement.toCatalog(): CatalogRecord {
    val relayUrl = if (isNull(1)) null else getText(1).normalizeRelayUrl()
    val relays = CatalogRows.decode(if (isNull(6)) "[]" else getText(6)).ifEmpty { listOfNotNull(relayUrl) }
    return CatalogRecord(
        id = getLong(0),
        relayUrl = relayUrl,
        relays = relays,
        position = if (isNull(2)) null else getInt(2),
        isPrivate = getLong(3) == 1L,
        manifestPubkey = blobHexOrNull(4),
        epoch = getLong(5),
        hints = CatalogRows.decode(if (isNull(7)) "[]" else getText(7)),
    )
}