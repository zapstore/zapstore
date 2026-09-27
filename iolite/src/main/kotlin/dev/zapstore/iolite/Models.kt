package dev.zapstore.iolite

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Which SQLite tables a commit touched. Observers subscribe to the tables they read. */
enum class Table {
    Catalogs,
    Apps,
    Proofs,
    Profiles,
    Stacks,
    Zaps,
    Comments,
    Preferences,
    Outbox,
}

data class CatalogRecord(
    val id: Long,
    /** Stable catalog identity: the first clearnet endpoint, or the first endpoint when every one is onion. */
    val relayUrl: RelayUrl,
    /** Endpoints from the signed manifest, clearnet and Tor. */
    val relays: List<RelayUrl>,
    val position: Int?,
    val isPrivate: Boolean,
    val manifestPubkey: String?,
    val epoch: Long,
) {
    /** Onion when Tor is in use and one is published; otherwise the first clearnet endpoint. */
    fun syncRelay(useOnion: Boolean): RelayUrl {
        val onion = relays.firstOrNull { it.isOnion }
        if (useOnion && onion != null) return onion
        return relays.firstOrNull { !it.isOnion } ?: relays.first()
    }
}

/** `32267:<pubkey>:<app_id>` as carried by stack `a` tags and release `a` tags. */
data class AppCoordinate(val pubkey: String, val appId: String) {
    override fun toString(): String = "${Kinds.App}:$pubkey:$appId"

    companion object {
        fun parse(value: String): AppCoordinate? {
            val parts = value.split(":", limit = 3)
            if (parts.size != 3 || parts[0] != Kinds.App.toString()) return null
            if (parts[1].isBlank() || parts[2].isBlank()) return null
            return AppCoordinate(parts[1], parts[2])
        }
    }
}

/**
 * One visible catalog listing: the `apps` row for the main channel and no-variant asset.
 * All values come from SQLite; nothing is re-derived from event JSON.
 */
class AppRecord(
    val id: ByteArray,
    val catalogId: Long,
    val appId: String,
    val certificateHash: String,
    /** Kind 32267 timestamp; sorts recently updated lists. */
    val createdAt: Long,
    /** Active NIP-C1 proof owner; null when the certificate has no current proof. */
    val proofPubkey: String?,
    /** Kind 32267 signer. */
    val eventPubkey: String,
    /** The catalog manifest signer; an author equal to it is hidden. */
    private val catalogManifestPubkey: String?,
    val name: String,
    val summary: String,
    val repository: String?,
    val version: String,
    val versionCode: Long,
    val channel: String?,
    private val metadata: JSONObject,
    /** Generated paragraph of what the app does. Bundle member `about`. */
    val about: String = "",
    /** Security paragraph, then one warning per line. Bundle member `security`. */
    val security: String = "",
    /** Scanner fact sheet. Bundle member `facts`. CSV columns are fact, value, and reason. */
    val facts: String = "",
    /** Local WebP from the bundle, when the file is present. */
    val iconFile: File? = null,
) {
    val coordinate: AppCoordinate get() = AppCoordinate(eventPubkey, appId)
    val isVerified: Boolean get() = proofPubkey != null

    /** Publisher to show: proof owner, else app signer, hidden when it is the catalog relay itself. */
    val authorPubkey: String? get() = (proofPubkey ?: eventPubkey).takeUnless { it == catalogManifestPubkey }

    val description: String get() = metadata.optString("description").ifBlank { summary }

    /** Warning lines folded into [security]. Each line starts with a warning mark. */
    val securityWarnings: String
        get() = security.lineSequence().map { it.trim() }.filter { it.startsWith("⚠") }.joinToString("\n")

    /** Security paragraph, without the warning lines. */
    val securityBody: String
        get() = security.lineSequence().filter { !it.trim().startsWith("⚠") }.joinToString("\n").trim()
    val iconUrl: String? get() = metadata.string("icon")
    val screenshots: List<String> get() = metadata.stringList("image")
    val website: String? get() = metadata.string("url")
    val license: String? get() = metadata.string("license")
    val downloadUrl: String? get() = metadata.string("download_url")
    val apkHash: String? get() = metadata.string("apk_hash")
    val releaseNotes: String get() = metadata.optString("release_notes")

    /**
     * [facts] in file order, one row per key.
     * The file is a CSV with columns fact, value, and reason.
     * The sheet repeats a key once per library. A yes wins over a no.
     */
    val factRows: List<AppFact>
        get() = parseFactRows(facts)
    /** Kind 30063 timestamp, in Unix seconds. */
    val releasedAt: Long get() = metadata.optLong("release_created_at", createdAt)

    override fun equals(other: Any?): Boolean = other is AppRecord && id.contentEquals(other.id)
    override fun hashCode(): Int = id.contentHashCode()
    override fun toString(): String = "AppRecord($appId@$version)"
}

