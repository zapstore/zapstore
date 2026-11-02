package dev.zapstore.iolite

import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReputationTest {
    @Test
    fun asksVertexAndDropsTheTargetFromTheAnswer() = runBlocking {
        val vertex = VertexRelay(Kinds.ReputationResponse) { JSONArray().put(row(TARGET)).put(row(A)).put(row(B)).toString() }
        withIolite(vertex) { iolite ->
            val ranked = iolite.reputation(setOf("wss://vertex.test".normalizeRelayUrl()), SOURCE, TARGET, LocalSigner(Hex.decode(CLIENT)))
            assertEquals(listOf(A, B), ranked)
            val request = vertex.requests.single()
            assertEquals(Kinds.VerifyReputation, request.kind)
            assertTrue(listOf("param", "source", SOURCE) in request.tags)
            assertTrue(listOf("param", "target", TARGET) in request.tags)
        }
    }

    @Test
    fun vertexErrorsSurfaceTheirMessage() = runBlocking {
        val vertex = VertexRelay(Kinds.DvmError) { "" }
        withIolite(vertex) { iolite ->
            try {
                iolite.reputation(setOf("wss://vertex.test".normalizeRelayUrl()), SOURCE, TARGET, LocalSigner(Hex.decode(CLIENT)))
                fail("expected an error")
            } catch (error: IllegalStateException) {
                assertEquals("not enough credits", error.message)
            }
        }
    }

    @Test
    fun readsFollowsFromTheNewestContactList() = runBlocking {
        val vertex = VertexRelay(Kinds.ReputationResponse) { "[]" }
        vertex.contacts = Event.sign(Hex.decode(CLIENT), 5, Kinds.ContactList, listOf(listOf("p", A), listOf("p", "bad"), listOf("p", B)), "")
        withIolite(vertex) { iolite ->
            val follows = iolite.follows(Crypto.xOnlyPubkey(Hex.decode(CLIENT)).let(Hex::encode), setOf("wss://vertex.test".normalizeRelayUrl()))
            assertEquals(setOf(A, B), follows)
        }
    }

    private fun row(pubkey: String) = JSONObject().put("pubkey", pubkey).put("rank", 0.1).put("follows", 1).put("followers", 2)

    private class VertexRelay(private val replyKind: Int, private val body: () -> String) {
        val requests = mutableListOf<Event>()
        var contacts: Event? = null
        private val vertexSecret = Hex.decode(VERTEX)
        private val subscriptions = mutableMapOf<String, JSONObject>()

        fun factory() = WebSocketFactory { _, listener ->
            listener.onOpen()
            object : WebSocketSession {
                override fun send(text: String) = handle(JSONArray(text), listener)
                override fun close() = listener.onClosing(1000, "")
            }
        }

        private fun handle(message: JSONArray, listener: WebSocketListener) {
            when (message.getString(0)) {
                "REQ" -> {
                    val id = message.getString(1)
                    val filter = message.getJSONObject(2)
                    subscriptions[id] = filter
                    if (filter.getJSONArray("kinds").getInt(0) == Kinds.ContactList) {
                        contacts?.let { listener.onMessage(JSONArray().put("EVENT").put(id).put(it.toJsonObject()).toString()) }
                    }
                    listener.onMessage(JSONArray().put("EOSE").put(id).toString())
                }
                "EVENT" -> {
                    val event = Event.parse(message.getJSONObject(1))
                    listener.onMessage(JSONArray().put("OK").put(event.id).put(true).put("").toString())
                    if (event.kind != Kinds.VerifyReputation) return
                    requests += event
                    val tags = listOf(listOf("e", event.id)) +
                        if (replyKind == Kinds.DvmError) listOf(listOf("status", "error", "not enough credits")) else emptyList()
                    val response = Event.sign(vertexSecret, 2, replyKind, tags, body())
                    subscriptions.filterValues { it.optJSONArray("#e")?.optString(0) == event.id }.keys.forEach { id ->
                        listener.onMessage(JSONArray().put("EVENT").put(id).put(response.toJsonObject()).toString())
                    }
                }
                "CLOSE" -> subscriptions.remove(message.getString(1))
            }
        }
    }

    private suspend fun withIolite(vertex: VertexRelay, block: suspend (Iolite) -> Unit) {
        val dir = File("build/tmp/iolite-vertex-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            config = IoliteConfig(eoseGrace = 5.milliseconds, oneShotTimeout = 2.seconds),
            webSocket = vertex.factory(),
            signer = LocalSigner(Hex.decode(CLIENT)),
            writeRelays = emptySet(),
            openConnection = ::JdbcSqliteConnection,
        )
        try {
            block(iolite)
        } finally {
            iolite.close()
            scope.coroutineContext.job.cancel()
        }
    }

    companion object {
        private const val VERTEX = "9a16dd6097f673708b50bcc73af983f751d8829e07c0bf6dc101f599379edcf3"
        private const val CLIENT = "974daf40683f692a8f328c03f6c1bc1927a0b6eb4e328c097cbb7262194caaba"
        private const val SOURCE = "78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d"
        private const val TARGET = "a3c76afced607fce8de10181e702656c96420f9e612816fee166ab3c5bcd62cd"
        private val A = "a".repeat(64)
        private val B = "b".repeat(64)
    }
}
