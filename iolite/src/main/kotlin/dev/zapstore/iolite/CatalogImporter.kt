package dev.zapstore.iolite

import java.io.ByteArrayInputStream
import java.io.File
import org.json.JSONObject

/** [skipped] names what was left out; everything that verified was imported. */
data class CatalogImportResult(val from: Long, val to: Long, val skipped: List<String> = emptyList())

/** Outcome of `GET /v1/bundle`. [imported] is null when the relay answered 304. */
data class CatalogSyncResult(
    val imported: CatalogImportResult?,
    val bytes: Long,
) {
    val notModified: Boolean get() = imported == null
}

/**
 * Verifies a catalog-sync bundle (signed manifest, member hashes, event signatures) and hands the
 * verified events and artifact files to [IoliteStore.importCatalog].
 *
 * Only a bundle that cannot be trusted at all is rejected: an unreadable archive, a manifest that
 * fails verification or does not follow the local epoch, or an index that does not match the
 * manifest. Any other bad member, line, or event is skipped and reported; the rest imports.
 */
class CatalogImporter(
    private val store: IoliteStore,
    private val iconsDir: File,
    private val config: IoliteConfig,
    private val device: DeviceProfile,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    /** The key that signed this bundle's manifest, or null when the bundle is unreadable or the manifest is not valid. */
    fun manifestSigner(compressed: ByteArray): String? = runCatching {
        val members = TarZstd.read(
            ByteArrayInputStream(compressed),
            maxCompressedBytes = config.maxCompressedBytes,
            maxUncompressedBytes = config.maxUncompressedBytes,
            maxMembers = config.maxMembers,
            maxMemberBytes = config.maxUncompressedBytes,
        )
        val event = Event.parse(members.first().data.toString(Charsets.UTF_8))
        event.pubkey.takeIf { event.kind == Kinds.Preferences && event.verify() }
    }.getOrNull()

    /** A full snapshot (`from` 0) replaces the local catalog when its `to` is not behind the local epoch. */
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
        if (members.isEmpty()) throw CatalogImportException("catalog bundle is empty")
        val skipped = ArrayList<String>()
        val manifest = parseAndVerifyManifest(members.first().data, existing?.manifestPubkey, existing?.epoch ?: 0L)
        val verified = verifyMembers(manifest.event, members.drop(1), skipped)

        val diff = verified["diff.jsonl"]
        val diffMissing = diff == null && members.any { it.name == "diff.jsonl" }
        val (parsedEvents, parsedDeletes) = parseDiff(diff, config.maxJsonLineBytes, skipped)
        val catalogPubkey = existing?.manifestPubkey ?: manifest.event.pubkey
        val (events, deletes) = validateOperations(
            validStacks(parsedEvents, catalogPubkey, skipped),
            validStackDeletes(parsedDeletes, catalogPubkey, skipped),
            skipped,
        )
        val artifacts = CatalogArtifacts.fromMembers(verified, config.maxIconBytes, skipped)

        val published = catalogKeyRelays(events, catalogPubkey)
        // A local relay often serves a snapshot whose kind 10067 names the public catalog. The phone
        // fetched this bundle from the local relay, so that address stays the one it syncs.
        val fetched = endpoint ?: existing?.relayUrl
        if (published.isNotEmpty() && fetched?.isLoopback != true) {
            store.upsertCatalogRelays(catalogId, published, replaceIdentity = true)
        } else if (existing == null || existing.relays.isEmpty()) {
            val relay = endpoint ?: throw CatalogImportException("catalog relay is missing")
            store.upsertCatalogRelays(catalogId, listOf(relay), replaceIdentity = true)
        }
        if (existing?.manifestPubkey == null) store.pinManifestPubkeyIfAbsent(catalogId, manifest.event.pubkey)
        val catalog = store.catalog(catalogId) ?: throw CatalogImportException("unknown catalog $catalogId")
        // Without its diff the epoch cannot advance, or the events would never be fetched again.
        val toEpoch = if (diffMissing) manifest.fromEpoch else manifest.toEpoch
        val removed = try {
            store.importCatalog(
                catalog = catalog,
                events = events,
                deletes = deletes,
                replaceAll = manifest.fromEpoch == 0L && !diffMissing,
                toEpoch = toEpoch,
                device = device,
                now = nowSeconds(),
                artifacts = artifacts,
                skipped = skipped,
            )
        } catch (failure: Throwable) {
            throw if (failure is CatalogImportException) failure else CatalogImportException(failure.message ?: "import failed")
        }
        removed.forEach { appId -> File(iconsDir, "$catalogId/$appId.webp").delete() }
        return CatalogImportResult(manifest.fromEpoch, toEpoch, skipped)
    }

    /**
     * Members whose bytes match the signed chain: the manifest's `file` tag for `index`, then
     * `index` for the rest. A member that does not match, or that the index does not list, is skipped.
     */
    private fun verifyMembers(manifest: Event, members: List<TarMember>, skipped: MutableList<String>): Map<String, TarMember> {
        val byName = LinkedHashMap<String, TarMember>()
        for (member in members) {
            if (byName.put(member.name, member) != null) skipped += "duplicate member ${member.name}"
        }
        val indexHash = manifest.tags.firstOrNull { it.size >= 3 && it[0] == "file" && it[1] == "index" }?.get(2)
            ?: throw CatalogImportException("manifest has no index file tag")
        val index = byName["index"] ?: throw CatalogImportException("index is missing")
        if (index.data.sha256Hex() != indexHash) throw CatalogImportException("hash mismatch for index")

        val listed = HashMap<String, String>()
        for (line in index.data.toString(Charsets.UTF_8).split('\n')) {
            if (line.isEmpty()) continue
            if (line.length < 66 || line[64] != ' ' || !Hex.isHex(line.substring(0, 64), 64)) {
                skipped += "malformed index line"
                continue
            }
            val name = line.substring(65)
            if (listed.put(name, line.substring(0, 64)) != null) skipped += "duplicate index entry $name"
        }
        val verified = LinkedHashMap<String, TarMember>()
        for ((name, member) in byName) {
            if (name == "index") continue
            val hash = listed[name]
            when {
                hash == null -> skipped += "member $name is not in the index"
                member.data.sha256Hex() != hash -> skipped += "hash mismatch for $name"
                !CatalogArtifacts.allowedMember(name) -> skipped += "unexpected member $name"
                else -> verified[name] = member
            }
        }
        return verified
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
        if (to < from) throw CatalogImportException("manifest to is behind from")
        if (from != localEpoch && !(from == 0L && to >= localEpoch)) {
            throw CatalogImportException("manifest from $from does not match catalog epoch $localEpoch")
        }
        if (version != 1) throw CatalogImportException("unsupported catalog-sync version $version")
        return Manifest(from, to, event)
    }

    private data class Manifest(
        val fromEpoch: Long,
        val toEpoch: Long,
        val event: Event,
    )

    companion object {
        /** Events and deletes from `diff.jsonl`. A line that is not valid is skipped. */
        internal fun parseDiff(
            member: TarMember?,
            maxLine: Long,
            skipped: MutableList<String> = ArrayList(),
        ): Pair<List<Event>, List<CatalogDelete>> {
            if (member == null) return emptyList<Event>() to emptyList()
            val text = member.data.toString(Charsets.UTF_8)
            val events = ArrayList<Event>()
            val deletes = ArrayList<CatalogDelete>()
            text.split('\n').forEachIndexed { number, line ->
                if (line.isEmpty()) return@forEachIndexed
                val where = "diff line ${number + 1}"
                if (line.length.toLong() > maxLine) {
                    skipped += "$where exceeds the line limit"
                    return@forEachIndexed
                }
                val json = runCatching { JSONObject(line) }.getOrNull()
                if (json == null) {
                    skipped += "$where is not JSON"
                    return@forEachIndexed
                }
                when {
                    json.has("id") -> catalogEvent(line, json)
                        .onSuccess { event -> if (event != null) events += event }
                        .onFailure { skipped += "$where: ${it.message}" }
                    json.has("delete") -> {
                        val ids = json.optJSONArray("delete")
                        if (ids == null || json.has("kind") || json.has("pubkey") || json.has("d") || json.has("app_id")) {
                            skipped += "$where is an invalid delete"
                            return@forEachIndexed
                        }
                        for (index in 0 until ids.length()) {
                            val appId = ids.optString(index)
                            if (appId.isBlank()) skipped += "$where has a blank app id" else deletes += CatalogDelete.Listing(appId)
                        }
                    }
                    json.optBoolean("banned") -> {
                        if (json.optString("app_id").isBlank()) skipped += "$where is an invalid ban"
                    }
                    else -> {
                        val kind = json.optInt("kind", -1)
                        val pubkey = json.optString("pubkey")
                        val d = json.optString("d")
                        val addressable = kind in 30_000..39_999
                        if (kind < 0 || !Hex.isHex(pubkey, 64) || !json.has("d") || (addressable && d.isBlank()) || json.has("app_id")) {
                            skipped += "$where is an invalid delete"
                        } else {
                            deletes += CatalogDelete.Coordinate(kind, pubkey, d)
                        }
                    }
                }
            }
            return events to deletes
        }

        /** A verified event, null for a proof that is not acceptable, or a failure naming why it was skipped. */
        private fun catalogEvent(line: String, json: JSONObject): Result<Event?> = runCatching {
            val event = Event.parse(line)
            if (!event.verify()) error("event ${event.id} failed verification")
            if (event.kind == Kinds.IdentityProof && !ListingRows.acceptableProof(event)) null else event
        }.recoverCatching { failure ->
            if (json.optInt("kind", -1) == Kinds.IdentityProof) null else throw failure
        }

        private fun validStacks(events: List<Event>, catalogPubkey: String, skipped: MutableList<String>): List<Event> =
            events.filter { event ->
                when {
                    event.kind != Kinds.AppStack -> true
                    event.pubkey != catalogPubkey -> false.also { skipped += "stack ${event.id} is not signed by the catalog key" }
                    event.dTag().isNullOrBlank() -> false.also { skipped += "stack ${event.id} has no d" }
                    else -> true
                }
            }

        private fun validStackDeletes(deletes: List<CatalogDelete>, catalogPubkey: String, skipped: MutableList<String>) =
            deletes.filter { delete ->
                val foreign = delete is CatalogDelete.Coordinate && delete.kind == Kinds.AppStack && delete.pubkey != catalogPubkey
                if (foreign) skipped += "stack delete is not for the catalog key"
                !foreign
            }

        /** Public `r` tags on the catalog key's kind 10067. Other keys and unusable URLs are skipped. */
        private fun catalogKeyRelays(events: List<Event>, catalogPubkey: String): List<RelayUrl> {
            val event = events.firstOrNull { it.kind == Kinds.CatalogRelayList && it.pubkey == catalogPubkey }
                ?: return emptyList()
            val relays = LinkedHashSet<RelayUrl>()
            for (raw in event.tagValues("r")) {
                val relay = runCatching { raw.normalizeRelayUrl() }.getOrNull() ?: continue
                relays += relay
            }
            return relays.toList()
        }

        /**
         * One operation per logical key. For two events, the newer wins (NIP-01 order). An event beats
         * a delete for the same key, since the event is signed and the delete line is not.
         */
        private fun validateOperations(
            events: List<Event>,
            deletes: List<CatalogDelete>,
            skipped: MutableList<String>,
        ): Pair<List<Event>, List<CatalogDelete>> {
            val listingKeys = HashSet<String>()
            val byKey = LinkedHashMap<String, Event>()
            val kept = ArrayList<Event>()
            for (event in events) {
                if (ListingRows.isListingKind(event.kind)) {
                    ListingRows.listingAppId(event)?.let(listingKeys::add)
                    kept += event
                    continue
                }
                val key = "${event.kind}:${event.pubkey}:${event.dTag().orEmpty()}"
                val other = byKey[key]
                if (other == null) {
                    byKey[key] = event
                } else {
                    skipped += "duplicate logical key $key"
                    if (event.createdAt > other.createdAt || (event.createdAt == other.createdAt && event.id < other.id)) byKey[key] = event
                }
            }
            kept += byKey.values
            val keptDeletes = deletes.filter { delete ->
                val clash = when (delete) {
                    is CatalogDelete.Listing -> !listingKeys.add(delete.appId)
                    is CatalogDelete.Coordinate -> byKey.containsKey("${delete.kind}:${delete.pubkey}:${delete.d}")
                }
                if (clash) skipped += "event and delete for the same key; the delete was skipped"
                !clash
            }
            return kept to keptDeletes
        }
    }
}
