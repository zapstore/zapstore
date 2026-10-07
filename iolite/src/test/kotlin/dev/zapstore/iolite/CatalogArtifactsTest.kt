package dev.zapstore.iolite

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogArtifactsTest {
    @Test
    fun unknownOpenSourceIsNotARow() {
        val rows = parseFactRows(
            "\"open_source\",\"unknown\",\"\"\n" +
                "\"offline_capable\",\"no\",\"\"\n",
        )
        assertEquals(1, rows.size)
        assertEquals("offline_capable", rows[0].key)
        assertFalse(rows[0].yes)
    }

    @Test
    fun parsesFactCsvAndKeepsReason() {
        val rows = parseFactRows(
            "\"google_services\",\"no\",\"Play services\"\n" +
                "\"google_services\",\"yes\",\"Firebase Cloud Messaging\"\n" +
                "\"location\",\"yes\",\"ACCESS_FINE_LOCATION. The map screen shows nearby stores.\"\n",
        )
        assertEquals(2, rows.size)
        assertEquals("google_services", rows[0].key)
        assertTrue(rows[0].yes)
        assertEquals("Firebase Cloud Messaging", rows[0].reason)
        assertEquals("location", rows[1].key)
        assertEquals("ACCESS_FINE_LOCATION. The map screen shows nearby stores.", rows[1].reason)
    }

    @Test
    fun parsesAboutSecurityFactsWarningsVectorIconAndHexAvatar() {
        val pubkey = "ab".repeat(32)
        val parsed = CatalogArtifacts.fromMembers(
            mapOf(
                "com.example.app/about" to TarMember("com.example.app/about", "Maps.".toByteArray()),
                "com.example.app/security" to TarMember("com.example.app/security", "It asks for location.\n⚠️ It runs a shell.".toByteArray()),
                "com.example.app/facts" to TarMember("com.example.app/facts", "gms: no".toByteArray()),
                "com.example.app/vector" to TarMember("com.example.app/vector", ByteArray(VECTOR_DIMS) { 7 }),
                "com.example.app/icon.webp" to TarMember("com.example.app/icon.webp", "icon".toByteArray()),
                "$pubkey.webp" to TarMember("$pubkey.webp", "avatar".toByteArray()),
            ),
            maxIconBytes = 256L * 1024,
        )
        val app = parsed.apps.single()
        assertEquals("com.example.app", app.appId)
        assertEquals("Maps.", app.about)
        assertEquals("It asks for location.\n⚠️ It runs a shell.", app.security)
        assertEquals("gms: no", app.facts)
        assertEquals(VECTOR_DIMS, app.vector!!.size)
        assertEquals(pubkey, parsed.avatars.single().pubkey)
        assertArrayEquals("avatar".toByteArray(), parsed.avatars.single().webp)
    }

    @Test
    fun rejectsPrefixedPathsAndNpubAvatars() {
        assertFalse(CatalogArtifacts.allowedMember("artifacts/com.example.app/about"))
        assertFalse(CatalogArtifacts.allowedMember("com.example.app/analysis"))
        assertFalse(CatalogArtifacts.allowedMember("com.example.app/warnings"))
        assertFalse(CatalogArtifacts.allowedMember("artifacts.bin"))
        assertFalse(CatalogArtifacts.allowedMember("npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqkxqqu.webp"))
        assertTrue(CatalogArtifacts.allowedMember("diff.jsonl"))
        assertTrue(CatalogArtifacts.allowedMember("index"))
        assertTrue(CatalogArtifacts.allowedMember("${"cd".repeat(32)}.webp"))
        val failure = runCatching {
            CatalogArtifacts.fromMembers(
                mapOf("com.example.app/vector" to TarMember("com.example.app/vector", ByteArray(32 + VECTOR_DIMS))),
                maxIconBytes = 256L * 1024,
            )
        }.exceptionOrNull()
        assertTrue(failure is CatalogImportException)
        assertEquals("vector com.example.app", failure!!.message)
    }

    @Test
    fun keepsVectorAndAboutWhenALaterRecordOmitsThem() {
        val dir = File("build/tmp/catalog-artifacts-${System.nanoTime()}").apply { mkdirs() }
        val store = jdbcStore(File(dir, "iolite.db").absolutePath)
        store.insertFixtureCatalog()
        val catalog = store.catalog(1)!!
        val device = DeviceProfile(abis = listOf("arm64-v8a"), sdk = 34)
        val pubkey = "cd".repeat(32)
        try {
            store.write { tx ->
                tx.db.prepare(
                    """
                    INSERT INTO apps (
                        id, catalog_id, app_id, certificate_hash, app_event_created_at, pubkey, event_pubkey,
                        name, summary, repository, version, version_code, channel, metadata
                    ) VALUES (?, 1, 'com.example.app', ?, 1, NULL, ?, 'Example', '', NULL, '1.0.0', 1, 'main', '{}')
                    """.trimIndent(),
                ).use { statement ->
                    statement.bindBlob(1, ByteArray(32) { 1 })
                    statement.bindBlob(2, ByteArray(32) { 2 })
                    statement.bindBlob(3, ByteArray(32) { 3 })
                    statement.step()
                }
            }
            store.importCatalog(
                catalog = catalog,
                events = emptyList(),
                deletes = emptyList(),
                replaceAll = false,
                toEpoch = 1,
                device = device,
                now = 0,
                artifacts = CatalogArtifact(
                    apps = listOf(
                        AppArtifact(
                            appId = "com.example.app",
                            webp = "one".toByteArray(),
                            vector = ByteArray(VECTOR_DIMS) { 7 },
                            facts = "maps",
                            about = "first note",
                            security = "Contacts leave the phone.\n---\nAsks for location.",
                        ),
                    ),
                    avatars = listOf(AvatarArtifact(pubkey, "face".toByteArray())),
                ),
            )
            val imported = store.apps().single()
            assertEquals("first note", imported.about)
            assertEquals("Contacts leave the phone.\n---\nAsks for location.", imported.security)
            assertEquals("Contacts leave the phone.", imported.securityWarnings)
            assertEquals("Asks for location.", imported.securityBody)
            assertEquals("one", imported.iconFile!!.readText())
            assertEquals("face", store.avatarFile(pubkey).readText())
            val vector = searchVector(store)

            store.importCatalog(
                catalog = store.catalog(1)!!,
                events = emptyList(),
                deletes = emptyList(),
                replaceAll = false,
                toEpoch = 2,
                device = device,
                now = 0,
                artifacts = CatalogArtifact(
                    apps = listOf(
                        AppArtifact(
                            appId = "com.example.app",
                            webp = "two".toByteArray(),
                            vector = null,
                            facts = null,
                            about = null,
                            security = null,
                        ),
                    ),
                ),
            )
            val kept = store.apps().single()
            assertEquals("first note", kept.about)
            assertEquals("Contacts leave the phone.\n---\nAsks for location.", kept.security)
            assertEquals("two", kept.iconFile!!.readText())
            assertArrayEquals(vector, searchVector(store))
            assertEquals("maps", searchFeatures(store))
        } finally {
            store.close()
        }
    }

    @Test
    fun npubRoundTrip() {
        val pubkey = "cd".repeat(32)
        assertEquals(pubkey, pubkey.toNpub().decodeNpub())
    }

    private fun searchVector(store: IoliteStore): ByteArray = store.read { db ->
        db.prepare(
            "SELECT vector FROM apps_search WHERE id = (SELECT id FROM apps WHERE app_id = 'com.example.app')",
        ).use { statement ->
            assertTrue(statement.step())
            statement.getBlob(0)
        }
    }

    private fun searchFeatures(store: IoliteStore): String = store.read { db ->
        db.prepare(
            "SELECT facts FROM apps WHERE app_id = 'com.example.app'",
        ).use { statement ->
            assertTrue(statement.step())
            statement.getText(0)
        }
    }
}
