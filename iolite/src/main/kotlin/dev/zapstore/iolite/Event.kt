package dev.zapstore.iolite

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class Event(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun tagValue(name: String): String? = tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

    fun tagValues(name: String): List<String> =
        tags.mapNotNull { tag -> tag.getOrNull(1)?.takeIf { tag.firstOrNull() == name } }

    fun tagCount(name: String): Int = tags.count { it.firstOrNull() == name }

    fun dTag(): String? = tagValue("d")

    fun address(): Address? = when {
        kind.isAddressable() -> Address(kind, pubkey, dTag().orEmpty())
        kind.isReplaceable() -> Address(kind, pubkey, "")
        else -> null
    }

    fun toJsonObject(): JSONObject = JSONObject()
        .put("id", id)
        .put("pubkey", pubkey)
        .put("created_at", createdAt)
        .put("kind", kind)
        .put("tags", tags.toJsonArray())
        .put("content", content)
        .put("sig", sig)

    fun toJson(): String = toJsonObject().toString()

    fun verify(): Boolean = id == computeId(pubkey, createdAt, kind, tags, content) &&
        Crypto.verifySchnorr(Hex.decode(sig), Hex.decode(id), Hex.decode(pubkey))

    companion object {
        fun parse(raw: String): Event = parse(JSONObject(raw))

        fun parse(json: JSONObject): Event {
            val tags = json.getJSONArray("tags").toStringLists()
            return Event(
                id = json.getString("id"),
                pubkey = json.getString("pubkey"),
                createdAt = json.getLong("created_at"),
                kind = json.getInt("kind"),
                tags = tags,
                content = json.optString("content", ""),
                sig = json.getString("sig"),
            )
        }

        fun computeId(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(canonicalBytes(pubkey, createdAt, kind, tags, content))
            return Hex.encode(digest.digest())
        }

        fun sign(
            secretKey: ByteArray,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
        ): Event {
            val pubkey = Hex.encode(Crypto.xOnlyPubkey(secretKey))
            val id = computeId(pubkey, createdAt, kind, tags, content)
            val sig = Hex.encode(Crypto.signSchnorr(Hex.decode(id), secretKey))
            return Event(id, pubkey, createdAt, kind, tags, content, sig)
        }

        internal fun canonicalBytes(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String,
        ): ByteArray {
            // NIP-01 id bytes must match go-nostr / JSON.stringify: compact JSON,
            // UTF-8, no escaped solidus. Android org.json escapes '/' and breaks verify.
            val json = StringBuilder(64 + pubkey.length + content.length + tags.sumOf { it.sumOf { value -> value.length + 3 } })
            json.append("[0,")
            appendJsonString(json, pubkey)
            json.append(',').append(createdAt).append(',').append(kind).append(',')
            json.append('[')
            tags.forEachIndexed { tagIndex, tag ->
                if (tagIndex > 0) json.append(',')
                json.append('[')
                tag.forEachIndexed { valueIndex, value ->
                    if (valueIndex > 0) json.append(',')
                    appendJsonString(json, value)
                }
                json.append(']')
            }
            json.append("],")
            appendJsonString(json, content)
            json.append(']')
            return json.toString().toByteArray(Charsets.UTF_8)
        }

        private fun appendJsonString(out: StringBuilder, value: String) {
            out.append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (ch.code < 0x20) {
                        out.append("\\u")
                        val hex = ch.code.toString(16)
                        repeat(4 - hex.length) { out.append('0') }
                        out.append(hex)
                    } else {
                        out.append(ch)
                    }
                }
            }
            out.append('"')
        }
    }
}

data class Address(val kind: Int, val pubkey: String, val d: String) {
    override fun toString(): String = "$kind:$pubkey:$d"
}

data class Filter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    val tags: Map<String, List<String>>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val search: String? = null,
) {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        ids?.let { json.put("ids", JSONArray(it)) }
        authors?.let { json.put("authors", JSONArray(it)) }
        kinds?.let { kinds ->
            val values = JSONArray()
            kinds.forEach(values::put)
            json.put("kinds", values)
        }
        tags?.forEach { (key, values) -> json.put("#$key", JSONArray(values)) }
        since?.let { json.put("since", it) }
        until?.let { json.put("until", it) }
        limit?.let { json.put("limit", it) }
        search?.let { json.put("search", it) }
        return json
    }
}

fun Int.isReplaceable(): Boolean = this == 0 || this == 3 || this in 10_000..19_999

fun Int.isEphemeral(): Boolean = this in 20_000..29_999

fun Int.isAddressable(): Boolean = this in 30_000..39_999

/** Replaceable-event precedence: newer `created_at` wins, then the lower event ID. */
fun supersedes(nextCreatedAt: Long, nextId: String, currentCreatedAt: Long, currentId: String): Boolean = when {
    nextCreatedAt != currentCreatedAt -> nextCreatedAt > currentCreatedAt
    else -> nextId < currentId
}

internal fun List<List<String>>.toJsonArray(): JSONArray {
    val tags = JSONArray()
    forEach { tag ->
        val values = JSONArray()
        tag.forEach(values::put)
        tags.put(values)
    }
    return tags
}

internal fun JSONArray?.toStringLists(): List<List<String>> {
    if (this == null) return emptyList()
    return List(length()) { index ->
        val tag = getJSONArray(index)
        List(tag.length()) { tag.getString(it) }
    }
}

object Kinds {
    const val Profile = 0
    const val Comment = 1_111
    const val Auth = 22_242
    const val Asset = 3_063
    const val Zap = 9_735
    const val RelayList = 10_002
    const val CatalogRelayList = 10_067
    const val AppStack = 30_267
    const val Preferences = 30_078
    const val Release = 30_063
    const val IdentityProof = 30_509
    const val App = 32_267
}

/** NIP-65: unmarked or `read`-marked `r` tags. */
fun List<List<String>>.readRelayUrls(): Set<RelayUrl> = markedRelayUrls("read")

private fun List<List<String>>.markedRelayUrls(marker: String): Set<RelayUrl> =
    asSequence()
        .filter { tag -> tag.getOrNull(0) == "r" }
        .filter { tag -> tag.getOrNull(2).let { it == null || it == marker } }
        .mapNotNull { tag -> tag.getOrNull(1) }
        .filter { it.startsWith("ws://") || it.startsWith("wss://") }
        .mapNotNull { runCatching { it.normalizeRelayUrl() }.getOrNull() }
        .toSet()
