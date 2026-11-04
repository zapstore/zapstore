package dev.zapstore.iolite

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchMatchesTest {
    private fun stack(name: String, description: String = "", identifier: String = name) =
        StackRecord("aa".repeat(32), identifier, "bb".repeat(32), name, description, emptyList(), 1)

    @Test
    fun stacksMatchWholeWordsAndRankTheNameFirst() {
        val stacks = listOf(
            stack("Maps", "A privacy-friendly set"),
            stack("Tools for privacy"),
            stack("Privacy Tools"),
            stack("Privacy"),
            stack("Best for privacy"),
            stack("Privacyware"),
        )
        fun names(query: String) = searchStacks(stacks, query, 10).map(StackRecord::name)
        assertEquals(listOf("Privacy", "Privacy Tools", "Tools for privacy", "Best for privacy", "Maps"), names("privacy"))
        // The words may come in any order; equal ranks keep the order given.
        assertEquals(listOf("Tools for privacy", "Privacy Tools"), names("tools privacy"))
        assertEquals(listOf("Maps"), names("friendly"))
        assertEquals(emptyList<String>(), names("priv"))
        assertEquals(emptyList<String>(), names("p"))
        assertEquals(listOf("Privacy"), searchStacks(stacks, "privacy", 1).map(StackRecord::name))
    }

    @Test
    fun developerRankPrefersTheName() {
        assertEquals(0, developerRank(listOf("Alice"), "npub1xyz", "alice"))
        assertEquals(1, developerRank(listOf("Alice Smith"), "npub1xyz", "ali"))
        assertEquals(2, developerRank(listOf("Smith, Alice"), "npub1xyz", "ali"))
        assertEquals(3, developerRank(listOf("Malice"), "npub1xyz", "alic"))
        assertEquals(4, developerRank(listOf("Bob"), "npub1alicekey", "npub1ali"))
        assertEquals(-1, developerRank(listOf("Bob"), "npub1alicekey", "ali"))
        assertEquals(0, developerRank(listOf("Bob", "Alice"), "npub1xyz", "alice"))
    }

    @Test
    fun developersAreFoundByProfileNameOrNpub() = runBlocking {
        val dir = File("build/tmp/iolite-developers-${System.nanoTime()}").apply { mkdirs() }
        val store = jdbcStore(File(dir, "iolite.db").absolutePath)
        store.insertFixtureCatalog()
        val device = DeviceProfile(listOf("arm64-v8a"), 34)
        val signers = listOf(1, 2, 3).map { LocalSigner(ByteArray(32).also { key -> key[31] = it.toByte() }) }
        val (ada, bob, anon) = signers
        val profiles = listOf(
            ada.sign(1, Kinds.Profile, emptyList(), """{"name":"Ada Lovelace"}"""),
            bob.sign(1, Kinds.Profile, emptyList(), """{"name":"bob","display_name":"Adaptive Robert Bob"}"""),
        )
        store.ingest(profiles, IngestContext(device, ada))
        store.write { tx ->
            signers.forEachIndexed { index, signer ->
                tx.db.prepare(
                    """
                    INSERT INTO apps (
                        id, catalog_id, app_id, certificate_hash, app_event_created_at, pubkey, event_pubkey,
                        name, summary, repository, version, version_code, channel, metadata
                    ) VALUES (?, 1, ?, ?, ?, ?, ?, ?, '', NULL, '1.0', 1, 'main', '{}')
                    """.trimIndent(),
                ).use { statement ->
                    val key = Hex.decode(signer.publicKey)
                    statement.bindBlob(1, ByteArray(32) { index.toByte() })
                    statement.bindText(2, "app.$index")
                    statement.bindBlob(3, ByteArray(32) { 2 })
                    statement.bindLong(4, index + 1L)
                    statement.bindBlob(5, key)
                    statement.bindBlob(6, key)
                    statement.bindText(7, "App $index")
                    statement.step()
                }
            }
        }
        // The name or the display name, best match first.
        assertEquals(listOf(ada.publicKey, bob.publicKey), store.developers("ada", 10))
        assertEquals(listOf(bob.publicKey), store.developers("bob", 10))
        assertEquals(listOf(ada.publicKey), store.developers("ada", 1))
        assertEquals(emptyList<String>(), store.developers("a", 10))
        assertEquals(emptyList<String>(), store.developers("zed", 10))
        // A developer with no stored profile is found by npub, not by a short fragment of it.
        assertEquals(listOf(anon.publicKey), store.developers(anon.publicKey.toNpub(), 10))
        assertEquals(emptyList<String>(), store.developers("npub1", 10))
        store.close()
    }
}
