package dev.zapstore.iolite

import androidx.sqlite.SQLiteConnection
import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

class CatalogImportException(message: String) : Exception(message)

/** A catalog delete operation from `deletes.jsonl`. */
sealed interface CatalogDelete {
    data class Listing(val appId: String) : CatalogDelete
    data class Coordinate(val kind: Int, val pubkey: String, val d: String) : CatalogDelete
}

/**
 * Derives `apps` and `certificate_proofs` rows from verified catalog events (kinds 32267, 30063, 3063, 30509).
 * Each listing event writes its own columns. Sibling events are not required.
 */
internal object ListingRows {
    val KINDS = setOf(Kinds.App, Kinds.Release, Kinds.Asset, Kinds.IdentityProof)
    private val LISTING_KINDS = setOf(Kinds.App, Kinds.Release, Kinds.Asset)
    private const val ANDROID_APK = "application/vnd.android.package-archive"

    fun listingAppId(event: Event): String? = when (event.kind) {
        Kinds.App -> event.dTag()
        Kinds.Release, Kinds.Asset -> event.tagValue("i")
        else -> null
    }

    fun isListingKind(kind: Int): Boolean = kind in LISTING_KINDS

    /**
     * Applies whatever listing events arrived for [appId].
     * Each event writes its own columns. A missing sibling leaves those columns as stored.
     */
    fun persistListing(tx: Tx, catalog: CatalogRecord, appId: String, events: List<Event>, device: DeviceProfile, now: Long) {
        val db = tx.db
        val app = events.firstOrNull { it.kind == Kinds.App }
        val release = events.firstOrNull { it.kind == Kinds.Release && it.tagValue("c") == "main" }
        val asset = events
            .filter { it.kind == Kinds.Asset && it.tagValue("i") == appId && assetMatchesDevice(it, device) }
            .maxWithOrNull(compareBy<Event> { it.tagValue("version_code")?.toLongOrNull() ?: -1 }.thenBy { it.id })
        if (app == null && release == null && asset == null) return
        val stored = storedListing(db, catalog.id, appId)
        val certificate = asset?.tagValue("apk_certificate_hash")?.takeIf { Hex.isHex(it, 64) }
            ?: stored?.certificate
            ?: return
        val eventPubkey = app?.pubkey ?: stored?.eventPubkey ?: release?.pubkey ?: asset?.pubkey ?: return
        if (!Hex.isHex(eventPubkey, 64)) return
        val metadata = JSONObject(stored?.metadata ?: "{}").apply {
            if (app != null) putAppMetadata(app)
            if (release != null) putReleaseMetadata(release)
            if (asset != null) putAssetMetadata(asset)
        }
        db.prepare(
            """
            INSERT INTO apps (
                id, catalog_id, app_id, variant_id, certificate_hash, app_event_created_at, pubkey, event_pubkey,
                name, summary, repository, version, version_code, channel, metadata
            ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'main', ?)
            ON CONFLICT(catalog_id, app_id) DO UPDATE SET
                certificate_hash = excluded.certificate_hash,
                app_event_created_at = excluded.app_event_created_at,
                pubkey = excluded.pubkey,
                event_pubkey = excluded.event_pubkey,
                name = excluded.name,
                summary = excluded.summary,
                repository = excluded.repository,
                version = excluded.version,
                version_code = excluded.version_code,
                channel = excluded.channel,
                metadata = excluded.metadata
            """.trimIndent(),
        ).use { statement ->
            statement.bindBlob(1, identity(catalog.relayUrl.url, appId))
            statement.bindLong(2, catalog.id)
            statement.bindText(3, appId)
            statement.bindBlob(4, Hex.decode(certificate))
            statement.bindLong(5, app?.createdAt ?: stored?.createdAt ?: 0)
            statement.bindBlobOrNull(6, currentProofPubkey(db, catalog.id, certificate, now)?.let(Hex::decode))
            statement.bindBlob(7, Hex.decode(eventPubkey))
            statement.bindText(8, app?.tagValue("name")?.takeIf { it.isNotBlank() } ?: stored?.name ?: appId)
            statement.bindText(9, if (app != null) app.tagValue("summary") ?: "" else stored?.summary ?: "")
            statement.bindTextOrNull(10, if (app != null) app.tagValue("repository") else stored?.repository)
            statement.bindText(
                11,
                asset?.tagValue("version")?.takeIf { it.isNotBlank() }
                    ?: stored?.version
                    ?: "",
            )
            statement.bindLong(12, asset?.tagValue("version_code")?.toLongOrNull() ?: stored?.versionCode ?: 0)
            statement.bindText(13, metadata.toString())
            statement.step()
        }
        tx.touch(Table.Apps)
    }

