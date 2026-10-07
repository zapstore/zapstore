package dev.zapstore.iolite

import java.io.ByteArrayInputStream
import java.io.File
import java.sql.DriverManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogImporterTest {
    @Test
    fun importsRelayGoldenFromZero() {
        withStore { store, importer ->
            val bundle = golden("from-0-to-1.tar.zst")
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
            val result = importer.importBundle(1, golden("from-0-to-2.tar.zst"), localRelay)
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
            importer.importBundle(1, golden("from-0-to-1.tar.zst"), localRelay)
            val result = importer.importBundle(1, golden("from-1-to-2.tar.zst"))
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
            importer.importBundle(1, golden("from-0-to-1.tar.zst"), localRelay)
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
            importer.importBundle(1, golden("from-0-to-1.tar.zst"))
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
        iolite.importCatalogUpdate(1, golden("from-0-to-1.tar.zst"), localRelay)
        val apps = iolite.observeApps().first { it.isNotEmpty() }
        assertEquals("com.example.app", apps.single().appId)
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
        iolite.importCatalogUpdate(1, golden("from-0-to-1.tar.zst"), localRelay)
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
                "http://127.0.0.1:3334/bundle?from=1" -> HttpResponse(304, emptyMap(), null)
                else -> error("unexpected catalog URL $url")
            }
        }
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            http = http,
            openConnection = ::JdbcSqliteConnection,
        )
        val bundle = golden("from-0-to-1.tar.zst")
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
                "http://127.0.0.1:3334/bundle?from=1",
                "http://127.0.0.1:3334/bundle?from=1",
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
        val bundle = golden("from-0-to-1.tar.zst")
        val requested = mutableListOf<String>()
        val http = HttpTransport { url, _ ->
            requested += url
            when (url) {
                "http://127.0.0.1:3334/bundle?from=0" -> HttpResponse(200, emptyMap(), bundle)
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
        assertEquals(listOf("http://127.0.0.1:3334/bundle?from=0"), requested)
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
    fun pinnedKeyRejectsADifferentSigner() {
        withStore { store, importer ->
            store.insertFixtureCatalog()
            store.pinManifestPubkeyIfAbsent(1, "aa".repeat(32))
            val failure = runCatching { importer.importBundle(1, golden("from-0-to-1.tar.zst")) }.exceptionOrNull()
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
            val rejected = runCatching {
            store.importCatalog(
                catalog = catalog,
                events = listOf(otherStack),
                deletes = emptyList(),
                replaceAll = true,
                toEpoch = 1,
                device = device,
                now = 1_700_000_000,
            )
            }.exceptionOrNull()
            assertTrue(rejected is CatalogImportException)
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
    fun migratesUrlHashesToManifestPubkey() {
        val dir = File("build/tmp/iolite-id-migrate-${System.nanoTime()}").apply { mkdirs() }
        val path = File(dir, "iolite.db").absolutePath
        val manifest = Hex.decode(fixtureManifest)
        val certificate = Hex.decode("cd".repeat(32))
        val owner = Hex.decode("ef".repeat(32))
        val relay = "ws://127.0.0.1:3334"
        val oldAppId = Crypto.sha256("$relay\u0000pub.ditto.app".toByteArray(Charsets.UTF_8))
        val oldProofId = Crypto.sha256(
            "$relay\u0000${"cd".repeat(32)}\u0000${"ef".repeat(32)}".toByteArray(Charsets.UTF_8),
        )
        jdbcStore(path).use { store ->
            store.insertFixtureCatalog()
            store.pinManifestPubkeyIfAbsent(1, fixtureManifest)
            store.write { tx ->
                tx.db.prepare(
                    """
                    INSERT INTO apps (
                        id, catalog_id, app_id, certificate_hash, app_event_created_at, pubkey, event_pubkey,
                        name, summary, repository, version, version_code, channel, metadata
                    ) VALUES (?, 1, 'pub.ditto.app', ?, 1, NULL, ?, 'Ditto', '', NULL, '1.0.0', 1, 'main', '{}')
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindBlob(1, oldAppId)
                    statement.bindBlob(2, certificate)
                    statement.bindBlob(3, owner)
                    statement.step()
                }
                tx.db.prepare("INSERT INTO apps_search (id, vector) VALUES (?, ?)").use { statement ->
                    statement.bindBlob(1, oldAppId)
                    statement.bindBlob(2, ByteArray(768))
                    statement.step()
                }
                tx.db.prepare(
                    """
                    INSERT INTO certificate_proofs (
                        id, catalog_id, certificate_hash, pubkey, event_id, created_at, expiry, revoked
                    ) VALUES (?, 1, ?, ?, ?, 1, 2, 0)
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindBlob(1, oldProofId)
                    statement.bindBlob(2, certificate)
                    statement.bindBlob(3, owner)
                    statement.bindBlob(4, ByteArray(32) { 9 })
                    statement.step()
                }
                tx.db.prepare(
                    """
                    INSERT INTO app_variants (id, app_id, channel, variant, certificate_hash, version, version_code)
                    VALUES (?, ?, 'beta', '', ?, '1.0.0', 1)
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindBlob(1, ByteArray(32) { 4 })
                    statement.bindBlob(2, oldAppId)
                    statement.bindBlob(3, certificate)
                    statement.step()
                }
            }
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = 2") }
        }
        jdbcStore(path).use { store ->
            val appId = blob(store, "SELECT id FROM apps WHERE app_id = 'pub.ditto.app'")
            assertArrayEquals(ListingRows.appRowId(manifest, "pub.ditto.app"), appId)
            assertArrayEquals(appId, blob(store, "SELECT id FROM apps_search"))
            assertArrayEquals(appId, blob(store, "SELECT app_id FROM app_variants"))
            assertArrayEquals(
                ListingRows.proofRowId(manifest, certificate, owner),
                blob(store, "SELECT id FROM certificate_proofs"),
            )
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
    ): Event = Event.sign(
        secret,
        createdAt,
        Kinds.Asset,
        listOf(
            listOf("i", appId),
            listOf("x", hash),
            listOf("version", version),
            listOf("m", "application/vnd.android.package-archive"),
            listOf("f", "android-arm64-v8a"),
            listOf("version_code", versionCode.toString()),
            listOf("apk_certificate_hash", "cd".repeat(32)),
            listOf("url", "https://example.invalid/$appId.apk"),
            listOf("filename", "$appId.apk"),
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
        val file = File("src/test/resources/golden", name)
        check(file.isFile) { "missing golden $name" }
        return file.readBytes()
    }

    companion object {
        private val localRelay = "ws://127.0.0.1:3334".normalizeRelayUrl()
        private val fixtureManifest = "ab".repeat(32)
    }
}
