package dev.zapstore.iolite

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The user's ordered catalogs: adding one by key, order and removal, and the kind 10067 that carries them. */
class CatalogListTest {
    private val hint = "wss://hint.test".normalizeRelayUrl()
    private val indexer = "wss://indexer.test".normalizeRelayUrl()
    private val outbox = "wss://outbox.test".normalizeRelayUrl()

    @Test
    fun addCatalogStoresItOnlyAfterItsBundleIsPulledAndPublishesTheList() = runBlocking {
        val bundle = CatalogImporterTest.goldenBundle("bundle-0-1.tar.zst")
        val key = withIolite(FakeRelays()) { it.bundleSigner(bundle)!! }
        val asked = mutableListOf<String>()
        val http = HttpTransport { url, _ ->
            asked += url
            if (url.startsWith("https://one.example/")) HttpResponse(200, emptyMap(), bundle) else HttpResponse(404, emptyMap(), null)
        }
        withIolite(FakeRelays(), http) { iolite ->
            val catalog = iolite.addCatalogAt(key, listOf("wss://two.example".normalizeRelayUrl(), "wss://one.example".normalizeRelayUrl()), setOf(hint), "1.2.3", false)
            // The first address answered 404; the second gave the bundle.
            assertEquals("https://two.example/v1/bundle?from=0&v=1.2.3", asked.first())
            assertTrue(asked.any { it.startsWith("https://one.example/") })
            assertEquals(key, catalog.manifestPubkey)
            assertEquals(0, catalog.position)
            assertTrue(catalog.epoch > 0)
            assertTrue(iolite.apps().isNotEmpty())
            assertEquals(listOf(hint), catalog.hints)
            val list = iolite.pendingPublishes().single { it.kind == Kinds.CatalogRelayList }
            assertEquals(listOf(listOf("p", key, hint.url)), list.tags)
            assertFalse(iolite.needsDefaultCatalog())
        }
    }

    @Test
    fun aCatalogThatGivesNoBundleIsRefusedAndNothingChanges() = runBlocking {
        val relays = FakeRelays()
        relays.put(hint, addresses(SECRET_A, "wss://one.example"))
        val answers = listOf(
            HttpResponse(404, emptyMap(), null),
            HttpResponse(200, emptyMap(), ByteArray(0)),
            HttpResponse(200, emptyMap(), "not a bundle".toByteArray()),
            HttpResponse(304, emptyMap(), null),
        )
        for (answer in answers) {
            withIolite(relays, HttpTransport { _, _ -> answer }) { iolite ->
                try {
                    iolite.addCatalog(PUB_A, setOf(hint))
                    fail("expected the catalog to be refused for $answer")
                } catch (_: CatalogImportException) {
                }
                assertTrue(iolite.allCatalogs().isEmpty())
                assertTrue(iolite.pendingPublishes().isEmpty())
                assertTrue(iolite.needsDefaultCatalog())
            }
        }
        withIolite(relays, HttpTransport { _, _ -> throw java.io.IOException("unreachable") }) { iolite ->
            try {
                iolite.addCatalog(PUB_A, setOf(hint))
                fail("expected the catalog to be refused")
            } catch (failure: CatalogImportException) {
                assertTrue(failure.message!!.contains("unreachable"))
            }
            assertTrue(iolite.allCatalogs().isEmpty())
        }
    }

    @Test
    fun aBundleSignedByAnotherKeyIsRefused() = runBlocking {
        val bundle = CatalogImporterTest.goldenBundle("bundle-0-1.tar.zst")
        withIolite(FakeRelays(), HttpTransport { _, _ -> HttpResponse(200, emptyMap(), bundle) }) { iolite ->
            try {
                iolite.addCatalogAt(PUB_A, listOf("wss://one.example".normalizeRelayUrl()), emptySet(), "", false)
                fail("expected the catalog to be refused")
            } catch (failure: CatalogImportException) {
                assertTrue(failure.message!!.contains("not signed"))
            }
            assertTrue(iolite.allCatalogs().isEmpty())
            assertTrue(iolite.apps().isEmpty())
            assertTrue(iolite.pendingPublishes().isEmpty())
        }
    }

