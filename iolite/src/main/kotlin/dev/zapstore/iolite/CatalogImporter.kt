package dev.zapstore.iolite

import java.io.ByteArrayInputStream
import java.io.File
import org.json.JSONObject

data class CatalogImportResult(val from: Long, val to: Long)

/** Outcome of `GET /bundle`. [imported] is null when the relay answered 304. */
data class CatalogSyncResult(
    val imported: CatalogImportResult?,
    val bytes: Long,
) {
    val notModified: Boolean get() = imported == null
}

/**
 * Verifies a catalog-sync bundle (signed manifest, member hashes, event signatures) and hands the
 * verified events and artifact files to [IoliteStore.importCatalog].
 */
class CatalogImporter(
    private val store: IoliteStore,
    private val iconsDir: File,
    private val config: IoliteConfig,
    private val device: DeviceProfile,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    fun importBundle(catalogId: Long, compressed: ByteArray, endpoint: RelayUrl? = null): CatalogImportResult {
        val existing = store.catalog(catalogId)
        val members = try {
            TarZstd.read(
                ByteArrayInputStream(compressed),
                maxCompressedBytes = config.maxCompressedBytes,
                maxUncompressedBytes = config.maxUncompressedBytes,
                maxMembers = config.maxMembers,
                maxMemberBytes = config.maxUncompressedBytes,
            )
        } catch (failure: Exception) {
            throw CatalogImportException(failure.message ?: "invalid catalog bundle")
        }
        val byName = members.associateBy { it.name }
        val manifest = parseAndVerifyManifest(members.first().data, existing?.manifestPubkey, existing?.epoch ?: 0L)
        val names = members.drop(1).map { it.name }
        if (names != names.sorted()) throw CatalogImportException("tar members after manifest.json must be lexicographic")

        val fileTags = manifest.event.tags.filter { it.firstOrNull() == "file" }
        verifyFileTags(fileTags, byName)
        val index = byName["index"] ?: throw CatalogImportException("index is missing")
        verifyIndex(index.data, members)

        val unexpected = names.filter { !CatalogArtifacts.allowedMember(it) }
        if (unexpected.isNotEmpty()) throw CatalogImportException("unexpected catalog member ${unexpected.first()}")

        val (events, deletes) = parseDiff(byName["diff.jsonl"], config.maxJsonLineBytes)
        validateOperations(events, deletes)
        val catalogPubkey = existing?.manifestPubkey ?: manifest.event.pubkey
        validateStacks(events, deletes, catalogPubkey)
        events.filter { it.kind == Kinds.IdentityProof }.forEach(ListingRows::validateProof)
        val artifacts = CatalogArtifacts.fromMembers(byName, config.maxIconBytes)

        if (existing == null || existing.relays.isEmpty()) {
            val relay = endpoint ?: throw CatalogImportException("catalog relay is missing")
            store.upsertCatalogRelays(catalogId, listOf(relay), replaceIdentity = true)
        }
        if (existing?.manifestPubkey == null) store.pinManifestPubkeyIfAbsent(catalogId, manifest.event.pubkey)
        val catalog = store.catalog(catalogId) ?: throw CatalogImportException("unknown catalog $catalogId")
        val removed = try {
            store.importCatalog(
                catalog = catalog,
                events = events,
                deletes = deletes,
                replaceAll = manifest.fromEpoch == 0L,
                toEpoch = manifest.toEpoch,
                device = device,
                now = nowSeconds(),
                artifacts = artifacts,
            )
        } catch (failure: Throwable) {
            throw if (failure is CatalogImportException) failure else CatalogImportException(failure.message ?: "import failed")
        }
        removed.forEach { appId -> File(iconsDir, "$catalogId/$appId.webp").delete() }
        return CatalogImportResult(manifest.fromEpoch, manifest.toEpoch)
    }

    private fun verifyFileTags(fileTags: List<List<String>>, byName: Map<String, TarMember>) {
        val seenFiles = HashSet<String>()
        for (tag in fileTags) {
            if (tag.size < 3) throw CatalogImportException("malformed file tag")
            val name = tag[1]
            if (!seenFiles.add(name)) throw CatalogImportException("duplicate file tag $name")
            val member = byName[name] ?: throw CatalogImportException("manifest file $name is missing")
            if (member.data.sha256Hex() != tag[2]) throw CatalogImportException("hash mismatch for $name")
        }
    }

    private fun verifyIndex(raw: ByteArray, members: List<TarMember>) {
        val lines = raw.toString(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
        val listed = HashMap<String, String>()
        for (line in lines) {
            if (line.length < 66 || line[64] != ' ') throw CatalogImportException("malformed index line")
            val hash = line.substring(0, 64)
            val name = line.substring(65)
            if (listed.put(name, hash) != null) throw CatalogImportException("duplicate index entry $name")
        }
        for (member in members) {
            if (member.name == "manifest.json" || member.name == "index") continue
            val hash = listed.remove(member.name) ?: throw CatalogImportException("index missing ${member.name}")
            if (member.data.sha256Hex() != hash) throw CatalogImportException("hash mismatch for ${member.name}")
        }
        if (listed.isNotEmpty()) throw CatalogImportException("index entry ${listed.keys.first()} is missing")
    }

    private fun parseAndVerifyManifest(raw: ByteArray, expectedPubkey: String?, localEpoch: Long): Manifest {
        val event = Event.parse(raw.toString(Charsets.UTF_8))
        if (event.kind != Kinds.Preferences) throw CatalogImportException("manifest kind must be 30078")
        if (!event.verify()) throw CatalogImportException("manifest signature is invalid")
        if (expectedPubkey != null && event.pubkey != expectedPubkey) {
            throw CatalogImportException("manifest pubkey does not match the pinned key")
        }
        val from = event.tagValue("from")?.toLongOrNull() ?: throw CatalogImportException("manifest from is missing")
        val to = event.tagValue("to")?.toLongOrNull() ?: throw CatalogImportException("manifest to is missing")
        val version = event.tagValue("v")?.toIntOrNull() ?: throw CatalogImportException("manifest v is missing")
        if (from != localEpoch) throw CatalogImportException("manifest from $from does not match catalog epoch $localEpoch")
        if (to < from) throw CatalogImportException("manifest to is behind from")
        if (version != 1) throw CatalogImportException("unsupported catalog-sync version $version")
        return Manifest(from, to, event)
    }

    private data class Manifest(
        val fromEpoch: Long,
        val toEpoch: Long,
        val event: Event,
    )

    companion object {
        private fun parseDiff(member: TarMember?, maxLine: Long): Pair<List<Event>, List<CatalogDelete>> {
            if (member == null) return emptyList<Event>() to emptyList()
            val text = member.data.toString(Charsets.UTF_8)
            if (text.isEmpty()) return emptyList<Event>() to emptyList()
            val events = ArrayList<Event>()
            val deletes = ArrayList<CatalogDelete>()
            for (line in text.split('\n')) {
                if (line.isEmpty()) continue
                if (line.length.toLong() > maxLine) throw CatalogImportException("JSONL line exceeds limit")
                val json = JSONObject(line)
                when {
                    json.has("id") -> {
                        val event = Event.parse(line)
                        if (!event.verify()) throw CatalogImportException("event ${event.id} failed verification")
                        events += event
                    }
                    json.has("delete") -> {
                        if (json.has("kind") || json.has("pubkey") || json.has("d") || json.has("app_id")) {
                            throw CatalogImportException("delete line has mixed forms")
                        }
                        val ids = json.optJSONArray("delete") ?: throw CatalogImportException("invalid delete line")
                        for (index in 0 until ids.length()) {
                            val appId = ids.optString(index)
                            if (appId.isBlank()) throw CatalogImportException("invalid delete line")
                            deletes += CatalogDelete.Listing(appId)
                        }
                    }
                    else -> {
                        val kind = json.optInt("kind", -1)
                        val pubkey = json.optString("pubkey")
                        val d = json.optString("d")
                        if (kind < 0 || pubkey.isBlank() || d.isBlank() || json.has("app_id")) {
                            throw CatalogImportException("invalid delete line")
                        }
                        deletes += CatalogDelete.Coordinate(kind, pubkey, d)
                    }
                }
            }
            return events to deletes
        }

        private fun validateStacks(events: List<Event>, deletes: List<CatalogDelete>, catalogPubkey: String) {
            for (event in events) {
                if (event.kind != Kinds.AppStack) continue
                if (event.pubkey != catalogPubkey) throw CatalogImportException("stack is not signed by the catalog key")
                if (event.dTag().isNullOrBlank()) throw CatalogImportException("stack missing d")
            }
            for (delete in deletes) {
                if (delete is CatalogDelete.Coordinate && delete.kind == Kinds.AppStack && delete.pubkey != catalogPubkey) {
                    throw CatalogImportException("stack is not signed by the catalog key")
                }
            }
        }

        private fun validateOperations(events: List<Event>, deletes: List<CatalogDelete>) {
            val listingKeys = HashSet<String>()
            val eventKeys = HashSet<String>()
            for (event in events) {
                if (ListingRows.isListingKind(event.kind)) {
                    listingKeys += ListingRows.listingAppId(event) ?: continue
                } else {
                    val key = "${event.kind}:${event.pubkey}:${event.dTag().orEmpty()}"
                    if (!eventKeys.add(key)) throw CatalogImportException("duplicate logical key $key")
                }
            }
            for (delete in deletes) {
                when (delete) {
                    is CatalogDelete.Listing -> if (!listingKeys.add(delete.appId)) {
                        throw CatalogImportException("event and delete for ${delete.appId}")
                    }
                    is CatalogDelete.Coordinate -> {
                        val key = "${delete.kind}:${delete.pubkey}:${delete.d}"
                        if (!eventKeys.add(key)) throw CatalogImportException("event and delete for $key")
                    }
                }
            }
        }

    }
}
