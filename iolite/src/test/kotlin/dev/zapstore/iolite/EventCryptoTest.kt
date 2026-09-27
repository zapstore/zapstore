package dev.zapstore.iolite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCryptoTest {
    @Test
    fun signAndVerifyRoundTrip() {
        val secret = Hex.decode("0000000000000000000000000000000000000000000000000000000000000001")
        val event = Event.sign(
            secretKey = secret,
            createdAt = 1_700_000_000,
            kind = 1,
            tags = listOf(listOf("t", "zapstore")),
            content = "hello",
        )
        assertEquals("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", event.pubkey)
        assertTrue(event.verify())
        assertEquals(event.id, Event.computeId(event.pubkey, event.createdAt, event.kind, event.tags, event.content))
    }

    @Test
    fun rejectsTamperedContent() {
        val secret = Hex.decode("0000000000000000000000000000000000000000000000000000000000000001")
        val event = Event.sign(secret, 1, 1, emptyList(), "hello")
        val tampered = event.copy(content = "bye")
        assertTrue(!tampered.verify())
    }

    @Test
    fun replacementOrderingPrefersNewerThenLowestId() {
        val older = Event("b".repeat(64), "a".repeat(64), 10, 1, emptyList(), "", "c".repeat(128))
        val newer = older.copy(createdAt = 11, id = "c".repeat(64))
        val sameTimeLowerId = older.copy(id = "a".repeat(64))
        assertTrue(supersedes(newer.createdAt, newer.id, older.createdAt, older.id))
        assertTrue(supersedes(sameTimeLowerId.createdAt, sameTimeLowerId.id, older.createdAt, older.id))
        assertTrue(!supersedes(older.createdAt, older.id, newer.createdAt, newer.id))
    }

    @Test
    fun filterSerializesTagKeysWithHash() {
        val json = Filter(kinds = listOf(Kinds.App), tags = mapOf("d" to listOf("com.example.app"))).toJsonObject()
        assertEquals("com.example.app", json.getJSONArray("#d").getString(0))
        assertEquals(Kinds.App, json.getJSONArray("kinds").getInt(0))
    }

    @Test
    fun verifiesRelayAssetWhoseUrlContainsSlash() {
        val event = Event.parse(
            """{"id":"fb5b64912c37e33f0da48745032f0c7be9e571bfb82da10a09b31c085fccff85","pubkey":"c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5","created_at":1789657776,"kind":3063,"tags":[["i","com.example.app"],["x","a000000000000000000000000000000000000000000000000000000000000000"],["version","1.0.0"],["m","application/vnd.android.package-archive"],["version_code","100"],["apk_certificate_hash","b000000000000000000000000000000000000000000000000000000000000000"],["url","https://cdn.example.com/com.example.app.apk"]],"content":"","sig":"de1a0c2353acc9ceb8fc9122ff14d205a62b8bef0f93742b4286e988e0a3de6612d285424ae8bec5b56ca7b1b6f26164346685703930d8bcec34c2cadd37956d"}""",
        )
        assertTrue(event.verify())
        val canonical = String(Event.canonicalBytes(event.pubkey, event.createdAt, event.kind, event.tags, event.content), Charsets.UTF_8)
        assertTrue(canonical.contains("https://cdn.example.com/com.example.app.apk"))
        assertTrue(!canonical.contains("\\/"))
    }
}