    @Test
    fun addressesAreLookedUpOnTheHintThenTheOutbox() = runBlocking {
        val relays = FakeRelays()
        relays.put(hint, addresses(SECRET_A, "wss://one.example", "wss://two.example"))
        relays.put(indexer, Event.sign(SECRET_B, 5, Kinds.RelayList, listOf(listOf("r", outbox.url, "write"), listOf("r", "wss://read.test", "read")), ""))
        relays.put(outbox, addresses(SECRET_B, "wss://home.example"))
        withIolite(relays) { iolite ->
            assertEquals(listOf("wss://one.example", "wss://two.example"), iolite.resolveCatalog(PUB_A, setOf(hint))!!.map { it.url })
            assertEquals(listOf("wss://home.example"), iolite.resolveCatalog(PUB_B)!!.map { it.url })
        }
    }

    @Test
    fun aKeyNobodyServesIsRefusedAndLeavesTheListAlone() = runBlocking {
        withIolite(FakeRelays()) { iolite ->
            try {
                iolite.addCatalog(PUB_A, setOf(hint))
                fail("expected the catalog to be refused")
            } catch (_: CatalogImportException) {
            }
            assertTrue(iolite.allCatalogs().isEmpty())
            assertTrue(iolite.pendingPublishes().isEmpty())
        }
    }

    @Test
    fun addressesSignedByAnotherKeyAreNotBelieved() = runBlocking {
        val relays = FakeRelays()
        // B signs a list; the lookup was for A.
        relays.put(hint, addresses(SECRET_B, "wss://evil.example"))
        withIolite(relays) { iolite ->
            assertNull(iolite.resolveCatalog(PUB_A, setOf(hint)))
        }
    }

    @Test
    fun theFirstCatalogSuppliesAnAppAndOrderCanChange() = runBlocking {
        withIolite(FakeRelays()) { iolite ->
            val first = iolite.store.addCatalog(PUB_A, listOf("wss://a.example".normalizeRelayUrl()))
            val second = iolite.store.addCatalog(PUB_B, listOf("wss://b.example".normalizeRelayUrl()))
            insertApp(iolite, first.id, 1, "shared", "From A")
            insertApp(iolite, second.id, 2, "shared", "From B")
            insertApp(iolite, second.id, 3, "only.b", "Only B")

            assertEquals(listOf(PUB_A, PUB_B), iolite.catalogs().map { it.manifestPubkey })
            assertEquals("From A", iolite.app("shared")!!.name)
            assertEquals(setOf("shared", "only.b"), iolite.apps().map { it.appId }.toSet())

            assertTrue(iolite.moveCatalog(second.id, -1))
            assertEquals(listOf(PUB_B, PUB_A), iolite.catalogs().map { it.manifestPubkey })
            assertEquals("From B", iolite.app("shared")!!.name)
            assertFalse(iolite.moveCatalog(second.id, -1))

            assertTrue(iolite.removeCatalog(second.id))
            assertEquals("From A", iolite.app("shared")!!.name)
            assertNull(iolite.app("only.b"))
            assertEquals(listOf(0), iolite.catalogs().map { it.position })
        }
    }

    @Test
    fun removingEveryCatalogLeavesAnEmptyAppThatIsNotRefilled() = runBlocking {
        withIolite(FakeRelays()) { iolite ->
            val only = iolite.store.addCatalog(PUB_A, listOf("wss://a.example".normalizeRelayUrl()))
            insertApp(iolite, only.id, 1, "app.one", "One")
            assertTrue(iolite.removeCatalog(only.id))
            assertTrue(iolite.catalogs().isEmpty())
            assertTrue(iolite.apps().isEmpty())
            // The empty list was published, so it is the user's decision and the default stays gone.
            assertFalse(iolite.needsDefaultCatalog())
            val list = iolite.pendingPublishes().single { it.kind == Kinds.CatalogRelayList }
            assertTrue(list.tags.isEmpty())
            iolite.wipeLocalData()
            assertTrue(iolite.catalogs().isEmpty())
            assertFalse(iolite.needsDefaultCatalog())
        }
    }