    private data class StoredListing(
        val certificate: String,
        val createdAt: Long,
        val eventPubkey: String,
        val name: String,
        val summary: String,
        val repository: String?,
        val version: String,
        val versionCode: Long,
        val metadata: String,
    )

    private fun storedListing(db: SQLiteConnection, catalogId: Long, appId: String): StoredListing? =
        db.prepare(
            """
            SELECT certificate_hash, app_event_created_at, event_pubkey, name, summary, repository,
                version, version_code, metadata
            FROM apps WHERE catalog_id = ? AND app_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, catalogId)
            statement.bindText(2, appId)
            if (!statement.step()) return null
            StoredListing(
                certificate = Hex.encode(statement.getBlob(0)),
                createdAt = statement.getLong(1),
                eventPubkey = Hex.encode(statement.getBlob(2)),
                name = statement.getText(3),
                summary = statement.getText(4),
                repository = if (statement.isNull(5)) null else statement.getText(5),
                version = statement.getText(6),
                versionCode = statement.getLong(7),
                metadata = statement.getText(8),
            )
        }

    private fun JSONObject.putAppMetadata(app: Event) {
        put("description", app.content)
        put("icon", app.tagValue("icon") ?: "")
        put("url", app.tagValue("url") ?: "")
        put("license", app.tagValue("license") ?: "")
        put("image", JSONArray(app.tagValues("image")))
        put("app_event_id", app.id)
    }

    private fun JSONObject.putReleaseMetadata(release: Event) {
        put("release_event_id", release.id)
        put("release_created_at", release.createdAt)
        put("release_notes", release.content)
    }

    private fun JSONObject.putAssetMetadata(asset: Event) {
        put("asset_event_id", asset.id)
        asset.tagValue("url")?.let { put("download_url", it) }
        asset.tagValue("x")?.takeIf { it.isNotBlank() }?.let { put("apk_hash", it) }
        asset.tagValue("filename")?.let { put("filename", it) }
        asset.tagValue("m")?.let { put("mime", it) }
    }

    fun persistProof(tx: Tx, catalog: CatalogRecord, event: Event) {
        validateProof(event)
        val hash = event.tagValue("d")!!
        val revoked = event.tagCount("revoked") > 0
        val expiry = if (revoked) 0 else event.tagValue("expiry")!!.toLong()
        tx.db.prepare(
            """
            INSERT INTO certificate_proofs (
                id, catalog_id, certificate_hash, pubkey, event_id, created_at, expiry, revoked, delegations
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(catalog_id, certificate_hash, pubkey) DO UPDATE SET
                event_id = excluded.event_id,
                created_at = excluded.created_at,
                expiry = excluded.expiry,
                revoked = excluded.revoked,
                delegations = excluded.delegations
            """.trimIndent(),
        ).use { statement ->
            statement.bindBlob(1, identity(catalog.relayUrl.url, hash, event.pubkey))
            statement.bindLong(2, catalog.id)
            statement.bindBlob(3, Hex.decode(hash))
            statement.bindBlob(4, Hex.decode(event.pubkey))
            statement.bindBlob(5, Hex.decode(event.id))
            statement.bindLong(6, event.createdAt)
            statement.bindLong(7, expiry)
            statement.bindLong(8, if (revoked) 1 else 0)
            statement.bindText(9, JSONArray(event.tagValues("delegation")).toString())
            statement.step()
        }
        tx.touch(Table.Proofs)
    }

    fun delete(tx: Tx, catalog: CatalogRecord, delete: CatalogDelete) {
        when (delete) {
            is CatalogDelete.Listing -> {
                tx.db.prepare("DELETE FROM apps WHERE catalog_id = ? AND app_id = ?").use { statement ->
                    statement.bindLong(1, catalog.id)
                    statement.bindText(2, delete.appId)
                    statement.step()
                }
                tx.touch(Table.Apps)
            }
            is CatalogDelete.Coordinate -> {
                if (delete.kind == Kinds.AppStack) {
                    EventRows.deleteStack(tx, delete.pubkey, delete.d)
                    return
                }
                if (delete.kind != Kinds.IdentityProof) return
                tx.db.prepare(
                    "DELETE FROM certificate_proofs WHERE catalog_id = ? AND pubkey = ? AND certificate_hash = ?",
                ).use { statement ->
                    statement.bindLong(1, catalog.id)
                    statement.bindBlob(2, Hex.decode(delete.pubkey))
                    statement.bindBlob(3, Hex.decode(delete.d))
                    statement.step()
                }
                tx.touch(Table.Proofs)
            }
        }
    }

    /** App IDs stored for [catalog] that are not in [keep]. */
    fun staleAppIds(db: SQLiteConnection, catalog: CatalogRecord, keep: Set<String>): List<String> =
        db.prepare("SELECT app_id FROM apps WHERE catalog_id = ?").use { statement ->
            statement.bindLong(1, catalog.id)
            buildList {
                while (statement.step()) {
                    val appId = statement.getText(0)
                    if (appId !in keep) add(appId)
                }
            }
        }

    /** Re-derives `apps.pubkey` from the currently active proofs. */
    fun recomputeAppPubkeys(tx: Tx, catalogId: Long, now: Long) {
        val db = tx.db
        val rows = db.prepare("SELECT id, certificate_hash, pubkey FROM apps WHERE catalog_id = ?").use { apps ->
            apps.bindLong(1, catalogId)
            buildList {
                while (apps.step()) add(Triple(apps.getBlob(0), Hex.encode(apps.getBlob(1)), apps.blobHexOrNull(2)))
            }
        }
        var changed = false
        for ((id, certificate, stored) in rows) {
            val pubkey = currentProofPubkey(db, catalogId, certificate, now)
            if (pubkey == stored) continue
            db.prepare("UPDATE apps SET pubkey = ? WHERE id = ?").use { update ->
                update.bindBlobOrNull(1, pubkey?.let(Hex::decode))
                update.bindBlob(2, id)
                update.step()
            }
            changed = true
        }
        if (changed) tx.touch(Table.Apps)
    }

    private fun currentProofPubkey(db: SQLiteConnection, catalogId: Long, certificateHash: String, now: Long): String? =
        db.prepare(
            """
            SELECT pubkey FROM certificate_proofs
            WHERE catalog_id = ? AND certificate_hash = ? AND revoked = 0 AND expiry > ?
            ORDER BY created_at DESC, event_id ASC LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, catalogId)
            statement.bindBlob(2, Hex.decode(certificateHash))
            statement.bindLong(3, now)
            if (statement.step()) Hex.encode(statement.getBlob(0)) else null
        }