/** Reads a facts CSV. A header is required. Repeated keys collapse to one row; a yes wins. */
internal fun parseFactRows(facts: String): List<AppFact> {
    val table = parseCsv(facts)
    if (table.isEmpty()) return emptyList()
    val header = table.first().map { it.trim() }
    val factIdx = header.indexOf("fact")
    val valueIdx = header.indexOf("value")
    val reasonIdx = header.indexOf("reason")
    if (factIdx < 0 || valueIdx < 0) return emptyList()
    val rows = LinkedHashMap<String, AppFact>()
    for (record in table.drop(1)) {
        val key = record.getOrNull(factIdx)?.trim().orEmpty()
        if (key.isEmpty()) continue
        val yes = when (record.getOrNull(valueIdx)?.trim()) {
            "yes" -> true
            "no" -> false
            else -> continue
        }
        val reason = if (reasonIdx < 0) "" else record.getOrNull(reasonIdx)?.trim().orEmpty()
        val previous = rows[key]
        rows[key] = AppFact(
            key = key,
            yes = previous?.yes == true || yes,
            reason = reason.ifBlank { previous?.reason.orEmpty() },
        )
    }
    return rows.values.toList()
}

internal fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<MutableList<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
        val char = text[i]
        if (quoted) {
            if (char == '"') {
                if (i + 1 < text.length && text[i + 1] == '"') {
                    field.append('"')
                    i += 2
                    continue
                }
                quoted = false
            } else {
                field.append(char)
            }
            i++
            continue
        }
        when (char) {
            '"' -> quoted = true
            ',' -> {
                row.add(field.toString())
                field.clear()
            }
            '\n' -> {
                row.add(field.toString())
                field.clear()
                rows.add(row)
                row = mutableListOf()
            }
            '\r' -> Unit
            else -> field.append(char)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
        row.add(field.toString())
        rows.add(row)
    }
    return rows
}

/** One scanner fact. [reason] is empty when the facts file has no phrase for this key. */
data class AppFact(
    val key: String,
    val yes: Boolean,
    val reason: String = "",
)

/** Kind 0 projection. `name`/`displayName` are already trimmed to non-blank. */
data class ProfileRecord(
    val pubkey: String,
    val updatedAt: Long,
    val eventId: String,
    val name: String?,
    val displayName: String?,
    val about: String?,
    val picture: String?,
    val banner: String?,
    val website: String?,
    val nip05: String?,
) {
    val displayNameOrNpub: String get() = displayName ?: name ?: pubkey.toNpub()
}

/** Kind 30267 projection. Private (device-encrypted) coordinates are already decrypted. */
data class StackRecord(
    val pubkey: String,
    val identifier: String,
    val eventId: String,
    val name: String,
    val description: String,
    val apps: List<AppCoordinate>,
    val updatedAt: Long,
) {
    val appIds: List<String> get() = apps.map(AppCoordinate::appId)
}

/** Kind 9735 projection. */
data class ZapRecord(
    val eventId: String,
    val pubkey: String,
    val amountSats: Long,
    val createdAt: Long,
    val appId: String,
)

/** Kind 1111 projection. */
data class CommentRecord(
    val eventId: String,
    val pubkey: String,
    val createdAt: Long,
    val content: String,
    val appId: String?,
    val stack: String?,
    val parentEventId: String?,
)

data class DeviceProfile(
    val abis: List<String>,
    val sdk: Int,
) {
    /** `android-<abi>` platform tags as used by NIP-82 `f` tags. */
    val platforms: List<String> get() = abis.map { if (it.startsWith("android-")) it else "android-$it" }
}

data class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray?,
)

fun interface HttpTransport {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

interface WebSocketSession {
    fun send(text: String)
    fun close()
}

interface WebSocketListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosing(code: Int, reason: String)
    fun onFailure(error: Throwable)
}

fun interface WebSocketFactory {
    fun open(url: String, listener: WebSocketListener): WebSocketSession
}

internal fun JSONObject.string(key: String): String? = optString(key).takeIf { it.isNotBlank() }

internal fun JSONObject.stringList(key: String): List<String> = optJSONArray(key).toStringList()

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { index -> optString(index) }.filter { it.isNotBlank() }
}
