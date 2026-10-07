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
    /** Canonical endpoint: the first clearnet endpoint, or the first endpoint when every one is onion. */
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
    /** Kind 3063 `apk_certificate_hash`. Null until an asset is stored. */
    val certificateHash: String?,
    /** Kind 32267 timestamp; sorts recently updated lists. */
    val createdAt: Long,
    /** Active NIP-C1 proof owner; null when the certificate has no current proof. */
    val proofPubkey: String?,
    /** Listing signer: the app event, or the release or asset that created the row. */
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
    /** Generated paragraph of what the app does. Bundle member `about`. Absent when the note did not change. */
    val about: String = "",
    /**
     * Bundle member `security`. A current file is the fact CSV, a line of permission ids,
     * then the prose, separated by lines that are only `---`.
     * An older file is notices, a `---` line, then the paragraph.
     */
    val security: String = "",
    /** Fact CSV. Filled from [security] on import. Older catalogs stored this as its own member. */
    val facts: String = "",
    /** Local WebP from the bundle, when the file is present. */
    val iconFile: File? = null,
) {
    val coordinate: AppCoordinate get() = AppCoordinate(eventPubkey, appId)
    val isVerified: Boolean get() = proofPubkey != null

    /** Publisher to show: proof owner, else app signer, hidden when it is the catalog relay itself. */
    val authorPubkey: String? get() = (proofPubkey ?: eventPubkey).takeUnless { it == catalogManifestPubkey }

    val description: String get() = metadata.optString("description").ifBlank { summary }

    /** Notices before a line that is only `---`. Empty when the prose has no separator. */
    val securityWarnings: String
        get() = proseSections(securityProse()).first

    /** Paragraph after a `---` line, or the whole prose when there is no separator. */
    val securityBody: String
        get() = proseSections(securityProse()).second

    /** Permission ids from the security file, such as INTERNET or READ_SMS. */
    val permissionIds: List<String>
        get() {
            val doc = parseSecurityDocument(security)
            if (!doc.combined) return emptyList()
            return doc.permissions.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }

    private fun securityProse(): String {
        val doc = parseSecurityDocument(security)
        return if (doc.combined) doc.prose else security.trim()
    }
    val iconUrl: String? get() = metadata.string("icon")
    val screenshots: List<String> get() = metadata.stringList("image")
    val website: String? get() = metadata.string("url")
    val license: String? get() = metadata.string("license")
    val downloadUrl: String? get() = metadata.string("download_url")
    val apkHash: String? get() = metadata.string("apk_hash")
    val releaseNotes: String get() = metadata.optString("release_notes")

    /**
     * [facts] in file order, one row per key.
     * The file is a quoted CSV with no header. Columns are fact, value, and notes.
     * The sheet repeats a key once per library. A yes wins over a no.
     */
    val factRows: List<AppFact>
        get() {
            val doc = parseSecurityDocument(security)
            return parseFactRows(if (doc.combined) doc.csv else facts)
        }
    /** Kind 30063 timestamp, in Unix seconds. */
    val releasedAt: Long get() = metadata.optLong("release_created_at", createdAt)

    override fun equals(other: Any?): Boolean = other is AppRecord && id.contentEquals(other.id)
    override fun hashCode(): Int = id.contentHashCode()
    override fun toString(): String = "AppRecord($appId@$version)"
}

/** Fact CSV, permission ids, and prose from one security file. */
internal data class SecurityDocument(
    val csv: String,
    val permissions: String,
    val prose: String,
    val combined: Boolean,
)

/**
 * A current security file starts with a quoted CSV row and has two `---` lines.
 * The text after the second `---` is the prose, which may contain another `---`.
 * Anything else is an older prose note.
 */
internal fun parseSecurityDocument(text: String): SecurityDocument {
    val lines = text.trim().lines()
    if (lines.isEmpty()) return SecurityDocument("", "", "", false)
    val marks = lines.indices.filter { lines[it].trim() == "---" }
    if (marks.size >= 2 && lines.first().trim().startsWith("\"")) {
        val csv = lines.take(marks[0]).joinToString("\n").trim()
        val perms = lines.subList(marks[0] + 1, marks[1]).joinToString("\n").trim()
        val prose = lines.drop(marks[1] + 1).joinToString("\n").trim()
        return SecurityDocument(csv, perms, prose, true)
    }
    return SecurityDocument("", "", text.trim(), false)
}

/** Fact CSV inside a current security file, or null when the text is an older note. */
internal fun securityFactsCsv(text: String): String? {
    val doc = parseSecurityDocument(text)
    return if (doc.combined) doc.csv else null
}

private fun proseSections(text: String): Pair<String, String> {
    val lines = text.lines()
    val split = lines.indexOfFirst { it.trim() == "---" }
    if (split < 0) return "" to text.trim()
    val notices = lines.take(split).joinToString("\n").trim()
    val body = lines.drop(split + 1).joinToString("\n").trim()
    return notices to body
}

/** Reads a facts CSV. Columns are fact, value, notes. A header row is ignored. Repeated keys collapse to one row; a yes wins. */
internal fun parseFactRows(facts: String): List<AppFact> {
    val table = parseCsv(facts)
    if (table.isEmpty()) return emptyList()
    val rows = LinkedHashMap<String, AppFact>()
    for (record in table) {
        val key = record.getOrNull(0)?.trim().orEmpty()
        if (key.isEmpty() || key.equals("fact", ignoreCase = true)) continue
        val yes = when (record.getOrNull(1)?.trim()) {
            "yes" -> true
            "no" -> false
            else -> continue // unknown is not a pill, so a missing open_source is not Closed source
        }
        val notes = record.getOrNull(2)?.trim().orEmpty()
        val previous = rows[key]
        rows[key] = AppFact(
            key = key,
            yes = previous?.yes == true || yes,
            reason = notes.ifBlank { previous?.reason.orEmpty() },
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

/** One scanner fact. [reason] is the notes column, empty when the facts file has no phrase for this key. */
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