    @Test
    fun aFreshPhoneNeedsTheDefaultUntilTheListIsDecided() = runBlocking {
        withIolite(FakeRelays()) { iolite ->
            assertTrue(iolite.needsDefaultCatalog())
            iolite.store.addCatalog(PUB_A, emptyList(), listOf(hint))
            assertFalse(iolite.needsDefaultCatalog())
        }
    }

    @Test
    fun theListSurvivesARestoredKeyInOrderWithItsHints() = runBlocking {
        val relays = FakeRelays()
        relays.put(hint, addresses(SECRET_A, "wss://one.example"), addresses(SECRET_B, "wss://two.example"))
        val published = withIolite(relays) { iolite ->
            iolite.store.addCatalog(PUB_B, listOf("wss://two.example".normalizeRelayUrl()), listOf(hint))
            iolite.store.addCatalog(PUB_A, listOf("wss://one.example".normalizeRelayUrl()), listOf(hint))
            iolite.moveCatalog(iolite.catalogs().last().id, -1)
            iolite.pendingPublishes().filter { it.kind == Kinds.CatalogRelayList }.maxByOrNull { it.createdAt }!!
        }
        assertEquals(listOf(PUB_A, PUB_B), published.tagValues("p"))

        withIolite(FakeRelays()) { restored ->
            assertEquals(1, restored.ingest(listOf(published)))
            val catalogs = restored.catalogs()
            assertEquals(listOf(PUB_A, PUB_B), catalogs.map { it.manifestPubkey })
            // Only the key and a hint came over; the addresses are looked up on the next sync.
            assertTrue(catalogs.all { it.relays.isEmpty() })
            assertEquals(listOf(hint), catalogs.first().hints)
            assertFalse(restored.needsDefaultCatalog())
        }
    }

    @Test
    fun anOlderListFromAnotherDeviceDoesNotUndoANewerOne() = runBlocking {
        withIolite(FakeRelays()) { iolite ->
            val signer = LocalSigner(SECRET_DEVICE)
            val older = signer.sign(100, Kinds.CatalogRelayList, listOf(listOf("p", PUB_A)), "")
            val newer = signer.sign(200, Kinds.CatalogRelayList, listOf(listOf("p", PUB_B)), "")
            iolite.ingest(listOf(newer))
            iolite.ingest(listOf(older))
            assertEquals(listOf(PUB_B), iolite.catalogs().map { it.manifestPubkey })
        }
    }

    @Test
    fun aListFromAnotherKeyIsIgnored() = runBlocking {
        withIolite(FakeRelays()) { iolite ->
            val stranger = Event.sign(SECRET_B, 100, Kinds.CatalogRelayList, listOf(listOf("p", PUB_A)), "")
            assertEquals(0, iolite.ingest(listOf(stranger)))
            assertTrue(iolite.allCatalogs().isEmpty())
        }
    }

