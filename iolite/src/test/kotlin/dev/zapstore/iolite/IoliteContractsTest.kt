package dev.zapstore.iolite

import androidx.sqlite.execSQL
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IoliteContractsTest {
    @Test
    fun catalogRelayListDoesNotPinFromTheNetwork() = runBlocking {
        val signer = LocalSigner(SECRET)
        val extra = "wss://relay.example.com"
        val privateRelay = "wss://private.example"
        val http = HttpTransport { _, _ -> error("catalog relay list must not fetch NIP-11") }
        withIolite(http = http, signer = signer) { iolite ->
            val content = signer.encryptToSelf(JSONArray().put(privateRelay).toString())
            val event = signer.sign(
                1_700_000_100,
                Kinds.CatalogRelayList,
                listOf(listOf("r", "wss://relay.zapstore.dev"), listOf("r", extra)),
                content,
            )
            iolite.ingest(listOf(event))
            val selected = iolite.catalogs()
            assertEquals(
                listOf("wss://relay.zapstore.dev", extra, privateRelay),
                selected.map { it.relayUrl.url },
            )
            assertEquals(listOf(false, false, true), selected.map { it.isPrivate })
            assertTrue(selected.all { it.manifestPubkey == null })
            delay(80)
            assertTrue(iolite.allCatalogs().all { it.manifestPubkey == null })
        }
    }

    @Test
    fun rehydrateCatalogPreferencesAndStack() = runBlocking {
        val signer = LocalSigner(SECRET)
        withIolite(signer = signer) { iolite ->
            val privateRelay = "wss://private.example"
            val list = signer.sign(
                1_700_000_100,
                Kinds.CatalogRelayList,
                listOf(listOf("r", "wss://relay.zapstore.dev")),
                signer.encryptToSelf(JSONArray().put(privateRelay).toString()),
            )
            iolite.ingest(listOf(list))
            iolite.ingest(
                listOf(signer.sign(
                    1_700_000_101,
                    Kinds.Preferences,
                    listOf(listOf("d", "ui"), listOf("client", "zapstore")),
                    """{"theme":"dark"}""",
                )),
            )
            iolite.ingest(
                listOf(signer.sign(
                    1_700_000_102,
                    Kinds.AppStack,
                    listOf(
                        listOf("d", "privacy"),
                        listOf("name", "Privacy"),
                        listOf("a", "32267:${signer.publicKey}:com.example.app"),
                        listOf("client", "zapstore"),
                    ),
                    signer.encryptToSelf(JSONArray().put("32267:${signer.publicKey}:com.hidden").toString()),
                )),
            )

            val restoredList = iolite.rehydrateCatalogRelayList()
            assertTrue(restoredList.id != list.id)
            assertEquals(listOf("wss://relay.zapstore.dev"), restoredList.tagValues("r"))
            assertEquals(
                listOf(privateRelay),
                JSONArray(signer.decryptFromSelf(restoredList.content)).let { array ->
                    List(array.length()) { array.getString(it) }
                },
            )

            val restoredPrefs = iolite.rehydratePreferences("ui", signer)
            assertEquals("ui", restoredPrefs.dTag())
            assertEquals("""{"theme":"dark"}""", restoredPrefs.content)
            assertEquals("zapstore", restoredPrefs.tagValue("client"))

            val restoredStack = iolite.rehydrateStack("privacy", signer)
            assertEquals("privacy", restoredStack.dTag())
            assertEquals("Privacy", restoredStack.tagValue("name"))
            assertEquals("android-arm64-v8a", restoredStack.tagValue("f"))
            assertEquals("zapstore", restoredStack.tagValue("client"))
            assertEquals(
                listOf("32267:${signer.publicKey}:com.hidden"),
                JSONArray(signer.decryptFromSelf(restoredStack.content)).let { array ->
                    List(array.length()) { array.getString(it) }
                },
            )
        }
    }

    @Test
    fun catalogManifestsAreNotStoredAsPreferences() = runBlocking {
        val signer = LocalSigner(SECRET)
        withIolite(signer = signer) { iolite ->
            val manifest = signer.sign(
                1_700_000_100,
                Kinds.Preferences,
                listOf(listOf("d", "catalog"), listOf("from", "0"), listOf("to", "1"), listOf("v", "1")),
                "{}",
            )
            assertEquals(0, iolite.ingest(listOf(manifest)))
            assertTrue(runCatching { iolite.rehydratePreferences("catalog", signer) }.isFailure)
        }
    }

    @Test
    fun pruneRemovesOldZaps() = runBlocking {
        val signer = LocalSigner(SECRET)
        withIolite(
            signer = signer,
            config = IoliteConfig(
                expirationSweepInterval = 20.milliseconds,
                pruneRules = mapOf(Kinds.Zap to 1.seconds),
            ),
            nowMillis = { 10_000L },
        ) { iolite ->
            iolite.ingest(
                listOf(signer.sign(
                    1,
                    Kinds.Zap,
                    listOf(listOf("i", "com.example.app"), listOf("amount", "1000000")),
                    "",
                )),
            )
            val newZap = signer.sign(
                10,
                Kinds.Zap,
                listOf(listOf("i", "com.example.app"), listOf("amount", "2000000")),
                "",
            )
            iolite.ingest(listOf(newZap))
            delay(80)
            val zaps = iolite.query(Query.zaps(AppCoordinate(signer.publicKey, "com.example.app"))).first { true }
            assertEquals(QueryPhase.LocalOnly, zaps.phase)
            assertEquals(listOf(newZap.id), zaps.items.map { it.eventId })
            assertEquals(2_000L, zaps.items.single().amountSats)
        }
    }

    @Test
    fun commentsAreIndexedFromAddressTag() = runBlocking {
        val signer = LocalSigner(SECRET)
        withIolite(signer = signer) { iolite ->
            val coordinate = AppCoordinate(signer.publicKey, "com.example.app")
            val event = signer.sign(
                1_700_000_000,
                Kinds.Comment,
                listOf(listOf("A", coordinate.toString())),
                "great app",
            )
            iolite.ingest(listOf(event))
            val comments = iolite.query(Query.comments(coordinate)).first { it.items.isNotEmpty() }
            assertEquals(listOf(event.id), comments.items.map { it.eventId })
            assertEquals(listOf("great app"), comments.items.map { it.content })
            assertEquals(listOf("com.example.app"), comments.items.map { it.appId })
        }
    }

    @Test
    fun commentsIndexParentEventTag() = runBlocking {
        val signer = LocalSigner(SECRET)
        withIolite(signer = signer) { iolite ->
            val coordinate = AppCoordinate(signer.publicKey, "com.example.app")
            val root = signer.sign(
                1_700_000_000,
                Kinds.Comment,
                listOf(listOf("A", coordinate.toString())),
                "root",
            )
            val reply = signer.sign(
                1_700_000_100,
                Kinds.Comment,
                listOf(listOf("A", coordinate.toString()), listOf("e", root.id)),
                "reply",
            )
            iolite.ingest(listOf(root, reply))
            val comments = iolite.query(Query.comments(coordinate)).first { it.items.size == 2 }
            assertEquals(root.id, comments.items.single { it.eventId == reply.id }.parentEventId)
            assertNull(comments.items.single { it.eventId == root.id }.parentEventId)
        }
    }

    @Test
    fun proofExpiryClearsAppPubkey() {
        val dir = File("build/tmp/iolite-c1-${System.nanoTime()}").apply { mkdirs() }
        val store = IoliteStore(File(dir, "iolite.db").absolutePath)
        store.insertFixtureCatalog()
        val appId = ByteArray(32) { 1 }
        val cert = ByteArray(32) { 2 }
        val pubkey = Hex.decode(LocalSigner(SECRET).publicKey)
        store.write { tx ->
            tx.db.prepare(
                """
                INSERT INTO apps (
                    id, catalog_id, app_id, certificate_hash, app_event_created_at, pubkey, event_pubkey,
                    name, summary, repository, version, version_code, channel, metadata
                ) VALUES (?, 1, 'com.example.app', ?, 1, ?, ?, 'Example', '', NULL, '1.0', 1, 'main', '{}')
                """.trimIndent(),
            ).use { statement ->
                statement.bindBlob(1, appId)
                statement.bindBlob(2, cert)
                statement.bindBlob(3, pubkey)
                statement.bindBlob(4, pubkey)
                statement.step()
            }
            tx.db.prepare(
                """
                INSERT INTO certificate_proofs (
                    id, catalog_id, certificate_hash, pubkey, event_id, created_at, expiry, revoked, delegations
                ) VALUES (?, 1, ?, ?, ?, 50, 150, 0, '[]')
                """.trimIndent(),
            ).use { statement ->
                statement.bindBlob(1, ByteArray(32) { 3 })
                statement.bindBlob(2, cert)
                statement.bindBlob(3, pubkey)
                statement.bindBlob(4, ByteArray(32) { 4 })
                statement.step()
            }
        }
        store.recomputeProofs(now = 100L)
        assertEquals(Hex.encode(pubkey), store.app("com.example.app")!!.proofPubkey)
        assertTrue(store.app("com.example.app")!!.isVerified)
        store.recomputeProofs(now = 200L)
        assertNull(store.app("com.example.app")!!.proofPubkey)
        assertEquals(150L, store.nextProofExpiry(100L))
        store.close()
    }

    @Test
    fun appSearchMatchesNameAndAppIdPrefix() {
        val dir = File("build/tmp/iolite-search-${System.nanoTime()}").apply { mkdirs() }
        val store = IoliteStore(File(dir, "iolite.db").absolutePath)
        store.insertFixtureCatalog()
        insertApp(store, 1, "com.example.app", "Zapstore", embedding = null)
        assertEquals(listOf("com.example.app"), store.apps(AppFilter(search = "zap")).map { it.appId })
        assertEquals(emptyList<AppRecord>(), store.apps(AppFilter(search = "example")))
        assertEquals(listOf("com.example.app"), store.apps(AppFilter(search = "com.example")).map { it.appId })
        assertEquals(listOf("com.example.app"), store.apps(AppFilter(search = "COM.EXAMPLE")).map { it.appId })
        store.close()
    }

    @Test
    fun appSearchRanksEmbeddings() {
        val dir = File("build/tmp/iolite-search-vec-${System.nanoTime()}").apply { mkdirs() }
        val store = IoliteStore(File(dir, "iolite.db").absolutePath)
        store.insertFixtureCatalog()
        val close = ByteArray(EMBEDDING_DIMS) { 20 }
        val far = ByteArray(EMBEDDING_DIMS) { 1 }
        val opposite = ByteArray(EMBEDDING_DIMS) { -20 }
        insertApp(store, 1, "app.map", "Map", opposite)
        insertApp(store, 2, "app.maple", "Maple", embedding = null)
        insertApp(store, 3, "app.atlas", "Atlas", close)
        insertApp(store, 4, "app.noise", "Noise", far)
        val vector = ByteArray(EMBEDDING_DIMS) { 20 }
        assertEquals(
            listOf("app.map", "app.atlas", "app.maple"),
            store.apps(AppFilter(search = "map", queryVector = vector)).map { it.appId },
        )
        assertEquals(listOf("app.atlas"), store.apps(AppFilter(search = "nope", queryVector = vector)).map { it.appId })
        assertEquals(listOf("app.map", "app.maple"), store.apps(AppFilter(search = "map")).map { it.appId })
        assertEquals(listOf("app.map"), store.apps(AppFilter(search = "map", queryVector = vector, limit = 1)).map { it.appId })
        store.close()
    }

    @Test
    fun appLimitReturnsNewestRows() {
        val dir = File("build/tmp/iolite-limit-${System.nanoTime()}").apply { mkdirs() }
        val store = IoliteStore(File(dir, "iolite.db").absolutePath)
        store.insertFixtureCatalog()
        val pubkey = Hex.decode(LocalSigner(SECRET).publicKey)
        store.write { tx ->
            repeat(25) { index ->
                tx.db.prepare(
                    """
                    INSERT INTO apps (
                        id, catalog_id, app_id, certificate_hash, app_event_created_at, pubkey, event_pubkey,
                        name, summary, repository, version, version_code, channel, metadata
                    ) VALUES (?, 1, ?, ?, ?, ?, ?, ?, '', NULL, '1.0', 1, 'main', '{}')
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindBlob(1, ByteArray(32) { index.toByte() })
                    statement.bindText(2, "app.$index")
                    statement.bindBlob(3, ByteArray(32) { 2 })
                    statement.bindLong(4, index + 1L)
                    statement.bindBlob(5, pubkey)
                    statement.bindBlob(6, pubkey)
                    statement.bindText(7, "App $index")
                    statement.step()
                }
            }
        }
        val page = store.apps(AppFilter(limit = 20))
        assertEquals(20, page.size)
        assertEquals((24 downTo 5).map { "app.$it" }, page.map { it.appId })
        store.close()
    }

    @Test
    fun wipeAndCloseTruncateWal() {
        val dir = File("build/tmp/iolite-wal-${System.nanoTime()}").apply { mkdirs() }
        val db = File(dir, "iolite.db")
        val wal = File("${db.path}-wal")
        val store = IoliteStore(db.absolutePath)
        store.insertFixtureCatalog()
        val pubkey = Hex.decode(LocalSigner(SECRET).publicKey)
        store.write { tx ->
            repeat(40) { index ->
                tx.db.prepare(
                    """
                    INSERT INTO apps (
                        id, catalog_id, app_id, certificate_hash, app_event_created_at, pubkey, event_pubkey,
                        name, summary, repository, version, version_code, channel, metadata
                    ) VALUES (?, 1, ?, ?, ?, ?, ?, ?, '', NULL, '1.0', 1, 'main', '{}')
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindBlob(1, ByteArray(32) { index.toByte() })
                    statement.bindText(2, "app.$index")
                    statement.bindBlob(3, ByteArray(32) { 2 })
                    statement.bindLong(4, index + 1L)
                    statement.bindBlob(5, pubkey)
                    statement.bindBlob(6, pubkey)
                    statement.bindText(7, "App $index")
                    statement.step()
                }
            }
        }
        store.wipe()
        assertTrue(!wal.exists() || wal.length() == 0L)
        store.write { tx ->
            tx.db.execSQL("UPDATE catalogs SET epoch = 1")
            tx.touch(Table.Catalogs)
        }
        store.close()
        assertTrue(!wal.exists() || wal.length() == 0L)
    }

    @Test
    fun ingestBatchCommitsTogether() {
        val dir = File("build/tmp/iolite-batch-${System.nanoTime()}").apply { mkdirs() }
        val store = IoliteStore(File(dir, "iolite.db").absolutePath)
        val signer = LocalSigner(SECRET)
        val device = DeviceProfile(listOf("arm64-v8a"), 34)
        val first = runBlocking { signer.sign(1, Kinds.Profile, emptyList(), """{"name":"Ada"}""") }
        val second = runBlocking { signer.sign(2, Kinds.Profile, emptyList(), """{"name":"Bob"}""") }
        assertEquals(2, store.ingest(listOf(first, second), IngestContext(device, signer)))
        val profile = store.profiles(listOf(signer.publicKey)).getValue(signer.publicKey)
        assertEquals("Bob", profile.name)
        assertEquals(second.id, profile.eventId)
        store.close()
    }

    private suspend fun withIolite(
        signer: LocalSigner,
        http: HttpTransport? = null,
        config: IoliteConfig = IoliteConfig(eoseGrace = 5.milliseconds, oneShotTimeout = 2.seconds),
        nowMillis: () -> Long = { System.currentTimeMillis() },
        block: suspend (Iolite) -> Unit,
    ) {
        val dir = File("build/tmp/iolite-contracts-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            config = config,
            http = http,
            signer = signer,
            nowMillis = nowMillis,
        )
        try {
            block(iolite)
        } finally {
            iolite.close()
            scope.coroutineContext.job.cancel()
        }
    }

    private fun insertApp(store: IoliteStore, index: Int, appId: String, name: String, embedding: ByteArray?) {
        val pubkey = Hex.decode(LocalSigner(SECRET).publicKey)
        val id = ByteArray(32) { index.toByte() }
        store.write { tx ->
            tx.db.prepare(
                """
                INSERT INTO apps (
                    id, catalog_id, app_id, certificate_hash, app_event_created_at, pubkey, event_pubkey,
                    name, summary, repository, version, version_code, channel, metadata
                ) VALUES (?, 1, ?, ?, ?, ?, ?, ?, 'example summary', NULL, '1.0', 1, 'main', '{}')
                """.trimIndent(),
            ).use { statement ->
                statement.bindBlob(1, id)
                statement.bindText(2, appId)
                statement.bindBlob(3, ByteArray(32) { 2 })
                statement.bindLong(4, index.toLong())
                statement.bindBlob(5, pubkey)
                statement.bindBlob(6, pubkey)
                statement.bindText(7, name)
                statement.step()
            }
            if (embedding != null) {
                tx.db.prepare("INSERT INTO apps_search (id, embedding) VALUES (?, ?)").use { statement ->
                    statement.bindBlob(1, id)
                    statement.bindBlob(2, embedding)
                    statement.step()
                }
            }
        }
    }

    companion object {
        private val SECRET = Hex.decode("0000000000000000000000000000000000000000000000000000000000000001")
    }
}

fun IoliteStore.insertFixtureCatalog() {
    write { tx ->
        tx.db.prepare(
            """
            INSERT INTO catalogs (id, relay_url, position, is_private, epoch, endpoints)
            VALUES (1, 'ws://127.0.0.1:3334', 0, 0, 0, '["ws://127.0.0.1:3334"]')
            """.trimIndent(),
        ).use { it.step() }
    }
}
