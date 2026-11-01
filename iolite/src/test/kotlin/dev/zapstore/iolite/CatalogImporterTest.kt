package dev.zapstore.iolite

import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class CatalogImporterTest {
    @Test
    fun importsRelayGoldenFromZero() {
        withStore { store, importer ->
            val bundle = golden("bundle-0-1.tar.zst")
            val result = importer.importBundle(1, bundle, localRelay)
            assertEquals(0, result.from)
            assertEquals(1, result.to)
            val app = store.apps().single()
            assertEquals("com.example.app", app.appId)
            assertEquals("1.0.0", app.version)
            assertEquals(100, app.versionCode)
            assertEquals(1, store.catalog(1)!!.epoch)
            assertEquals(listOf("ws://127.0.0.1:3334"), store.catalog(1)!!.relays.map { it.url })
            assertEquals(manifestPubkey(bundle), store.catalog(1)!!.manifestPubkey)
        }
    }

    @Test
    fun importsCoalescedFromZeroToTwo() {
        withStore { store, importer ->
            val result = importer.importBundle(1, golden("bundle-0-2.tar.zst"), localRelay)
            assertEquals(0, result.from)
            assertEquals(2, result.to)
            val app = store.apps().single()
            assertEquals("com.example.app", app.appId)
            assertEquals("1.1.0", app.version)
            assertEquals(110, app.versionCode)
            assertEquals(2, store.catalog(1)!!.epoch)
        }
    }

    @Test
    fun appliesAdjacentDeltaFromOneToTwo() {
        withStore { store, importer ->
            importer.importBundle(1, golden("bundle-0-1.tar.zst"), localRelay)
            val result = importer.importBundle(1, golden("bundle-1-2.tar.zst"))
            assertEquals(1, result.from)
            assertEquals(2, result.to)
            val app = store.apps().single()
            assertEquals("1.1.0", app.version)
            assertEquals(110, app.versionCode)
            assertEquals(2, store.catalog(1)!!.epoch)
        }
    }

    @Test
    fun wipeClearsAppsAndEpoch() {
        withStore { store, importer ->
            importer.importBundle(1, golden("bundle-0-1.tar.zst"), localRelay)
            assertEquals(1, store.catalog(1)!!.epoch)
            assertEquals(1, store.apps().size)
            store.wipe()
            assertEquals(0, store.catalog(1)!!.epoch)
            assertEquals(0, store.apps().size)
            assertNull(store.catalog(1)!!.manifestPubkey)
            assertEquals(listOf("ws://127.0.0.1:3334"), store.catalog(1)!!.relays.map { it.url })
        }
    }

    @Test
    fun fromZeroReplacesLeftoverListings() {
        withStore { store, importer ->
            store.insertFixtureCatalog()
            store.write { tx ->
                tx.db.prepare(
                    """
                    INSERT INTO apps (
                        id, catalog_id, app_id, certificate_hash, app_event_created_at, pubkey, event_pubkey,
                        name, summary, repository, version, version_code, channel, metadata
                    ) VALUES (?, 1, 'com.example.leftover', ?, 1, NULL, ?, 'Leftover', '', NULL, '9.0.0', 9, 'main', '{}')
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindBlob(1, ByteArray(32) { 1 })
                    statement.bindBlob(2, ByteArray(32) { 2 })
                    statement.bindBlob(3, ByteArray(32) { 3 })
                    statement.step()
                }
            }
            importer.importBundle(1, golden("bundle-0-1.tar.zst"))
            assertEquals(listOf("com.example.app"), store.apps().map { it.appId })
        }
    }

    @Test
    fun observeAppsEmitsAfterImport() = runBlocking {
        val dir = File("build/tmp/iolite-observe-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            openConnection = ::JdbcSqliteConnection,
        )
        iolite.importCatalogUpdate(1, golden("bundle-0-1.tar.zst"), localRelay)
        val apps = iolite.observeApps().first { it.isNotEmpty() }
        assertEquals("com.example.app", apps.single().appId)
        iolite.close()
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun deviceListsArePrivateCatalogCoordinates() = runBlocking {
        val dir = File("build/tmp/iolite-lists-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val device = LocalSigner(Hex.decode("00".repeat(31) + "07"))
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            signer = device,
            openConnection = ::JdbcSqliteConnection,
        )
        iolite.importCatalogUpdate(1, golden("bundle-0-1.tar.zst"), localRelay)
        val event = iolite.setDeviceList(DeviceEvents.BOOKMARKS, listOf("com.example.app", "org.other", "com.example.app"))
        assertEquals(listOf("com.example.app", "org.other"), iolite.observeDeviceList(DeviceEvents.BOOKMARKS).first { it.isNotEmpty() })
        assertEquals("Saved Apps", event.tagValue("name"))
        assertTrue(event.tags.none { it.firstOrNull() == "a" })
        val owner = device.publicKey
        assertEquals(
            listOf("32267:$owner:com.example.app", "32267:$owner:org.other"),
            JSONArray(device.decryptFromSelf(event.content)).let { array -> (0 until array.length()).map(array::getString) },
        )
        iolite.setDeviceList(DeviceEvents.BOOKMARKS, emptyList())
        assertEquals(emptyList<String>(), iolite.observeDeviceList(DeviceEvents.BOOKMARKS).first { it.isEmpty() })
        assertEquals(emptyList<String>(), iolite.observeDeviceList(DeviceEvents.UPDATE_IGNORE).first())
        iolite.close()
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun observeAppAndSearchReadImportedListing() = runBlocking {
        val dir = File("build/tmp/iolite-query-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            openConnection = ::JdbcSqliteConnection,
        )
        iolite.importCatalogUpdate(1, golden("bundle-0-1.tar.zst"), localRelay)
        val app = iolite.observeApp("com.example.app").first { it != null }!!
        assertEquals("com.example.app", app.appId)
        assertEquals(listOf("com.example.app"), iolite.apps(AppFilter(search = app.name)).map { it.appId })
        assertEquals(emptyList<AppRecord>(), iolite.apps(AppFilter(search = "nomatch")))
        iolite.close()
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun bundledImportThenSyncUsesStoredEndpoint() = runBlocking {
        val dir = File("build/tmp/iolite-sync-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val requested = mutableListOf<String>()
        val http = HttpTransport { url, _ ->
            requested += url
            when (url) {
                "http://127.0.0.1:3334/v1/bundle?from=1" -> HttpResponse(304, emptyMap(), null)
                else -> error("unexpected catalog URL $url")
            }
        }
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            http = http,
            openConnection = ::JdbcSqliteConnection,
        )
        val bundle = golden("bundle-0-1.tar.zst")
        val first = iolite.syncCatalog(catalogId = 1, bundled = bundle, endpoint = localRelay)
        val imported = first.imported!!
        assertEquals(0, imported.from)
        assertEquals(1, imported.to)
        assertEquals(bundle.size.toLong(), first.bytes)
        assertEquals(manifestPubkey(bundle), iolite.defaultCatalog().manifestPubkey)
        assertEquals(listOf("ws://127.0.0.1:3334"), iolite.defaultCatalog().relays.map { it.url })
        val unchanged = iolite.syncCatalog()
        assertNull(unchanged.imported)
        assertTrue(unchanged.notModified)
        assertEquals(
            listOf(
                "http://127.0.0.1:3334/v1/bundle?from=1",
                "http://127.0.0.1:3334/v1/bundle?from=1",
            ),
            requested,
        )
        iolite.close()
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun emptyCatalogFetchesFirstBundleFromEndpoint() = runBlocking {
        val dir = File("build/tmp/iolite-bootstrap-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val bundle = golden("bundle-0-1.tar.zst")
        val requested = mutableListOf<String>()
        val http = HttpTransport { url, _ ->
            requested += url
            when (url) {
                "http://127.0.0.1:3334/v1/bundle?from=0" -> HttpResponse(200, emptyMap(), bundle)
                else -> error("unexpected catalog URL $url")
            }
        }
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            http = http,
            openConnection = ::JdbcSqliteConnection,
        )
        val first = iolite.syncCatalog(catalogId = 1, bundled = ByteArray(0), endpoint = localRelay)
        val imported = first.imported!!
        assertEquals(0, imported.from)
        assertEquals(1, imported.to)
        assertEquals(bundle.size.toLong(), first.bytes)
        assertEquals(manifestPubkey(bundle), iolite.defaultCatalog().manifestPubkey)
        assertEquals(listOf("ws://127.0.0.1:3334"), iolite.defaultCatalog().relays.map { it.url })
        assertEquals(listOf("http://127.0.0.1:3334/v1/bundle?from=0"), requested)
        iolite.close()
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun fullSnapshotForAStaleFromReplacesTheCatalog() = runBlocking {
        val dir = File("build/tmp/iolite-snapshot-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val http = HttpTransport { url, _ ->
            when (url) {
                "http://127.0.0.1:3334/v1/bundle?from=1" -> HttpResponse(200, emptyMap(), golden("bundle-0-2.tar.zst"))
                else -> error("unexpected catalog URL $url")
            }
        }
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            http = http,
            openConnection = ::JdbcSqliteConnection,
        )
        iolite.importCatalogUpdate(1, golden("bundle-0-1.tar.zst"), localRelay)
        val result = iolite.syncCatalog()
        assertEquals(0, result.imported!!.from)
        assertEquals(2, iolite.defaultCatalog().epoch)
        iolite.close()
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun relayBehindLocalEpochIsAnErrorWithoutRefetch() = runBlocking {
        val dir = File("build/tmp/iolite-behind-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val requested = mutableListOf<String>()
        val http = HttpTransport { url, _ ->
            requested += url
            HttpResponse(400, emptyMap(), "from is newer than the current catalog epoch".toByteArray())
        }
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            http = http,
            openConnection = ::JdbcSqliteConnection,
        )
        iolite.importCatalogUpdate(1, golden("bundle-0-2.tar.zst"), localRelay)
        assertThrows(CatalogImportException::class.java) { iolite.syncCatalog() }
        assertThrows(CatalogImportException::class.java) { iolite.importCatalogUpdate(1, golden("bundle-0-1.tar.zst")) }
        assertEquals(2, iolite.defaultCatalog().epoch)
        assertEquals(listOf("http://127.0.0.1:3334/v1/bundle?from=2"), requested)
        iolite.close()
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun badLinesEventsAndMembersAreSkippedAndTheRestImports() {
        var cleanApps = emptyList<String>()
        withStore { store, importer ->
            importer.importBundle(1, golden("bundle-0-2.tar.zst"), localRelay)
            cleanApps = store.apps().map { it.appId }.sorted()
        }
        withStore { store, importer ->
            var corrupted = ""
            val bundle = rebuild(golden("bundle-0-2.tar.zst")) { members, indexed ->
                if (!indexed) {
                    val diff = members.getValue("diff.jsonl").toString(Charsets.UTF_8).trimEnd()
                    val forged = JSONObject(diff.lineSequence().first { it.contains("\"id\"") }).put("content", "forged")
                    members["diff.jsonl"] = "$diff\nnot json\n$forged\n".toByteArray()
                } else {
                    corrupted = members.keys.first { it.endsWith("/about") || it.endsWith(".webp") }
                    members[corrupted] = "tampered".toByteArray()
                }
            }
            val result = importer.importBundle(1, bundle, localRelay)
            assertEquals(2, result.to)
            assertEquals(cleanApps, store.apps().map { it.appId }.sorted())
            assertEquals(3, result.skipped.size)
            assertTrue(result.skipped.any { it.endsWith("is not JSON") })
            assertTrue(result.skipped.any { it.contains("failed verification") || it.contains("invalid") })
            assertTrue(result.skipped.contains("hash mismatch for $corrupted"))
        }
    }

    @Test
    fun catalogRelayListReplacesTheBootstrapEndpoint() {
        withStore { store, importer ->
            val bundle = withCatalogRelays(
                golden("bundle-0-1.tar.zst"),
                listOf(
                    "https://not-a-websocket",
                    "wss://relay.zapstore.dev/",
                    "ws://abcdefghijklmnopqrstuvwxyz234567.onion",
                ),
            )
            importer.importBundle(1, bundle, "wss://relay.example.com".normalizeRelayUrl())
            val catalog = store.catalog(1)!!
            assertEquals(
                listOf("wss://relay.zapstore.dev", "ws://abcdefghijklmnopqrstuvwxyz234567.onion"),
                catalog.relays.map { it.url },
            )
            assertEquals("wss://relay.zapstore.dev", catalog.relayUrl.url)
        }
    }

    @Test
    fun laterCatalogRelayListUpdatesStoredRelays() {
        withStore { store, importer ->
            importer.importBundle(1, golden("bundle-0-1.tar.zst"), "wss://relay.example.com".normalizeRelayUrl())
            val delta = withCatalogRelays(golden("bundle-1-2.tar.zst"), listOf("wss://relay.zapstore.dev"))
            importer.importBundle(1, delta)
            assertEquals(listOf("wss://relay.zapstore.dev"), store.catalog(1)!!.relays.map { it.url })
            assertEquals(2, store.catalog(1)!!.epoch)
        }
    }

    @Test
    fun foreignRelayListLeavesTheBootstrapEndpoint() {
        withStore { store, importer ->
            val bundle = withCatalogRelays(
                golden("bundle-0-1.tar.zst"),
                listOf("wss://relay.example.com"),
                relaySigner = Hex.decode("02".padStart(64, '0')),
            )
            importer.importBundle(1, bundle, localRelay)
            assertEquals(listOf(localRelay.url), store.catalog(1)!!.relays.map { it.url })
        }
    }

    @Test
    fun bundledRelayListIsTheNextFetch() = runBlocking {
        val dir = File("build/tmp/iolite-relays-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val requested = mutableListOf<String>()
        val http = HttpTransport { url, _ ->
            requested += url
            when (url) {
                "https://relay.zapstore.dev/v1/bundle?from=1" -> HttpResponse(304, emptyMap(), null)
                else -> error("unexpected catalog URL $url")
            }
        }
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            http = http,
            openConnection = ::JdbcSqliteConnection,
        )
        val bundle = withCatalogRelays(golden("bundle-0-1.tar.zst"), listOf("wss://relay.zapstore.dev"))
        iolite.syncCatalog(catalogId = 1, bundled = bundle, endpoint = "wss://relay.example.com".normalizeRelayUrl())
        assertEquals(listOf("wss://relay.zapstore.dev"), iolite.defaultCatalog().relays.map { it.url })
        iolite.syncCatalog()
        assertEquals(
            listOf(
                "https://relay.zapstore.dev/v1/bundle?from=1",
                "https://relay.zapstore.dev/v1/bundle?from=1",
            ),
            requested,
        )
        iolite.close()
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun loopbackFetchStaysThere() = runBlocking {
        val dir = File("build/tmp/iolite-loopback-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val requested = mutableListOf<String>()
        val http = HttpTransport { url, _ ->
            requested += url
            HttpResponse(304, emptyMap(), null)
        }
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            http = http,
            openConnection = ::JdbcSqliteConnection,
        )
        val bundle = withCatalogRelays(golden("bundle-0-1.tar.zst"), listOf("wss://relay.zapstore.dev"))
        iolite.syncCatalog(catalogId = 1, bundled = bundle, endpoint = localRelay)
        assertEquals(listOf(localRelay.url), iolite.defaultCatalog().relays.map { it.url })
        iolite.syncCatalog()
        assertEquals(
            listOf("http://127.0.0.1:3334/v1/bundle?from=1", "http://127.0.0.1:3334/v1/bundle?from=1"),
            requested,
        )
        iolite.close()
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun syncRelayUsesOnionOnlyWhenAsked() {
        val clearnet = "wss://relay.zapstore.dev".normalizeRelayUrl()
        val onion = "ws://abcdefghijklmnopqrstuvwxyz234567.onion".normalizeRelayUrl()
        val catalog = CatalogRecord(
            id = 1,
            relayUrl = clearnet,
            relays = listOf(clearnet, onion),
            position = 0,
            isPrivate = false,
            manifestPubkey = null,
            epoch = 1,
        )
        assertEquals(onion.url, catalog.syncRelay(useOnion = true).url)
        assertEquals(clearnet.url, catalog.syncRelay(useOnion = false).url)
    }

    @Test
    fun badProofDoesNotRejectTheBundle() {
        withStore { store, importer ->
            store.insertFixtureCatalog()
            val secret = Hex.decode("22".repeat(32))
            val kept = Event.sign(
                secret,
                1_700_000_000,
                Kinds.IdentityProof,
                listOf(
                    listOf("d", "cd".repeat(32)),
                    listOf("signature", "not-a-certificate-signature"),
                    listOf("expiry", "1800000000"),
                    listOf("cert", "!!!!"),
                ),
                "",
            )
            val malformed = Event.sign(
                secret,
                1_700_000_001,
                Kinds.IdentityProof,
                listOf(listOf("d", "not-a-hash")),
                "",
            )
            val unsigned = Event(
                id = "aa".repeat(32),
                pubkey = "bb".repeat(32),
                createdAt = 1_700_000_002,
                kind = Kinds.IdentityProof,
                tags = listOf(listOf("d", "ee".repeat(32)), listOf("signature", "x"), listOf("expiry", "1800000000")),
                content = "",
                sig = "11".repeat(64),
            )
            val bundle = withCatalogRelays(
                golden("bundle-0-1.tar.zst"),
                listOf("ws://127.0.0.1:3334"),
                extra = listOf(kept, malformed, unsigned),
            )
            importer.importBundle(1, bundle, localRelay)
            assertEquals("com.example.app", store.apps().single().appId)
            assertEquals(listOf(kept.pubkey), proofPubkeys(store))
        }
    }

    @Test
    fun pinnedKeyRejectsADifferentSigner() {
        withStore { store, importer ->
            store.insertFixtureCatalog()
            store.pinManifestPubkeyIfAbsent(1, "aa".repeat(32))
            val failure = runCatching { importer.importBundle(1, golden("bundle-0-1.tar.zst")) }.exceptionOrNull()
            assertTrue(failure is CatalogImportException)
            assertEquals("manifest pubkey does not match the pinned key", failure!!.message)
            assertEquals("aa".repeat(32), store.catalog(1)!!.manifestPubkey)
            assertEquals(0, store.catalog(1)!!.epoch)
        }
    }

    @Test
    fun importsCatalogStackForCuratedStacks() {
        withStore { store, _ ->
            store.insertFixtureCatalog()
            val secret = Hex.decode("00".repeat(31) + "01")
            val otherSecret = Hex.decode("00".repeat(31) + "03")
            val device = DeviceProfile(abis = listOf("arm64-v8a"), sdk = 34)
            val catalogStack = stackEvent(secret, "editors")
            val otherStack = stackEvent(otherSecret, "mine")
            store.pinManifestPubkeyIfAbsent(1, catalogStack.pubkey)
            store.ingest(listOf(otherStack), IngestContext(device, null))
            val catalog = store.catalog(1)!!
            val skipped = ArrayList<String>()
            store.importCatalog(
                catalog = catalog,
                events = listOf(otherStack),
                deletes = emptyList(),
                replaceAll = false,
                toEpoch = 1,
                device = device,
                now = 1_700_000_000,
                skipped = skipped,
            )
            assertEquals(1, skipped.size)
            assertEquals(emptyList<StackRecord>(), store.stacks(catalogStack.pubkey))
            store.importCatalog(
                catalog = catalog,
                events = listOf(catalogStack, stackEvent(secret, "desktop", platform = "android-x86_64")),
                deletes = emptyList(),
                replaceAll = true,
                toEpoch = 1,
                device = device,
                now = 1_700_000_000,
            )
            val curated = store.stacks(catalogStack.pubkey)
            assertEquals(listOf("editors"), curated.map { it.identifier })
            assertEquals(listOf("com.example.app"), curated.single().appIds)
            assertEquals(listOf("mine"), store.stacks(otherStack.pubkey).map { it.identifier })
            store.importCatalog(
                catalog = store.catalog(1)!!,
                events = emptyList(),
                deletes = listOf(CatalogDelete.Coordinate(Kinds.AppStack, catalogStack.pubkey, "editors")),
                replaceAll = false,
                toEpoch = 2,
                device = device,
                now = 1_700_000_000,
            )
            assertEquals(emptyList<StackRecord>(), store.stacks(catalogStack.pubkey))
            assertEquals(listOf("mine"), store.stacks(otherStack.pubkey).map { it.identifier })
        }
    }

    @Test
    fun listingImportRequiresManifestPubkey() {
        withStore { store, _ ->
            store.insertFixtureCatalog()
            val secret = Hex.decode("11".repeat(32))
            val failure = runCatching {
                store.importCatalog(
                    catalog = store.catalog(1)!!,
                    events = fixtureListing(secret, "pub.ditto.app", "Ditto", "1.0.0", 10).events,
                    deletes = emptyList(),
                    replaceAll = false,
                    toEpoch = 1,
                    device = DeviceProfile(abis = listOf("arm64-v8a"), sdk = 34),
                    now = 1_700_000_000,
                )
            }.exceptionOrNull()
            assertTrue(failure is CatalogImportException)
            assertEquals("catalog manifest pubkey is missing", failure!!.message)
        }
    }

    @Test
    fun appIdUsesManifestPubkey() {
        withStore { store, _ ->
            store.insertFixtureCatalog()
            val secret = Hex.decode("11".repeat(32))
            import(store, fixtureListing(secret, "pub.ditto.app", "Ditto", "1.0.0", 10).events, 1)
            val id = blob(store, "SELECT id FROM apps WHERE app_id = 'pub.ditto.app'")
            assertArrayEquals(ListingRows.appRowId(Hex.decode(fixtureManifest), "pub.ditto.app"), id)
        }
    }

    @Test
    fun partialDeltaPatchesStoredListing() {
        withStore { store, _ ->
            store.insertFixtureCatalog()
            val secret = Hex.decode("11".repeat(32))
            val ditto = fixtureListing(secret, "pub.ditto.app", "Ditto", "1.0.0", 10)
            import(store, ditto.events, 1)
            val renamed = appEvent(secret, "pub.ditto.app", "Ditto Nightly", 1_700_000_100, "renamed description")
            import(store, listOf(renamed), 2)
            val app = store.apps().single()
            assertEquals("Ditto Nightly", app.name)
            assertEquals("renamed description", app.description)
            assertEquals("1.0.0", app.version)
            assertEquals(10, app.versionCode)
            assertEquals(2, store.catalog(1)!!.epoch)
        }
    }

    @Test
    fun eachListingKindInsertsAlone() {
        withStore { store, _ ->
            store.insertFixtureCatalog()
            val secret = Hex.decode("11".repeat(32))
            val full = fixtureListing(secret, "pub.ditto.app", "Ditto", "1.0.0", 10)
            import(store, listOf(full.app), 1)
            assertTrue(store.apps().isEmpty())
            assertEquals("Ditto", storedName(store, "pub.ditto.app"))

            import(store, listOf(full.release), 2)
            assertTrue(store.apps().isEmpty())
            assertEquals("1.0.0", storedVersion(store, "pub.ditto.app"))

            import(store, listOf(full.asset), 3)
            val withAsset = store.apps().single()
            assertEquals("Ditto", withAsset.name)
            assertEquals("1.0.0", withAsset.version)
            assertEquals(10, withAsset.versionCode)
            assertEquals("cd".repeat(32), withAsset.certificateHash)
            assertEquals(full.asset.tagValue("url"), withAsset.downloadUrl)
            assertEquals("notes", withAsset.releaseNotes)

            val renamed = appEvent(secret, "pub.ditto.app", "Ditto Nightly", 1_700_000_200, "renamed description")
            import(store, listOf(renamed), 4)
            val afterApp = store.apps().single()
            assertEquals("Ditto Nightly", afterApp.name)
            assertEquals("cd".repeat(32), afterApp.certificateHash)
            assertEquals(10, afterApp.versionCode)
            assertEquals("notes", afterApp.releaseNotes)
        }
    }

    @Test
    fun releaseAndAssetInsertWithoutApp() {
        withStore { store, _ ->
            store.insertFixtureCatalog()
            val secret = Hex.decode("11".repeat(32))
            val full = fixtureListing(secret, "pub.ditto.app", "Ditto", "1.0.0", 10)
            import(store, listOf(full.release, full.asset), 1)
            assertTrue(store.apps().isEmpty())
            assertEquals("", storedName(store, "pub.ditto.app"))
            assertEquals("1.0.0", storedVersion(store, "pub.ditto.app"))
        }
    }

    @Test
    fun variantAssetIsTheListing() {
        withStore { store, _ ->
            store.insertFixtureCatalog()
            val secret = Hex.decode("11".repeat(32))
            val ditto = fixtureListing(secret, "pub.ditto.app", "Ditto", "1.0.0", 10)
            import(store, ditto.events, 1)
            val asset = assetEvent(
                secret, "pub.ditto.app", "1.1.0", 11, 1_700_000_100,
                hash = "ef".repeat(32), variant = "google",
            )
            import(store, listOf(asset), 2)
            val app = store.apps().single()
            assertEquals("1.1.0", app.version)
            assertEquals(11, app.versionCode)
            assertEquals(asset.tagValue("x"), app.apkHash)
        }
    }

    @Test
    fun assetOnlyDeltaUpdatesVersionCode() {
        withStore { store, _ ->
            store.insertFixtureCatalog()
            val secret = Hex.decode("11".repeat(32))
            val ditto = fixtureListing(secret, "pub.ditto.app", "Ditto", "1.0.0", 10)
            import(store, ditto.events, 1)
            val asset = assetEvent(secret, "pub.ditto.app", "9.9.9", 42, 1_700_000_100, hash = "ef".repeat(32))
            import(store, listOf(asset), 2)
            val app = store.apps().single()
            assertEquals("Ditto", app.name)
            assertEquals("9.9.9", app.version)
            assertEquals(42, app.versionCode)
            assertEquals(asset.tagValue("x"), app.apkHash)
        }
    }

    @Test
    fun ancestorProofCountsAndExtraCertificateHashesAreIgnored() {
        withStore { store, _ ->
            store.insertFixtureCatalog()
            val listing = Hex.decode("11".repeat(32))
            val ancestorOwner = Hex.decode("33".repeat(32))
            val signerOwner = Hex.decode("44".repeat(32))
            val signer = "cd".repeat(32)
            val ancestor = "aa".repeat(32)
            val asset = assetEvent(
                listing, "pub.ditto.app", "1.0.0", 10, 1_700_000_000,
                ancestors = listOf(ancestor),
                extraCertificates = listOf("ee".repeat(32)),
            )
            val app = appEvent(listing, "pub.ditto.app", "Ditto", 1_700_000_000)
            import(store, listOf(app, asset, proofEvent(ancestorOwner, ancestor, 1_600_000_000)), 1)
            val withAncestor = store.apps().single()
            assertEquals(signer, withAncestor.certificateHash)
            assertEquals(listOf(signer, ancestor), withAncestor.certificateChain())
            assertNull(withAncestor.proofPubkey)

            import(store, listOf(proofEvent(signerOwner, signer, 1_650_000_000)), 2)
            val current = store.apps().single()
            assertEquals(Hex.encode(Crypto.xOnlyPubkey(signerOwner)), current.proofPubkey)
            val proof = store.proof(current.catalogId, signer, current.proofPubkey!!)!!
            assertEquals("00", proof.signature)
            assertEquals(1_650_000_000L, proof.createdAt)
            assertEquals(1_800_000_000L, proof.expiry)
        }
    }

    @Test
    fun malformedListingDoesNotAbortSync() {
        withStore { store, _ ->
            store.insertFixtureCatalog()
            val secret = Hex.decode("11".repeat(32))
            val ditto = fixtureListing(secret, "pub.ditto.app", "Ditto", "1.0.0", 10)
            import(store, ditto.events, 1)
            val broken = Event.sign(
                secret,
                1_700_000_050,
                Kinds.Release,
                listOf(
                    listOf("a", "32267:${"ab".repeat(32)}:other.app"),
                    listOf("i", "pub.ditto.app"),
                    listOf("version", "3.0.0"),
                    listOf("d", "pub.ditto.app@3.0.0"),
                    listOf("c", "main"),
                    listOf("e", ditto.asset.id),
                ),
                "notes",
            )
            val renamed = appEvent(secret, "pub.ditto.app", "Ditto Nightly", 1_700_000_050, "renamed description")
            val other = fixtureListing(secret, "com.example.keep", "Keep", "2.0.0", 20, 1_700_000_200)
            val stray = assetEvent(secret, "com.example.unknown", "0.0.1", 1, 1_700_000_300)
            import(store, listOf(broken, renamed, stray) + other.events, 2)
            val apps = store.apps().associateBy { it.appId }
            assertEquals(setOf("pub.ditto.app", "com.example.keep"), apps.keys)
            assertEquals("Ditto Nightly", apps.getValue("pub.ditto.app").name)
            assertEquals("notes", apps.getValue("pub.ditto.app").releaseNotes)
            assertEquals("1.0.0", apps.getValue("pub.ditto.app").version)
            assertEquals(10, apps.getValue("pub.ditto.app").versionCode)
            assertEquals("Keep", apps.getValue("com.example.keep").name)
            assertEquals("", storedName(store, "com.example.unknown"))
            assertEquals("0.0.1", storedVersion(store, "com.example.unknown"))
            assertEquals(2, store.catalog(1)!!.epoch)
        }
    }

    @Test
    fun diffAcceptsProfileDeletesWithEmptyDAndBanLines() {
        val pubkey = "ab".repeat(32)
        val diff = listOf(
            """{"kind":0,"pubkey":"$pubkey","d":""}""",
            """{"kind":30509,"pubkey":"$pubkey","d":"${"cd".repeat(32)}"}""",
            """{"delete":["com.example.spy"]}""",
            """{"banned":true,"app_id":"com.example.spy","reason":"Records the microphone."}""",
        ).joinToString("\n")
        val (events, deletes) = CatalogImporter.parseDiff(TarMember("diff.jsonl", diff.toByteArray()), 1_000_000)
        assertEquals(emptyList<Event>(), events)
        assertEquals(
            listOf(
                CatalogDelete.Coordinate(0, pubkey, ""),
                CatalogDelete.Coordinate(Kinds.IdentityProof, pubkey, "cd".repeat(32)),
                CatalogDelete.Listing("com.example.spy"),
            ),
            deletes,
        )
        val skipped = ArrayList<String>()
        val mixed = listOf(
            """{"kind":30267,"pubkey":"$pubkey","d":""}""",
            "not json",
            """{"delete":["com.example.kept"]}""",
        ).joinToString("\n")
        val (_, kept) = CatalogImporter.parseDiff(TarMember("diff.jsonl", mixed.toByteArray()), 1_000_000, skipped)
        assertEquals(listOf(CatalogDelete.Listing("com.example.kept")), kept)
        assertEquals(listOf("diff line 1 is an invalid delete", "diff line 2 is not JSON"), skipped)
    }

    private fun storedName(store: IoliteStore, appId: String): String = store.read { db ->
        db.prepare("SELECT name FROM apps WHERE app_id = ?").use { statement ->
            statement.bindText(1, appId)
            check(statement.step())
            statement.getText(0)
        }
    }

    private fun storedVersion(store: IoliteStore, appId: String): String = store.read { db ->
        db.prepare("SELECT version FROM apps WHERE app_id = ?").use { statement ->
            statement.bindText(1, appId)
            check(statement.step())
            statement.getText(0)
        }
    }

    private fun proofPubkeys(store: IoliteStore): List<String> = store.read { db ->
        db.prepare("SELECT pubkey FROM certificate_proofs ORDER BY created_at").use { statement ->
            buildList {
                while (statement.step()) add(Hex.encode(statement.getBlob(0)))
            }
        }
    }

    private fun blob(store: IoliteStore, sql: String): ByteArray = store.read { db ->
        db.prepare(sql).use { statement ->
            check(statement.step())
            statement.getBlob(0)
        }
    }

    private fun import(store: IoliteStore, events: List<Event>, epoch: Long) {
        if (store.catalog(1)!!.manifestPubkey == null) {
            store.pinManifestPubkeyIfAbsent(1, fixtureManifest)
        }
        store.importCatalog(
            catalog = store.catalog(1)!!,
            events = events,
            deletes = emptyList(),
            replaceAll = false,
            toEpoch = epoch,
            device = DeviceProfile(abis = listOf("arm64-v8a"), sdk = 34),
            now = 1_700_000_000,
        )
    }

    private data class FixtureListing(val app: Event, val release: Event, val asset: Event) {
        val events: List<Event> get() = listOf(app, release, asset)
    }

    private fun fixtureListing(
        secret: ByteArray,
        appId: String,
        name: String,
        version: String,
        versionCode: Long,
        createdAt: Long = 1_700_000_000,
    ): FixtureListing {
        val asset = assetEvent(secret, appId, version, versionCode, createdAt)
        val pubkey = asset.pubkey
        val release = Event.sign(
            secret,
            createdAt,
            Kinds.Release,
            listOf(
                listOf("a", "32267:$pubkey:$appId"),
                listOf("i", appId),
                listOf("version", version),
                listOf("d", "$appId@$version"),
                listOf("c", "main"),
                listOf("e", asset.id),
            ),
            "notes",
        )
        return FixtureListing(appEvent(secret, appId, name, createdAt), release, asset)
    }

    private fun appEvent(
        secret: ByteArray,
        appId: String,
        name: String,
        createdAt: Long,
        content: String = "nightly build",
    ): Event = Event.sign(
        secret,
        createdAt,
        Kinds.App,
        listOf(listOf("d", appId), listOf("name", name), listOf("summary", "short")),
        content,
    )

    private fun assetEvent(
        secret: ByteArray,
        appId: String,
        version: String,
        versionCode: Long,
        createdAt: Long,
        hash: String = "ab".repeat(32),
        variant: String? = null,
        certificate: String = "cd".repeat(32),
        ancestors: List<String> = emptyList(),
        extraCertificates: List<String> = emptyList(),
    ): Event = Event.sign(
        secret,
        createdAt,
        Kinds.Asset,
        buildList {
            add(listOf("i", appId))
            add(listOf("x", hash))
            add(listOf("version", version))
            add(listOf("m", "application/vnd.android.package-archive"))
            add(listOf("f", "android-arm64-v8a"))
            add(listOf("version_code", versionCode.toString()))
            add(listOf("apk_certificate_hash", certificate))
            extraCertificates.forEach { add(listOf("apk_certificate_hash", it)) }
            ancestors.forEach { add(listOf("apk_certificate_ancestor", it)) }
            add(listOf("url", "https://example.invalid/$appId.apk"))
            add(listOf("filename", "$appId.apk"))
            if (variant != null) add(listOf("variant", variant))
        },
        "",
    )

    private fun proofEvent(secret: ByteArray, certificate: String, createdAt: Long): Event = Event.sign(
        secret,
        createdAt,
        Kinds.IdentityProof,
        listOf(
            listOf("d", certificate),
            listOf("signature", "00"),
            listOf("expiry", "1800000000"),
        ),
        "",
    )

    private fun stackEvent(secret: ByteArray, identifier: String, platform: String = "android-arm64-v8a"): Event {
        val pubkey = Hex.encode(Crypto.xOnlyPubkey(secret))
        return Event.sign(
            secret,
            1_700_000_000,
            Kinds.AppStack,
            listOf(
                listOf("d", identifier),
                listOf("name", identifier),
                listOf("a", "32267:$pubkey:com.example.app"),
                listOf("f", platform),
            ),
            "",
        )
    }

    private fun withStore(block: (IoliteStore, CatalogImporter) -> Unit) {
        val dir = File("build/tmp/iolite-import-${System.nanoTime()}").apply { mkdirs() }
        val store = jdbcStore(File(dir, "iolite.db").absolutePath)
        val importer = CatalogImporter(
            store = store,
            iconsDir = File(dir, "icons"),
            config = IoliteConfig(),
            device = DeviceProfile(abis = listOf("arm64-v8a"), sdk = 34),
        )
        try {
            block(store, importer)
        } finally {
            store.close()
        }
    }

    private fun manifestPubkey(bundle: ByteArray): String {
        val config = IoliteConfig()
        val members = TarZstd.read(
            ByteArrayInputStream(bundle),
            maxCompressedBytes = config.maxCompressedBytes,
            maxUncompressedBytes = config.maxUncompressedBytes,
            maxMembers = config.maxMembers,
            maxMemberBytes = config.maxUncompressedBytes,
        )
        return Event.parse(members.first().data.toString(Charsets.UTF_8)).pubkey
    }

    private fun golden(name: String): ByteArray {
        val file = File(clientFixtures(), name)
        check(file.isFile) { "missing generated bundle $name" }
        return file.readBytes()
    }

    /** Re-signs [bundle] after [edit] runs on its members, once before the index is rebuilt and once after. */
    private fun rebuild(bundle: ByteArray, edit: (MutableMap<String, ByteArray>, Boolean) -> Unit): ByteArray {
        val config = IoliteConfig()
        val members = TarZstd.read(
            ByteArrayInputStream(bundle),
            maxCompressedBytes = config.maxCompressedBytes,
            maxUncompressedBytes = config.maxUncompressedBytes,
            maxMembers = config.maxMembers,
            maxMemberBytes = config.maxUncompressedBytes,
        ).associate { it.name to it.data }.toMutableMap()
        val manifest = Event.parse(members.getValue("manifest.json").toString(Charsets.UTF_8))
        edit(members, false)
        members["index"] = members.keys
            .filter { it != "manifest.json" && it != "index" }
            .sorted()
            .joinToString("") { name -> "${members.getValue(name).sha256Hex()} $name\n" }
            .toByteArray()
        edit(members, true)
        val signed = Event.sign(
            catalogSecret,
            manifest.createdAt,
            Kinds.Preferences,
            listOf(
                listOf("d", manifest.tagValue("d")!!),
                listOf("from", manifest.tagValue("from")!!),
                listOf("to", manifest.tagValue("to")!!),
                listOf("v", manifest.tagValue("v")!!),
                listOf("file", "index", members.getValue("index").sha256Hex()),
            ),
            "",
        )
        members["manifest.json"] = signed.toJson().toByteArray()
        val order = listOf("manifest.json") + members.keys.filter { it != "manifest.json" }.sorted()
        return packTarZstd(order.map { name -> TarMember(name, members.getValue(name)) })
    }

    private fun withCatalogRelays(
        bundle: ByteArray,
        relays: List<String>,
        relaySigner: ByteArray = catalogSecret,
        extra: List<Event> = emptyList(),
    ): ByteArray {
        val config = IoliteConfig()
        val members = TarZstd.read(
            ByteArrayInputStream(bundle),
            maxCompressedBytes = config.maxCompressedBytes,
            maxUncompressedBytes = config.maxUncompressedBytes,
            maxMembers = config.maxMembers,
            maxMemberBytes = config.maxUncompressedBytes,
        ).associate { it.name to it.data }.toMutableMap()
        val manifest = Event.parse(members.getValue("manifest.json").toString(Charsets.UTF_8))
        check(Hex.encode(Crypto.xOnlyPubkey(catalogSecret)) == manifest.pubkey) {
            "catalog secret does not match the bundle"
        }
        val list = Event.sign(
            relaySigner,
            manifest.createdAt,
            Kinds.CatalogRelayList,
            relays.map { listOf("r", it) },
            "",
        )
        val diff = buildString {
            append(members.getValue("diff.jsonl").toString(Charsets.UTF_8).trimEnd())
            append('\n')
            append(list.toJson())
            append('\n')
            extra.forEach { event ->
                append(event.toJson())
                append('\n')
            }
        }
        members["diff.jsonl"] = diff.toByteArray()
        val index = members.keys
            .filter { it != "manifest.json" && it != "index" }
            .sorted()
            .joinToString("") { name -> "${members.getValue(name).sha256Hex()} $name\n" }
        members["index"] = index.toByteArray()
        val signed = Event.sign(
            catalogSecret,
            manifest.createdAt,
            Kinds.Preferences,
            listOf(
                listOf("d", manifest.tagValue("d")!!),
                listOf("from", manifest.tagValue("from")!!),
                listOf("to", manifest.tagValue("to")!!),
                listOf("v", manifest.tagValue("v")!!),
                listOf("file", "index", members.getValue("index").sha256Hex()),
            ),
            "",
        )
        members["manifest.json"] = signed.toJson().toByteArray()
        val order = listOf("manifest.json") + members.keys.filter { it != "manifest.json" }.sorted()
        return packTarZstd(order.map { name -> TarMember(name, members.getValue(name)) })
    }

    private fun packTarZstd(members: List<TarMember>): ByteArray {
        val raw = ByteArrayOutputStream()
        for (member in members) {
            raw.write(ustarHeader(member.name, member.data.size))
            raw.write(member.data)
            val pad = (512 - (member.data.size % 512)) % 512
            if (pad > 0) raw.write(ByteArray(pad))
        }
        raw.write(ByteArray(1024))
        val compressed = ByteArrayOutputStream()
        ZstdOutputStream(compressed).use { stream -> stream.write(raw.toByteArray()) }
        return compressed.toByteArray()
    }

    private fun ustarHeader(name: String, size: Int): ByteArray {
        val header = ByteArray(512)
        fun put(offset: Int, text: String) {
            text.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        }
        put(0, name)
        put(100, "0000644")
        header[107] = 0
        put(108, "0000000")
        header[115] = 0
        put(116, "0000000")
        header[123] = 0
        put(124, size.toString(8).padStart(11, '0'))
        header[135] = 0
        put(136, "00000000000")
        header[147] = 0
        header[156] = '0'.code.toByte()
        put(257, "ustar")
        header[262] = 0
        put(263, "00")
        for (index in 148 until 156) header[index] = ' '.code.toByte()
        val sum = header.sumOf { it.toInt() and 0xff }
        put(148, sum.toString(8).padStart(6, '0'))
        header[154] = 0
        header[155] = ' '.code.toByte()
        return header
    }

    companion object {
        private val localRelay = "ws://127.0.0.1:3334".normalizeRelayUrl()
        private val fixtureManifest = "ab".repeat(32)
        private val catalogSecret = Hex.decode("01".padStart(64, '0'))
        private val clientFixturesDir: File by lazy { writeClientFixtures() }

        private fun clientFixtures(): File = clientFixturesDir

        /** Same files the webapp imports: tests/fixtures/bundle-<from>-<to>.tar.zst. */
        private fun writeClientFixtures(): File {
            val start = System.getProperty("user.dir") ?: error("user.dir is unset")
            var dir = File(start).canonicalFile
            repeat(6) {
                if (File(dir, "tests/go.mod").isFile) {
                    val proc = ProcessBuilder("go", "run", ".", "bundles")
                        .directory(File(dir, "tests"))
                        .redirectErrorStream(true)
                        .start()
                    val log = proc.inputStream.bufferedReader().readText()
                    check(proc.waitFor() == 0) { "bundles exited ${proc.exitValue()}\n$log" }
                    return File(dir, "tests/fixtures")
                }
                dir = dir.parentFile ?: return@repeat
            }
            error("repo root not found from ${System.getProperty("user.dir")}")
        }
    }
}
