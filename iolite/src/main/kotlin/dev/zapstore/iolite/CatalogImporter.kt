package dev.zapstore.iolite

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import org.json.JSONObject

/** [skipped] names what was left out; everything that verified was imported. [bytes] is the compressed size read. */
data class CatalogImportResult(val from: Long, val to: Long, val skipped: List<String> = emptyList(), val bytes: Long = 0)

/** Outcome of `GET /v1/bundle`. [imported] is null when the relay answered 304. */
data class CatalogSyncResult(
    val imported: CatalogImportResult?,
    val bytes: Long,
) {
    val notModified: Boolean get() = imported == null
}

/**
 * A catalog-sync bundle that can be opened more than once. Import reads it in passes and never holds
 * it decompressed, so an APK asset or a file is never loaded into memory whole.
 */
fun interface CatalogBundle {
    fun open(): InputStream

    companion object {
        fun of(bytes: ByteArray): CatalogBundle = CatalogBundle { ByteArrayInputStream(bytes) }

        fun of(file: File): CatalogBundle = CatalogBundle { file.inputStream().buffered() }
    }
}

/**
 * Verifies a catalog-sync bundle (signed manifest, member hashes, event signatures) and hands the
 * verified events and artifact files to [IoliteStore.importCatalog].
 *
 * Only a bundle that cannot be trusted at all is rejected: an unreadable archive, a manifest that
 * fails verification or does not follow the local epoch, or an index that does not match the
 * manifest. Any other bad member, line, or event is skipped and reported; the rest imports.
 *
 * A from-0 bundle is the whole catalog, so nothing here holds all of it. The first pass hashes every
 * member and keeps only the hashes. Inside the import transaction, a second pass streams `diff.jsonl`
 * and writes each app's listing as its events end, and a third writes the artifacts one app at a time.
 * Each pass checks the bytes against the first, so a bundle that changes between passes rolls back.
 */
