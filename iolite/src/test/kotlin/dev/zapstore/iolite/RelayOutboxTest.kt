package dev.zapstore.iolite

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayOutboxTest {
    @Test
    fun localSignerRoundTripAndNip42Auth() = runBlocking {
        val signer = LocalSigner(SECRET)
        val event = signer.sign(1_700_000_000, 1, emptyList(), "hello")
        assertTrue(event.verify())
        assertEquals(signer.publicKey, event.pubkey)

        val auth = Nip42.auth(signer, "wss://relay.example".normalizeRelayUrl(), "challenge-1", 1_700_000_001)
        assertEquals(Kinds.Auth, auth.kind)
        assertEquals("challenge-1", auth.tagValue("challenge"))
        assertEquals("wss://relay.example", auth.tagValue("relay"))
        assertTrue(auth.verify())
        assertTrue(Nip42.wire(auth).startsWith("""["AUTH","""))
    }

    @Test
    fun nip55SignerAcceptsVerifiedCallback() = runBlocking {
        val local = LocalSigner(SECRET)
        val nip55 = Nip55Signer(local.publicKey) { unsigned ->
            val json = org.json.JSONObject(unsigned)
            local.sign(
                json.getLong("created_at"),
                json.getInt("kind"),
                json.getJSONArray("tags").toStringLists(),
                json.getString("content"),
            ).toJson()
        }
        val signed = nip55.sign(10, 1, listOf(listOf("t", "zap")), "hi")
        assertTrue(signed.verify())
        assertEquals(local.publicKey, signed.pubkey)
    }

    @Test
    fun queryLocalAndRemoteIngestsProfileAfterEoseGrace() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val profile = signer.sign(1_700_000_000, Kinds.Profile, emptyList(), """{"name":"Ada"}""")
        val relay = "ws://relay.test".normalizeRelayUrl()
        withIolite(webSocket = network.factory(), writeRelays = setOf(relay), signer = signer) { iolite ->
            val states = async {
                withTimeout(5_000) {
                    iolite.query(
                        Query.profile(signer.publicKey),
                        QueryOptions.localAndRemote(setOf(relay), RemoteMode.OneShot(2.seconds)),
                    ).first { state -> state.phase == QueryPhase.Complete && state.items?.name == "Ada" }
                }
            }
            network.awaitOpen(relay.url)
            val req = network.relays.getValue(relay.url).sent.first { it.startsWith("""["REQ"""") }
            val subId = JSONArray(req).getString(1)
            network.push(relay.url, JSONArray().put("EVENT").put(subId).put(profile.toJsonObject()).toString())
            network.push(relay.url, JSONArray().put("EOSE").put(subId).toString())
            val complete = states.await()
            assertEquals(QueryPhase.Complete, complete.phase)
            assertEquals("Ada", complete.items!!.name)
        }
    }

    @Test
    fun durableOutboxDeletesAfterOkThreshold() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val one = "ws://one.test".normalizeRelayUrl()
        val two = "ws://two.test".normalizeRelayUrl()
        val three = "ws://three.test".normalizeRelayUrl()
        withIolite(webSocket = network.factory(), writeRelays = setOf(one, two, three), signer = signer) { iolite ->
            val event = iolite.publish(1, emptyList(), "note", signer, relays = setOf(one, two, three))
            assertEquals(1, iolite.pendingOutbox().size)
            network.awaitOpen(one.url)
            network.awaitOpen(two.url)
            fun ok(url: RelayUrl) {
                network.push(url.url, JSONArray().put("OK").put(event.id).put(true).put("").toString())
            }
            ok(one)
            assertEquals(1, iolite.pendingOutbox().size)
            ok(two)
            assertTrue(iolite.pendingOutbox().isEmpty())
        }
    }

    @Test
    fun oneOrTwoRelaysNeedSingleOk() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val relay = "ws://one.test".normalizeRelayUrl()
        withIolite(webSocket = network.factory(), writeRelays = setOf(relay), signer = signer) { iolite ->
            val event = iolite.publish(1, emptyList(), "note", signer)
            network.awaitOpen(relay.url)
            network.push(relay.url, JSONArray().put("OK").put(event.id).put(true).put("").toString())
            assertTrue(iolite.pendingOutbox().isEmpty())
        }
    }

    @Test
    fun authChallengeIsNotOutboxed() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val relay = "ws://auth.test".normalizeRelayUrl()
        withIolite(webSocket = network.factory(), writeRelays = setOf(relay), signer = signer) { iolite ->
            val collected = async {
                iolite.query(
                    Query.profile(signer.publicKey),
                    QueryOptions.localAndRemote(setOf(relay), RemoteMode.OneShot(2.seconds)),
                ).take(3).toList()
            }
            network.awaitOpen(relay.url)
            network.push(relay.url, JSONArray().put("AUTH").put("abc").toString())
            val auth = withTimeout(2_000) {
                while (true) {
                    val found = network.relays.getValue(relay.url).sent.firstOrNull { it.startsWith("""["AUTH"""") }
                    if (found != null) return@withTimeout found
                    kotlinx.coroutines.delay(5)
                }
                error("unreachable")
            }
            val event = Event.parse(JSONArray(auth).getJSONObject(1))
            assertEquals(Kinds.Auth, event.kind)
            assertTrue(iolite.pendingOutbox().none { it.kind == Kinds.Auth })
            collected.cancel()
        }
    }

    @Test
    fun cachedQuerySkipsRelay() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val profile = signer.sign(1_700_000_000, Kinds.Profile, emptyList(), """{"name":"Ada"}""")
        val relay = "ws://cache.test".normalizeRelayUrl()
        var now = 1_000L
        withIolite(
            webSocket = network.factory(),
            writeRelays = setOf(relay),
            signer = signer,
            nowMillis = { now },
            config = IoliteConfig(eoseGrace = 5.milliseconds, oneShotTimeout = 2.seconds),
        ) { iolite ->
            val first = async {
                iolite.query(
                    Query.profile(signer.publicKey),
                    QueryOptions.localAndRemote(setOf(relay), RemoteMode.OneShot(2.seconds), cachedFor = 30.seconds),
                ).first { it.phase == QueryPhase.Complete }
            }
            network.awaitOpen(relay.url)
            val req = network.relays.getValue(relay.url).sent.first { it.startsWith("""["REQ"""") }
            val subId = JSONArray(req).getString(1)
            network.push(relay.url, JSONArray().put("EVENT").put(subId).put(profile.toJsonObject()).toString())
            network.push(relay.url, JSONArray().put("EOSE").put(subId).toString())
            first.await()
            val sentAfter = network.relays.getValue(relay.url).sent.size
            now = 2_000L
            val cached = iolite.query(
                Query.profile(signer.publicKey),
                QueryOptions.localAndRemote(setOf(relay), RemoteMode.OneShot(2.seconds), cachedFor = 30.seconds),
            ).first { it.phase == QueryPhase.Cached }
            assertEquals("Ada", cached.items!!.name)
            assertEquals(sentAfter, network.relays.getValue(relay.url).sent.size)
        }
    }

    private suspend fun withIolite(
        webSocket: WebSocketFactory,
        writeRelays: Set<RelayUrl>,
        signer: Signer,
        nowMillis: () -> Long = { System.currentTimeMillis() },
        config: IoliteConfig = IoliteConfig(eoseGrace = 5.milliseconds, oneShotTimeout = 2.seconds),
        block: suspend (Iolite) -> Unit,
    ) {
        val dir = File("build/tmp/iolite-relay-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            config = config,
            webSocket = webSocket,
            signer = signer,
            writeRelays = writeRelays,
            nowMillis = nowMillis,
        )
        try {
            block(iolite)
        } finally {
            iolite.close()
            scope.coroutineContext.job.cancel()
        }
    }

    companion object {
        private val SECRET = Hex.decode("0000000000000000000000000000000000000000000000000000000000000001")
    }
}

internal class MemoryNetwork {
    val relays = mutableMapOf<String, MemoryRelay>()

    fun factory(): WebSocketFactory = WebSocketFactory { url, listener ->
        relays.getOrPut(url) { MemoryRelay(url) }.connect(listener)
    }

    fun push(url: String, text: String) {
        relays.getValue(url).listener?.onMessage(text)
    }

    suspend fun awaitOpen(url: String) {
        withTimeout(2_000) {
            while (relays[url]?.listener == null) {
                kotlinx.coroutines.delay(5)
            }
        }
    }
}

internal class MemoryRelay(val url: String) {
    val sent = CopyOnWriteArrayList<String>()
    @Volatile var listener: WebSocketListener? = null

    fun connect(listener: WebSocketListener): WebSocketSession {
        this.listener = listener
        listener.onOpen()
        return object : WebSocketSession {
            override fun send(text: String) {
                sent += text
            }

            override fun close() {
                listener.onClosing(1000, "")
            }
        }
    }
}