    @Test
    fun catalogReferencesTakeHexNpubAndNprofile() {
        assertEquals(CatalogReference(PUB_A), CatalogReference.parse(PUB_A))
        assertEquals(CatalogReference(PUB_A), CatalogReference.parse("  ${PUB_A.uppercase()} "))
        assertEquals(CatalogReference(PUB_A), CatalogReference.parse("nostr:" + PUB_A.toNpub()))
        // The NIP-19 nprofile example: two relay hints.
        val parsed = CatalogReference.parse(
            "nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p",
        )!!
        assertEquals("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d", parsed.pubkey)
        assertEquals(listOf("wss://r.x.com", "wss://djbas.sadkb.com"), parsed.hints.map { it.url })
        assertNull(CatalogReference.parse("not a key"))
        assertNull(CatalogReference.parse(""))
        assertNull(CatalogReference.parse("npub1invalid"))
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun addresses(secret: ByteArray, vararg urls: String): Event =
        Event.sign(secret, 10, Kinds.CatalogRelayList, urls.map { listOf("r", it) }, "")

    private fun insertApp(iolite: Iolite, catalogId: Long, index: Int, appId: String, name: String) {
        val publisher = Hex.decode(PUB_A)
        iolite.store.write { tx ->
            tx.db.prepare(
                """
                INSERT INTO apps (
                    id, catalog_id, app_id, certificate_hash, app_event_created_at, pubkey, event_pubkey,
                    name, summary, repository, version, version_code, channel, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', NULL, '1.0', 1, 'main', '{}')
                """.trimIndent(),
            ).use { statement ->
                statement.bindBlob(1, ByteArray(32) { index.toByte() })
                statement.bindLong(2, catalogId)
                statement.bindText(3, appId)
                statement.bindBlob(4, ByteArray(32) { 2 })
                statement.bindLong(5, index.toLong())
                statement.bindBlob(6, publisher)
                statement.bindBlob(7, publisher)
                statement.bindText(8, name)
                statement.step()
            }
            tx.touch(Table.Apps)
        }
    }

    private suspend fun <T> withIolite(relays: FakeRelays, http: HttpTransport? = null, block: suspend (Iolite) -> T): T {
        val dir = File("build/tmp/iolite-catalog-list-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            config = IoliteConfig(
                eoseGrace = 5.milliseconds,
                oneShotTimeout = 2.seconds,
                relayListTimeout = 2.seconds,
                indexerRelays = setOf(indexer),
            ),
            http = http,
            webSocket = relays.factory(),
            signer = LocalSigner(SECRET_DEVICE),
            openConnection = ::JdbcSqliteConnection,
        )
        try {
            return block(iolite)
        } finally {
            iolite.close()
            scope.coroutineContext.job.cancel()
        }
    }

    /** Relays that answer a REQ from the events they hold, by author and kind. */
    private class FakeRelays {
        private val held = ConcurrentHashMap<String, MutableList<Event>>()

        fun put(relay: RelayUrl, vararg events: Event) {
            held.getOrPut(relay.url) { mutableListOf() }.addAll(events)
        }

        fun factory() = WebSocketFactory { url, listener ->
            listener.onOpen()
            object : WebSocketSession {
                override fun send(text: String) {
                    val message = JSONArray(text)
                    when (message.getString(0)) {
                        "REQ" -> {
                            val id = message.getString(1)
                            val filter = message.getJSONObject(2)
                            val authors = filter.optJSONArray("authors").toStringListOrNull()
                            val kinds = filter.optJSONArray("kinds")?.let { array -> List(array.length()) { array.getInt(it) } }
                            held[url].orEmpty()
                                .filter { authors == null || it.pubkey in authors }
                                .filter { kinds == null || it.kind in kinds }
                                .forEach { listener.onMessage(JSONArray().put("EVENT").put(id).put(it.toJsonObject()).toString()) }
                            listener.onMessage(JSONArray().put("EOSE").put(id).toString())
                        }
                        "EVENT" -> {
                            val event = Event.parse(message.getJSONObject(1))
                            listener.onMessage(JSONArray().put("OK").put(event.id).put(true).put("").toString())
                        }
                    }
                }

                override fun close() = listener.onClosing(1000, "")
            }
        }

        private fun JSONArray?.toStringListOrNull(): List<String>? = this?.let { List(length()) { index -> getString(index) } }
    }

    companion object {
        private val SECRET_A = Hex.decode("0a".repeat(32))
        private val SECRET_B = Hex.decode("0b".repeat(32))
        private val SECRET_DEVICE = Hex.decode("0000000000000000000000000000000000000000000000000000000000000001")
        private val PUB_A = Hex.encode(Crypto.xOnlyPubkey(SECRET_A))
        private val PUB_B = Hex.encode(Crypto.xOnlyPubkey(SECRET_B))
    }
}
