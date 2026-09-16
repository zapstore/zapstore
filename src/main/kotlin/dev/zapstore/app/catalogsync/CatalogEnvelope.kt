package dev.zapstore.app.catalogsync

import com.vitorpamplona.quartz.nip01Core.core.Event
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class CatalogManifest(
    val event: Event,
    val contentHash: String,
    val oldEpoch: Long,
    val newEpoch: Long,
    val schemaVersion: Int,
    val searchModel: String,
    val catalog: String,
    val kind: String,
)

data class CatalogDelta(
    val events: List<Event>,
    val deletedIds: List<String>,
    val oldEpoch: Long,
    val newEpoch: Long,
)

data class DecodedEnvelope(
    val manifest: CatalogManifest,
    val payload: ByteArray,
)

object CatalogEnvelope {
    fun encode(manifestEventJson: String, payload: ByteArray): ByteArray {
        val compressed = gzip(payload)
        val manifestBytes = manifestEventJson.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(CatalogSchema.MAGIC.size + 4 + manifestBytes.size + compressed.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(CatalogSchema.MAGIC)
        buffer.putInt(manifestBytes.size)
        buffer.put(manifestBytes)
        buffer.put(compressed)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): DecodedEnvelope {
        require(bytes.size >= CatalogSchema.MAGIC.size + 4) { "catalog envelope is truncated" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(CatalogSchema.MAGIC.size)
        buffer.get(magic)
        require(magic.contentEquals(CatalogSchema.MAGIC)) { "catalog envelope magic is invalid" }
        val manifestLength = buffer.int
        require(manifestLength > 0 && manifestLength <= buffer.remaining()) { "catalog envelope manifest is truncated" }
        val manifestBytes = ByteArray(manifestLength)
        buffer.get(manifestBytes)
        val compressed = ByteArray(buffer.remaining())
        buffer.get(compressed)
        return DecodedEnvelope(
            manifest = parseManifest(JSONObject(String(manifestBytes, Charsets.UTF_8))),
            payload = gunzip(compressed),
        )
    }

    fun decodeStreamToFile(input: InputStream, payloadFile: File): CatalogManifest {
        val magic = input.readExact(CatalogSchema.MAGIC.size)
        require(magic.size == CatalogSchema.MAGIC.size && magic.contentEquals(CatalogSchema.MAGIC)) {
            "catalog envelope magic is invalid"
        }
        val lengthBytes = input.readExact(4)
        require(lengthBytes.size == 4) { "catalog envelope is truncated" }
        val manifestLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).int
        require(manifestLength > 0) { "catalog envelope manifest is truncated" }
        val manifestBytes = input.readExact(manifestLength)
        require(manifestBytes.size == manifestLength) { "catalog envelope manifest is truncated" }
        payloadFile.outputStream().use { output ->
            GZIPInputStream(input).use { gzip -> gzip.copyTo(output) }
        }
        return parseManifest(JSONObject(String(manifestBytes, Charsets.UTF_8)))
    }

    private fun InputStream.readExact(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            if (read < 0) return bytes.copyOf(offset)
            offset += read
        }
        return bytes
    }

    fun parseManifest(json: JSONObject): CatalogManifest {
        val event = Event(
            id = json.getString("id"),
            pubKey = json.getString("pubkey"),
            createdAt = json.getLong("created_at"),
            kind = json.getInt("kind"),
            tags = decodeEventTags(json.getJSONArray("tags")),
            content = json.getString("content"),
            sig = json.getString("sig"),
        )
        val body = JSONObject(event.content)
        return CatalogManifest(
            event = event,
            contentHash = body.getString("content_hash"),
            oldEpoch = body.getLong("old_epoch"),
            newEpoch = body.getLong("new_epoch"),
            schemaVersion = body.getInt("schema_version"),
            searchModel = body.getString("search_model"),
            catalog = body.optString("catalog", CatalogSchema.CATALOG),
            kind = body.getString("kind"),
        )
    }

    fun parseDelta(payload: ByteArray): CatalogDelta {
        val json = JSONObject(String(payload, Charsets.UTF_8))
        val events = json.getJSONArray("events")
        val deleted = json.optJSONArray("deleted_ids") ?: JSONArray()
        return CatalogDelta(
            events = List(events.length()) { parseWireEvent(events.getJSONObject(it)) },
            deletedIds = List(deleted.length()) { deleted.getString(it) },
            oldEpoch = json.getLong("old_epoch"),
            newEpoch = json.getLong("new_epoch"),
        )
    }

    fun encodeDelta(delta: CatalogDelta): ByteArray {
        val events = JSONArray()
        delta.events.forEach { events.put(wireEvent(it)) }
        val deleted = JSONArray()
        delta.deletedIds.forEach(deleted::put)
        return JSONObject()
            .put("events", events)
            .put("deleted_ids", deleted)
            .put("old_epoch", delta.oldEpoch)
            .put("new_epoch", delta.newEpoch)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun parseWireEvent(json: JSONObject): Event = Event(
        id = json.getString("id"),
        pubKey = json.getString("pubkey"),
        createdAt = json.getLong("created_at"),
        kind = json.getInt("kind"),
        tags = decodeEventTags(json.getJSONArray("tags")),
        content = json.getString("content"),
        sig = json.getString("sig"),
    )

    fun wireEvent(event: Event): JSONObject = JSONObject()
        .put("id", event.id)
        .put("pubkey", event.pubKey)
        .put("created_at", event.createdAt)
        .put("kind", event.kind)
        .put("tags", JSONArray(event.tags.map { JSONArray(it.toList()) }))
        .put("content", event.content)
        .put("sig", event.sig)

    fun decodeEventTags(tags: JSONArray): Array<Array<String>> =
        Array(tags.length()) { index ->
            val row = tags.getJSONArray(index)
            Array(row.length()) { row.getString(it) }
        }

    fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
}

data class UpdatesRequest(
    val protocol: Int,
    val catalog: String,
    val schemaVersion: Int,
    val epoch: Long,
    val searchModel: String,
) {
    fun toJson(): String =
        """{"protocol":$protocol,"catalog":"$catalog","schema_version":$schemaVersion,"epoch":$epoch,"search_model":"$searchModel"}"""

    fun forbiddenFields(): Set<String> = emptySet()
}

fun parseUpdatesRequest(json: JSONObject): UpdatesRequest {
    val forbidden = listOf(
        "installed", "apps", "package", "packages", "search", "query", "hashes", "versions",
    )
    json.keys().asSequence().forEach { key ->
        require(key !in forbidden) { "updates request must not include $key" }
    }
    return UpdatesRequest(
        protocol = json.getInt("protocol"),
        catalog = json.getString("catalog"),
        schemaVersion = json.getInt("schema_version"),
        epoch = json.getLong("epoch"),
        searchModel = json.getString("search_model"),
    )
}