    private fun identity(vararg parts: String): ByteArray =
        Crypto.sha256(parts.joinToString("\u0000").toByteArray(Charsets.UTF_8))

    // --- validation --------------------------------------------------------------------------------

    private fun assetMatchesDevice(event: Event, device: DeviceProfile): Boolean {
        if (!event.tagValue("variant").isNullOrBlank()) return false
        if (event.tagValue("m") != ANDROID_APK) return false
        val platforms = event.tagValues("f").filter { it.startsWith("android-") }
        if (platforms.isNotEmpty() && platforms.none { it in device.platforms }) return false
        val minSdk = event.tagValue("min_platform_version")?.toIntOrNull()
        return minSdk == null || device.sdk >= minSdk
    }

    fun validateProof(event: Event) {
        if (event.kind != Kinds.IdentityProof) throw CatalogImportException("expected proof")
        if (event.tagCount("d") != 1) throw CatalogImportException("proof must have one d tag")
        val hash = event.tagValue("d") ?: throw CatalogImportException("proof missing d")
        if (!Hex.isHex(hash, 64)) throw CatalogImportException("proof d is not a sha256")
        if (event.tagCount("revoked") > 0) {
            if (event.tagCount("signature") != 0 || event.tagCount("expiry") != 0 ||
                event.tagCount("cert") != 0 || event.tagCount("delegation") != 0
            ) {
                throw CatalogImportException("revocation must omit active tags")
            }
            return
        }
        if (event.tagCount("signature") != 1 || event.tagCount("expiry") != 1) {
            throw CatalogImportException("active proof requires signature and expiry")
        }
        val expiry = event.tagValue("expiry")?.toLongOrNull() ?: throw CatalogImportException("invalid expiry")
        if (expiry <= event.createdAt) throw CatalogImportException("expiry must be after created_at")
        val cert = event.tagValue("cert") ?: return
        val der = Base64.getDecoder().decode(cert)
        if (Hex.encode(Crypto.sha256(der)) != hash) throw CatalogImportException("embedded certificate hash does not match d")
        verifyCertificateKey(der, event, expiry)
    }

    private fun verifyCertificateKey(der: ByteArray, event: Event, expiry: Long) {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der))
        val message = "Verifying at ${event.createdAt} until $expiry that I control the following Nostr public key: ${event.pubkey}"
        val signature = Base64.getDecoder().decode(event.tagValue("signature"))
        val algorithm = when (certificate.publicKey) {
            is RSAPublicKey -> "SHA256withRSA"
            is ECPublicKey -> "SHA256withECDSA"
            else -> throw CatalogImportException("unsupported certificate key type")
        }
        val verifier = Signature.getInstance(algorithm)
        verifier.initVerify(certificate.publicKey)
        verifier.update(message.toByteArray(Charsets.UTF_8))
        if (!verifier.verify(signature)) throw CatalogImportException("invalid certificate-key signature")
    }
}
