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
    PublishQueue,
    RelayReports,
}

data class CatalogRecord(
    val id: Long,
    /**
     * Canonical endpoint: the first clearnet endpoint, or the first endpoint when every one is onion.
     * Null while the catalog's addresses are not known yet.
     */
    val relayUrl: RelayUrl?,
    /** Clearnet and Tor addresses for this catalog. */
    val relays: List<RelayUrl>,
    val position: Int?,
    val isPrivate: Boolean,
    val manifestPubkey: String?,
    val epoch: Long,
    /** Relays that may hold this catalog's kind 10067, asked when [relays] is empty. */
    val hints: List<RelayUrl> = emptyList(),
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
 * One visible catalog listing: the `apps` row for the main channel and its selected asset.
 * All values come from SQLite; nothing is re-derived from event JSON.
 */
/** A stored kind 30509. [createdAt], [expiry], and [pubkey] make up the signed proof message. */
data class CertificateProof(
    val pubkey: String,
    val certificateHash: String,
    val createdAt: Long,
    val expiry: Long,
    val revoked: Boolean,
    /** Padded base64 of the certificate-key signature; empty on a revocation. */
    val signature: String,
) {
    fun isActive(nowSeconds: Long): Boolean = !revoked && nowSeconds < expiry

    /** The exact UTF-8 message the certificate key signed. */
    fun message(): String =
        "Verifying at $createdAt until $expiry that I control the following Nostr public key: $pubkey"
}

/** Who the app page header names on the second line. */
enum class AppByline {
    /** A C1 proof owner. */
    Verified,
    /** The listing signer, with no proof the app is theirs. */
    Unverified,
    /** The catalog key signed the listing. */
    Indexed,
}

class AppRecord(
    val id: ByteArray,
    val catalogId: Long,
    val appId: String,
    /** Kind 3063 `apk_certificate_hash`, the current signer. Null until an asset is stored. */
    val certificateHash: String?,
    /** Kind 32267 timestamp; sorts recently updated lists. */
    val createdAt: Long,
    /** Newest current NIP-C1 proof for [certificateHash]. An ancestor proof does not count. */
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

    /**
     * Where comments, zaps, and reports live (`specs/catalog.md`): the catalog coordinate, which
     * writes use, then the active C1 owner or else the listing signer. A repeated pubkey is dropped.
     */
    val catalogAnchor: AppCoordinate? get() = catalogManifestPubkey?.let { AppCoordinate(it, appId) }
    /** Who a zap pays: the C1 owner, or else the catalog that lists the app. */
    val zapRecipient: String? get() = proofPubkey ?: catalogManifestPubkey
    val anchors: List<AppCoordinate>
        get() = listOfNotNull(catalogManifestPubkey, eventPubkey).distinct().map { AppCoordinate(it, appId) }

    /**
     * Whose NIP-65 inboxes also carry this app's comments and zaps: the catalog key, and the C1
     * owner or else the listing signer. The C1 owner is read because zaps are paid to it.
     */
    val socialLists: List<String>
        get() = listOfNotNull(catalogManifestPubkey, proofPubkey ?: eventPubkey).distinct()

    /** Pubkeys a zap for this app is paid to: the catalog key, the listing signer, the C1 owner. */
    val zapRecipients: List<String>
        get() = listOfNotNull(catalogManifestPubkey, eventPubkey, proofPubkey).distinct()
    val isVerified: Boolean get() = proofPubkey != null
    /** The catalog key signed this listing: Zapstore indexed it, the developer did not publish it. */
    val isCatalogSigned: Boolean get() = catalogManifestPubkey != null && eventPubkey == catalogManifestPubkey

    /** Signer, then `apk_certificate_ancestor` from metadata, oldest first. */
    fun certificateChain(): List<String> {
        val signer = certificateHash ?: return emptyList()
        return buildList {
            add(signer)
            for (hash in metadata.stringList("apk_certificate_ancestor")) {
                if (Hex.isHex(hash, 64) && hash != signer) add(hash)
            }
        }
    }

    /** The C1 owner of the current certificate, the only claim of authorship. Null without a current proof. */
    val authorPubkey: String? get() = proofPubkey?.takeUnless { it == catalogManifestPubkey }

    /**
     * Who the header names. A proof owner, else the listing signer when that is not the catalog,
     * else the indexer.
     */
    val byline: AppByline
        get() = when {
            authorPubkey != null -> AppByline.Verified
            !isCatalogSigned -> AppByline.Unverified
            else -> AppByline.Indexed
        }

    /** The pubkey [byline] names. Null only when an indexed row has no catalog key. */
    val bylinePubkey: String?
        get() = when (byline) {
            AppByline.Verified -> authorPubkey
            AppByline.Unverified -> eventPubkey
            AppByline.Indexed -> catalogManifestPubkey
        }

    val description: String get() = metadata.optString("description").ifBlank { summary }

    /** The listing event's own text. Empty when the developer published none. Does not fall back to [summary]. */
    val listing: String get() = metadata.optString("description")

    /**
     * One line for listing rows: the first paragraph of the generated about,
     * else of the developer's [listing] description, else the listing's [summary] tag.
     */
    val tagline: String by lazy(LazyThreadSafetyMode.NONE) {
        plainParagraph(aboutProse).ifEmpty { plainParagraph(listing) }.ifEmpty { summary.trim() }
    }

    /** The generated about, trimmed. */
    val aboutProse: String get() = about.trim()

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

    /** Notes by fact name. A permission's reason is under its fact, such as `camera`. */
    val securityNotes: Map<String, String>
        get() {
            val doc = parseSecurityDocument(security)
            return doc.notes.ifEmpty {
                parseFactRows(doc.csv).filter { it.reason.isNotBlank() }.associate { it.key to it.reason }
            }
        }

    /** Problems to show next to the facts. An unknown id is kept; the app decides what it shows. */
    val securityFlags: List<SecurityFlag>
        get() = parseSecurityDocument(security).flags

    private fun securityProse(): String = parseSecurityDocument(security).prose
    val iconUrl: String? get() = metadata.string("icon")
    val screenshots: List<String> get() = metadata.stringList("image")

    /** The listing's `t` tags, lowercase. Empty for a listing stored before tags were kept. */
    val tags: List<String> get() = metadata.stringList("tag")
    val website: String? get() = metadata.string("url")
    val license: String? get() = metadata.string("license")
    val downloadUrl: String? get() = metadata.string("download_url")
    val apkHash: String? get() = metadata.string("apk_hash")
    val releaseNotes: String get() = metadata.optString("release_notes")
    /** The release APK's byte count, from the asset's `size` tag. Zero when unknown. */
    val sizeBytes: Long get() = metadata.optLong("size", 0L)
    /** `sizeBytes` as "1.2 MB", "840 KB", or "900 B". Empty when [sizeBytes] is zero. */
    val sizeLabel: String get() = sizeBytes.takeIf { it > 0 }?.let(::formatBytes).orEmpty()

    /**
     * Fact rows from the security file, in file order, one row per key.
     * The CSV has no header. Columns are fact, value, and notes.
     * The sheet repeats a key once per library. A yes wins over a no.
     */
    val factRows: List<AppFact>
        get() = parseFactRows(parseSecurityDocument(security).csv)
    /** Kind 30063 timestamp, in Unix seconds. */
    val releasedAt: Long get() = metadata.optLong("release_created_at", createdAt)

    override fun equals(other: Any?): Boolean = other is AppRecord && id.contentEquals(other.id)
    override fun hashCode(): Int = id.contentHashCode()
    override fun toString(): String = "AppRecord($appId@$version)"
}

/** A problem the enricher wants shown next to the facts, such as `source_mismatch`. */
data class SecurityFlag(val id: String, val note: String)

/**
 * Fact CSV, permission ids, and prose from one security file.
 * A dash-separated file has no keywords, notes object, or flags.
 */
internal data class SecurityDocument(
    val csv: String,
    val permissions: String,
    val prose: String,
    val combined: Boolean,
    val keywords: List<String> = emptyList(),
    val notes: Map<String, String> = emptyMap(),
    val flags: List<SecurityFlag> = emptyList(),
)

internal const val SOURCE_AVAILABLE = "source_available"

/** Older files named [SOURCE_AVAILABLE] this. */
private const val OPEN_SOURCE = "open_source"

private val pillKeys = listOf(
    "google_services",
    "tracking",
    "ads",
    "offline_capable",
    SOURCE_AVAILABLE,
)

/**
 * A security file is one JSON object, or the older dash-separated text.
 * That older text starts with a quoted CSV row and has two `---` lines.
 * The text after the second `---` is the prose, which may contain another `---`.
 * Anything else is not a security file.
 */
internal fun parseSecurityDocument(text: String): SecurityDocument {
    val trimmed = text.trim()
    if (trimmed.startsWith("{")) return parseSecurityJson(trimmed)
    val lines = trimmed.lines()
    if (lines.isEmpty()) return SecurityDocument("", "", "", false)
    val marks = lines.indices.filter { lines[it].trim() == "---" }
    if (marks.size >= 2 && lines.first().trim().startsWith("\"")) {
        val csv = lines.take(marks[0]).joinToString("\n").trim()
        val perms = lines.subList(marks[0] + 1, marks[1]).joinToString("\n").trim()
        val prose = lines.drop(marks[1] + 1).joinToString("\n").trim()
        return SecurityDocument(csv, perms, prose, true)
    }
    return SecurityDocument("", "", "", false)
}

private fun parseSecurityJson(text: String): SecurityDocument {
    val obj = try {
        JSONObject(text)
    } catch (_: Exception) {
        return SecurityDocument("", "", "", false)
    }
    val facts = obj.optJSONObject("facts")
    val notes = obj.optJSONObject("notes")
    val csv = buildString {
        for (key in pillKeys) {
            val stored = if (key == SOURCE_AVAILABLE && facts?.has(key) != true) OPEN_SOURCE else key
            if (facts == null || facts.isNull(stored)) continue
            val value = facts.opt(stored)
            if (value !is Boolean) continue
            if (isNotEmpty()) append('\n')
            append('"').append(key).append('"')
            append(',').append('"').append(if (value) "yes" else "no").append('"')
            append(',').append('"').append(jsonNote(notes, stored).replace("\"", "\"\"")).append('"')
        }
    }
    val noteMap = buildMap {
        notes?.keys()?.forEach { key ->
            val note = jsonNote(notes, key)
            if (note.isNotEmpty()) put(if (key == OPEN_SOURCE) SOURCE_AVAILABLE else key, note)
        }
    }
    val flags = buildList {
        val raw = obj.optJSONArray("flags") ?: return@buildList
        for (index in 0 until raw.length()) {
            val flag = raw.optJSONObject(index) ?: continue
            val id = flag.optString("id").trim()
            if (id.isNotEmpty()) add(SecurityFlag(id, flag.optString("note").trim()))
        }
    }
    val permissions = buildString {
        val ids = obj.optJSONArray("permissions") ?: JSONArray()
        for (index in 0 until ids.length()) {
            val id = ids.optString(index).trim()
            if (id.isEmpty()) continue
            if (isNotEmpty()) append(", ")
            append(id)
        }
    }
    val notice = obj.optString("notice").trim()
    val body = obj.optString("body").trim()
    val prose = when {
        notice.isNotEmpty() && body.isNotEmpty() -> "$notice\n---\n$body"
        notice.isNotEmpty() -> notice
        else -> body
    }
    val keywords = buildList {
        val raw = obj.optJSONArray("keywords") ?: return@buildList
        for (index in 0 until raw.length()) {
            val word = raw.optString(index).trim()
            if (word.isNotEmpty()) add(word)
        }
    }
    return SecurityDocument(csv, permissions, prose, true, keywords, noteMap, flags)
}

private fun jsonNote(notes: JSONObject?, key: String): String {
    if (notes == null || notes.isNull(key)) return ""
    val value = notes.opt(key)
    return if (value is String) value.trim() else ""
}

/** Fact CSV inside a security file, or null when the text is not one. */
internal fun securityFactsCsv(text: String): String? {
    val doc = parseSecurityDocument(text)
    return if (doc.combined) doc.csv else null
}

/** Keywords from a JSON security file. A dash-separated file has none. */
internal fun securityKeywords(text: String): List<String> = parseSecurityDocument(text).keywords

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
        val key = record.getOrNull(0)?.trim().orEmpty().let { if (it == OPEN_SOURCE) SOURCE_AVAILABLE else it }
        if (key.isEmpty() || key.equals("fact", ignoreCase = true)) continue
        val yes = when (record.getOrNull(1)?.trim()) {
            "yes" -> true
            "no" -> false
            else -> continue // unknown is not a pill, so a missing source_available is not No source
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

/** One scanner fact. [reason] is the notes column, empty when the CSV has no phrase for this key. */
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
    val lud16: String? = null,
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

/** Kind 9735 projection. [note] is the comment of the zap request inside the receipt, often empty. */
data class ZapRecord(
    val eventId: String,
    val pubkey: String,
    val amountSats: Long,
    val createdAt: Long,
    val appId: String,
    val note: String = "",
)

/**
 * Kind 1111 projection. [root] is the NIP-22 root coordinate (the `A` tag): an app's, whose pubkey says
 * whether the comment is in the app's main list, or a stack's. [appId] is the `i` tag, else the app of
 * the `a` or `A` tag; it is null on a comment on a stack.
 */
data class CommentRecord(
    val eventId: String,
    val pubkey: String,
    val createdAt: Long,
    val content: String,
    val appId: String?,
    val root: String?,
    val parentEventId: String?,
    /** The app version the comment was written on (its `v` tag), when it names one. */
    val version: String? = null,
) {
    /** The pubkey in [root], when it is an app coordinate. */
    val rootPubkey: String? get() = root?.let(AppCoordinate::parse)?.pubkey
}

/** Recent social activity across apps, newest first within each list. */
data class ActivityRecord(val comments: List<CommentRecord> = emptyList(), val zaps: List<ZapRecord> = emptyList())

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

    /**
     * [get], with a 200 body written to [destination] instead of returned, and refused past [maxBytes].
     * A catalog bundle is tens of MB, so it goes to disk rather than the heap. Other statuses keep their
     * body. The default goes through [get]; a transport that can stream overrides it.
     */
    fun download(url: String, headers: Map<String, String>, destination: File, maxBytes: Long): HttpResponse {
        val response = get(url, headers)
        if (response.status != 200) return response
        val body = response.body ?: ByteArray(0)
        if (body.size > maxBytes) throw java.io.IOException("response body exceeds $maxBytes bytes")
        destination.writeBytes(body)
        return response.copy(body = null)
    }
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

/** Matches the web app's `formatBytes`: whole megabytes to one decimal, else whole kilobytes, else bytes. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_000_000.0)
    bytes >= 1_000 -> "${Math.round(bytes / 1_000.0)} KB"
    else -> "$bytes B"
}

internal fun JSONObject.string(key: String): String? = optString(key).takeIf { it.isNotBlank() }

internal fun JSONObject.stringList(key: String): List<String> = optJSONArray(key).toStringList()

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { index -> optString(index) }.filter { it.isNotBlank() }
}

private val markdownImage = Regex("""!\[[^\]]*]\([^)]*\)""")
private val markdownLink = Regex("""\[([^\]]*)]\([^)]*\)""")
private val markdownMarks = Regex("""\*\*|__|`|^\s*(?:>+|[-+*]|\d+\.)\s+""")

/**
 * The first paragraph that is prose: headings, images, and bare URLs are skipped, links keep
 * their text, and emphasis marks are dropped.
 */
internal fun plainParagraph(markdown: String): String =
    markdown.split(Regex("""\n\s*\n"""))
        .asSequence()
        .map { block ->
            block.lines()
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString(" ") { it.replace(markdownImage, "").replace(markdownLink, "$1").replace(markdownMarks, "") }
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("http") }
        .orEmpty()