class CatalogImporter(
    private val store: IoliteStore,
    private val iconsDir: File,
    private val config: IoliteConfig,
    private val device: DeviceProfile,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) {
    private val limits = TarLimits.of(config)

    /** The key that signed this bundle's manifest, or null when the bundle is unreadable or the manifest is not valid. Reads only the manifest. */
    fun manifestSigner(bundle: CatalogBundle): String? = runCatching {
        var signer: String? = null
        bundle.open().use { input ->
            TarZstd.forEach(input, limits) { _, size, data ->
                val event = Event.parse(readManifest(size, data).toString(Charsets.UTF_8))
                signer = event.pubkey.takeIf { event.kind == Kinds.Preferences && event.verify() }
                false
            }
        }
        signer
    }.getOrNull()

    fun manifestSigner(compressed: ByteArray): String? = manifestSigner(CatalogBundle.of(compressed))

    fun importBundle(catalogId: Long, compressed: ByteArray, endpoint: RelayUrl? = null): CatalogImportResult =
        importBundle(catalogId, CatalogBundle.of(compressed), endpoint)

    /** A full snapshot (`from` 0) replaces the local catalog when its `to` is not behind the local epoch. */
    fun importBundle(catalogId: Long, bundle: CatalogBundle, endpoint: RelayUrl? = null): CatalogImportResult {
        val existing = store.catalog(catalogId)
        val skipped = ArrayList<String>()
        val scan = invalidBundle { scan(bundle, existing?.manifestPubkey, existing?.epoch ?: 0L, skipped) }
        val manifest = scan.manifest
        val diffHash = scan.verified["diff.jsonl"]
        val diffMissing = diffHash == null && scan.hasDiff
        val catalogPubkey = existing?.manifestPubkey ?: manifest.event.pubkey
        // Without its diff the epoch cannot advance, or the events would never be fetched again.
        val toEpoch = if (diffMissing) manifest.fromEpoch else manifest.toEpoch
        val replaceAll = manifest.fromEpoch == 0L && !diffMissing
        val removed = try {
            store.importCatalog(catalogId, catalogPubkey, toEpoch, device, nowSeconds(), skipped) { writer ->
                val diff = if (diffHash == null) Diff.EMPTY else streamDiff(bundle, diffHash, catalogPubkey, writer, skipped)
                diff.deletes.forEach(writer::delete)
                updateRelays(writer, existing, endpoint, catalogRelays(diff.events, catalogPubkey))
                writer.events(diff.events, replaceAll)
                streamArtifacts(bundle, scan.verified, writer, skipped)
            }
        } catch (failure: Throwable) {
            throw if (failure is CatalogImportException) failure else CatalogImportException(failure.message ?: "import failed")
        }
        removed.forEach { appId -> File(iconsDir, "$catalogId/$appId.webp").delete() }
        return CatalogImportResult(manifest.fromEpoch, toEpoch, skipped, scan.bytes)
    }

    /**
     * Where the catalog is synced from. The catalog key's kind 10067 names it, except when the bundle
     * came from a loopback relay: a local relay often serves a snapshot whose kind 10067 names the
     * public catalog, and the phone keeps syncing the address it fetched from.
     */
    private fun updateRelays(writer: IoliteStore.CatalogWriter, existing: CatalogRecord?, endpoint: RelayUrl?, published: List<RelayUrl>) {
        val fetched = endpoint ?: existing?.relayUrl
        if (published.isNotEmpty() && fetched?.isLoopback != true) {
            writer.relays(published)
        } else if (existing == null || existing.relays.isEmpty()) {
            writer.relays(listOf(endpoint ?: throw CatalogImportException("catalog relay is missing")))
        }
    }

    /**
     * First pass: the manifest is verified as soon as it is read, then every member is hashed and
     * dropped. Members whose bytes match the signed chain (the manifest's `file` tag for `index`, then
     * `index` for the rest) are returned with their hashes; any other member is skipped.
     */
    private fun scan(bundle: CatalogBundle, expectedPubkey: String?, localEpoch: Long, skipped: MutableList<String>): Scan {
        var manifest: Manifest? = null
        var indexHash: String? = null
        val listed = HashMap<String, String>()
        val indexSkipped = ArrayList<String>()
        val hashes = LinkedHashMap<String, String>()
        val bytes = bundle.open().use { input ->
            TarZstd.forEach(input, limits) { name, size, data ->
                when {
                    manifest == null -> manifest = parseAndVerifyManifest(readManifest(size, data), expectedPubkey, localEpoch)
                    name == "index" -> {
                        val digest = MessageDigest.getInstance("SHA-256")
                        DigestInputStream(data, digest).forEachLine(INDEX_LINE_BYTES) { _, line ->
                            if (line?.isEmpty() == true) return@forEachLine
                            if (line == null || line.length < 66 || line[64] != ' ' || !Hex.isHex(line.substring(0, 64), 64)) {
                                indexSkipped += "malformed index line"
                                return@forEachLine
                            }
                            val member = line.substring(65)
                            if (listed.put(member, line.substring(0, 64)) != null) indexSkipped += "duplicate index entry $member"
                        }
                        indexHash = Hex.encode(digest.digest())
                    }
                    else -> hashes[name] = data.sha256Hex()
                }
                true
            }
        }
        val verifiedManifest = manifest ?: throw CatalogImportException("catalog bundle is empty")
        val signedIndexHash = verifiedManifest.event.tags.firstOrNull { it.size >= 3 && it[0] == "file" && it[1] == "index" }?.get(2)
            ?: throw CatalogImportException("manifest has no index file tag")
        val readIndexHash = indexHash ?: throw CatalogImportException("index is missing")
        if (readIndexHash != signedIndexHash) throw CatalogImportException("hash mismatch for index")
        skipped += indexSkipped

        val verified = LinkedHashMap<String, String>()
        for ((name, hash) in hashes) {
            when {
                listed[name] == null -> skipped += "member $name is not in the index"
                listed[name] != hash -> skipped += "hash mismatch for $name"
                !CatalogArtifacts.allowedMember(name) -> skipped += "unexpected member $name"
                else -> verified[name] = hash
            }
        }
        for (name in listed.keys) if (name !in hashes) skipped += "member $name is in the index but not the bundle"
        // The signed index, not the tar, says whether a diff exists: a stripped diff must not read as an empty one.
        return Scan(verifiedManifest, verified, hasDiff = "diff.jsonl" in listed || "diff.jsonl" in hashes, bytes)
    }

    /**
     * Second pass: `diff.jsonl` line by line. Listing events arrive ordered by app, so each app is
     * written when its events end. The rest of the diff (proofs, profiles, stacks, the catalog relay
     * list, deletes) is small and is returned for after the listings.
     */
    private fun streamDiff(
        bundle: CatalogBundle,
        expectedHash: String,
        catalogPubkey: String,
        writer: IoliteStore.CatalogWriter,
        skipped: MutableList<String>,
    ): Diff {
        val listed = HashSet<String>()
        val others = ArrayList<Event>()
        val deletes = ArrayList<CatalogDelete>()
        var appId: String? = null
        val group = ArrayList<Event>()
        fun flush() {
            val id = appId ?: return
            writer.listing(id, ArrayList(group))
            listed += id
            group.clear()
            appId = null
        }
        bundle.open().use { input ->
            TarZstd.forEach(input, limits) { name, _, data ->
                if (name != "diff.jsonl") return@forEach true
                val digest = MessageDigest.getInstance("SHA-256")
                readDiff(DigestInputStream(data, digest), config.maxJsonLineBytes, skipped,
                    onEvent = { event ->
                        if (!ListingRows.isListingKind(event.kind)) {
                            if (validStack(event, catalogPubkey, skipped)) others += event
                            return@readDiff
                        }
                        val id = ListingRows.listingAppId(event) ?: return@readDiff
                        if (id != appId) {
                            flush()
                            appId = id
                        }
                        group += event
                    },
                    onDelete = { delete -> if (validStackDelete(delete, catalogPubkey, skipped)) deletes += delete },
                )
                flush()
                if (Hex.encode(digest.digest()) != expectedHash) throw CatalogImportException("catalog bundle changed while importing")
                false
            }
        }
        return validateOperations(others, deletes, listed, skipped)
    }

    /** Third pass: verified artifacts, one app at a time, after the listings they attach to. */
    private fun streamArtifacts(
        bundle: CatalogBundle,
        verified: Map<String, String>,
        writer: IoliteStore.CatalogWriter,
        skipped: MutableList<String>,
    ) {
        if (verified.keys.none { it != "diff.jsonl" }) return
        val reader = CatalogArtifacts.Reader(config.maxIconBytes, skipped, writer::artifacts)
        bundle.open().use { input ->
            TarZstd.forEach(input, limits) { name, size, data ->
                val expected = verified[name] ?: return@forEach true
                if (name == "diff.jsonl") return@forEach true
                val digest = MessageDigest.getInstance("SHA-256")
                val hashed = DigestInputStream(data, digest)
                reader.add(name, size, hashed)
                hashed.drain()
                if (Hex.encode(digest.digest()) != expected) throw CatalogImportException("catalog bundle changed while importing")
                true
            }
        }
        reader.finish()
    }

    private fun readManifest(size: Long, data: InputStream): ByteArray {
        if (size > config.maxJsonLineBytes) throw CatalogImportException("manifest exceeds the line limit")
        return data.readExactly(size.toInt())
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

    /** An archive that does not read is one bad bundle, not a crash. */
    private inline fun <T> invalidBundle(block: () -> T): T = try {
        block()
    } catch (failure: CatalogImportException) {
        throw failure
    } catch (failure: Exception) {
        throw CatalogImportException(failure.message ?: "invalid catalog bundle")
    }

    private class Manifest(
        val fromEpoch: Long,
        val toEpoch: Long,
        val event: Event,
    )

    private class Scan(val manifest: Manifest, val verified: Map<String, String>, val hasDiff: Boolean, val bytes: Long)

    /** The diff without its listings: proofs, profiles, stacks, the catalog relay list, and deletes. */
    private class Diff(val events: List<Event>, val deletes: List<CatalogDelete>) {
        companion object {
            val EMPTY = Diff(emptyList(), emptyList())
        }
    }

    companion object {
        /** `<64 hex> <member name>`; a longer line is malformed. */
        private const val INDEX_LINE_BYTES = 4_096L

        /** Calls [onEvent] and [onDelete] for each valid line of `diff.jsonl`. A line that is not valid is skipped. */
        internal fun readDiff(
            input: InputStream,
            maxLine: Long,
            skipped: MutableList<String>,
            onEvent: (Event) -> Unit,
            onDelete: (CatalogDelete) -> Unit,
        ) {
            input.forEachLine(maxLine) { number, line ->
                val where = "diff line ${number + 1}"
                if (line == null) {
                    skipped += "$where exceeds the line limit"
                    return@forEachLine
                }
                if (line.isEmpty()) return@forEachLine
                val json = runCatching { JSONObject(line) }.getOrNull()
                if (json == null) {
                    skipped += "$where is not JSON"
                    return@forEachLine
                }
                when {
                    json.has("id") -> catalogEvent(line, json)
                        .onSuccess { event -> if (event != null) onEvent(event) }
                        .onFailure { skipped += "$where: ${it.message}" }
                    json.has("delete") -> {
                        val ids = json.optJSONArray("delete")
                        val coordinate = json.optJSONObject("delete")
                        when {
                            ids != null -> for (index in 0 until ids.length()) {
                                val appId = ids.optString(index)
                                if (appId.isBlank()) skipped += "$where has a blank app id" else onDelete(CatalogDelete.Listing(appId))
                            }
                            coordinate != null -> {
                                val kind = coordinate.optInt("kind", -1)
                                val pubkey = coordinate.optString("pubkey")
                                val d = coordinate.optString("d")
                                val addressable = kind in 30_000..39_999
                                if (kind < 0 || !Hex.isHex(pubkey, 64) || !coordinate.has("d") || (addressable && d.isBlank())) {
                                    skipped += "$where is an invalid delete"
                                } else {
                                    onDelete(CatalogDelete.Coordinate(kind, pubkey, d))
                                }
                            }
                            else -> skipped += "$where is an invalid delete"
                        }
                    }
                    json.optBoolean("banned") -> {
                        if (json.optString("app_id").isBlank()) skipped += "$where is an invalid ban"
                    }
                    else -> skipped += "$where is not a known line"
                }
            }
        }

        /** A verified event, null for a proof that is not acceptable, or a failure naming why it was skipped. */
        private fun catalogEvent(line: String, json: JSONObject): Result<Event?> = runCatching {
            val event = Event.parse(line)
            if (!event.verify()) error("event ${event.id} failed verification")
            if (event.kind == Kinds.IdentityProof && !ListingRows.acceptableProof(event)) null else event
        }.recoverCatching { failure ->
            if (json.optInt("kind", -1) == Kinds.IdentityProof) null else throw failure
        }

        private fun validStack(event: Event, catalogPubkey: String, skipped: MutableList<String>): Boolean = when {
            event.kind != Kinds.AppStack -> true
            event.pubkey != catalogPubkey -> false.also { skipped += "stack ${event.id} is not signed by the catalog key" }
            event.dTag().isNullOrBlank() -> false.also { skipped += "stack ${event.id} has no d" }
            else -> true
        }

        private fun validStackDelete(delete: CatalogDelete, catalogPubkey: String, skipped: MutableList<String>): Boolean {
            val foreign = delete is CatalogDelete.Coordinate && delete.kind == Kinds.AppStack && delete.pubkey != catalogPubkey
            if (foreign) skipped += "stack delete is not for the catalog key"
            return !foreign
        }

        /** Public `r` tags on the catalog key's kind 10067. Other keys and unusable URLs are skipped. */
        private fun catalogRelays(events: List<Event>, catalogPubkey: String): List<RelayUrl> {
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
         * One operation per logical key among the non-listing [events]. For two events, the newer wins
         * (NIP-01 order). An event beats a delete for the same key, since the event is signed and the
         * delete line is not; [listed] are the app IDs whose listing events arrived.
         */
        private fun validateOperations(
            events: List<Event>,
            deletes: List<CatalogDelete>,
            listed: Set<String>,
            skipped: MutableList<String>,
        ): Diff {
            val listingKeys = HashSet(listed)
            val byKey = LinkedHashMap<String, Event>()
            for (event in events) {
                val key = "${event.kind}:${event.pubkey}:${event.dTag().orEmpty()}"
                val other = byKey[key]
                if (other == null) {
                    byKey[key] = event
                } else {
                    skipped += "duplicate logical key $key"
                    if (event.createdAt > other.createdAt || (event.createdAt == other.createdAt && event.id < other.id)) byKey[key] = event
                }
            }
            val keptDeletes = deletes.filter { delete ->
                val clash = when (delete) {
                    is CatalogDelete.Listing -> !listingKeys.add(delete.appId)
                    is CatalogDelete.Coordinate -> byKey.containsKey("${delete.kind}:${delete.pubkey}:${delete.d}")
                }
                if (clash) skipped += "event and delete for the same key; the delete was skipped"
                !clash
            }
            return Diff(byKey.values.toList(), keptDeletes)
        }
    }
}

/**
 * Calls [block] with each line, numbered from 0, without the line break. A line longer than [maxLine]
 * bytes is passed as null and never held. Only the current line is in memory.
 */
internal fun InputStream.forEachLine(maxLine: Long, block: (number: Int, line: String?) -> Unit) {
    val chunk = ByteArray(64 * 1024)
    var line = ByteArray(1024)
    var length = 0
    var tooLong = false
    var number = 0
    fun emit() {
        var end = length
        if (end > 0 && line[end - 1] == '\r'.code.toByte()) end--
        block(number++, if (tooLong) null else String(line, 0, end, Charsets.UTF_8))
        length = 0
        tooLong = false
        if (line.size > 64 * 1024) line = ByteArray(1024)
    }
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        var start = 0
        while (start < n) {
            var newline = start
            while (newline < n && chunk[newline] != '\n'.code.toByte()) newline++
            val count = newline - start
            if (!tooLong) {
                if (length + count > maxLine) {
                    tooLong = true
                    length = 0
                } else {
                    if (length + count > line.size) line = line.copyOf(maxOf(line.size * 2, length + count))
                    System.arraycopy(chunk, start, line, length, count)
                    length += count
                }
            }
            if (newline < n) emit()
            start = newline + 1
        }
    }
    if (length > 0 || tooLong) emit()
}

/** Reads to the end so a digest sees every byte. */
private fun InputStream.drain() {
    val buffer = ByteArray(8 * 1024)
    while (read(buffer) >= 0) Unit
}
