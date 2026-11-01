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

class RelayPublishTest {
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
                    ).first { state -> state.settled && state.items?.name == "Ada" }
                }
            }
            network.awaitOpen(relay.url)
            val req = network.relays.getValue(relay.url).sent.first { it.startsWith("""["REQ"""") }
            val subId = JSONArray(req).getString(1)
            network.push(relay.url, JSONArray().put("EVENT").put(subId).put(profile.toJsonObject()).toString())
            network.push(relay.url, JSONArray().put("EOSE").put(subId).toString())
            val complete = states.await()
            assertEquals(RelayStatus.Answered, complete.relays[relay])
            assertEquals("Ada", complete.items!!.name)
        }
    }

    @Test
    fun publishQueueDeletesAfterOkThreshold() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val one = "ws://one.test".normalizeRelayUrl()
        val two = "ws://two.test".normalizeRelayUrl()
        val three = "ws://three.test".normalizeRelayUrl()
        withIolite(webSocket = network.factory(), writeRelays = setOf(one, two, three), signer = signer) { iolite ->
            val event = iolite.publish(1, emptyList(), "note", signer, relays = setOf(one, two, three))
            assertEquals(1, iolite.pendingPublishes().size)
            network.awaitOpen(one.url)
            network.awaitOpen(two.url)
            fun ok(url: RelayUrl) {
                network.push(url.url, JSONArray().put("OK").put(event.id).put(true).put("").toString())
            }
            ok(one)
            assertEquals(1, iolite.pendingPublishes().size)
            ok(two)
            assertTrue(iolite.pendingPublishes().isEmpty())
        }
    }

    @Test
    fun oneFailedRelayIsNotUnreachable() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val down = "ws://down.test".normalizeRelayUrl()
        val up = "ws://up.test".normalizeRelayUrl()
        withIolite(webSocket = network.factory(), writeRelays = setOf(up), signer = signer) { iolite ->
            val live = async {
                withTimeout(5_000) {
                    iolite.query(Query.profile(signer.publicKey), QueryOptions.localAndRemote(setOf(down, up), RemoteMode.Stream))
                        .first { it.settled }
                }
            }
            network.awaitOpen(down.url)
            network.awaitOpen(up.url)
            network.relays.getValue(down.url).listener!!.onFailure(IllegalStateException("refused"))
            val req = withTimeout(2_000) {
                while (network.relays.getValue(up.url).sent.none { it.startsWith("[\"REQ\"") }) kotlinx.coroutines.delay(5)
                JSONArray(network.relays.getValue(up.url).sent.first { it.startsWith("[\"REQ\"") })
            }
            network.push(up.url, JSONArray().put("EOSE").put(req.getString(1)).toString())
            val settled = live.await()
            assertTrue(settled.relays[down] is RelayStatus.Failed)
            assertEquals(RelayStatus.Answered, settled.relays[up])
            assertTrue(!settled.unreachable)
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
            assertTrue(iolite.pendingPublishes().isEmpty())
        }
    }

    @Test
    fun devicePrivateEventsGoOnlyToTheCatalogRelay() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val general = "ws://general.test".normalizeRelayUrl()
        val catalog = "ws://catalog.test".normalizeRelayUrl()
        withIolite(webSocket = network.factory(), writeRelays = setOf(general), signer = signer) { iolite ->
            iolite.store.upsertCatalogRelays(1, listOf(catalog), replaceIdentity = true)
            iolite.setDeviceList(DeviceEvents.BOOKMARKS, listOf("dev.zap"))
            val note = iolite.publish(1, emptyList(), "note", signer)
            network.awaitOpen(catalog.url)
            network.awaitOpen(general.url)
            withTimeout(5_000) {
                while (network.relays.getValue(catalog.url).sent.none { it.startsWith("""["EVENT"""") }) kotlinx.coroutines.delay(5)
                while (network.relays.getValue(general.url).sent.none { note.id in it }) kotlinx.coroutines.delay(5)
            }
            val toCatalog = network.relays.getValue(catalog.url).sent.filter { it.startsWith("""["EVENT"""") }
            val toGeneral = network.relays.getValue(general.url).sent.filter { it.startsWith("""["EVENT"""") }
            assertTrue(toCatalog.all { Event.parse(JSONArray(it).getJSONObject(1)).kind == Kinds.AppStack })
            assertTrue(toGeneral.none { Event.parse(JSONArray(it).getJSONObject(1)).kind == Kinds.AppStack })
        }
    }

    @Test
    fun authChallengeIsNotQueued() = runBlocking {
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
            assertTrue(iolite.pendingPublishes().none { it.kind == Kinds.Auth })
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
                ).first { it.settled && it.items != null }
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
            ).first()
            assertTrue(cached.settled)
            assertEquals("Ada", cached.items!!.name)
            assertEquals(sentAfter, network.relays.getValue(relay.url).sent.size)
        }
    }

    @Test
    fun oneShotReadsTheAuthorsOutboxOnceTheirRelayListLands() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val base = "ws://base.test".normalizeRelayUrl()
        val outbox = "ws://outbox.test".normalizeRelayUrl()
        val relayList = signer.sign(1_700_000_000, Kinds.RelayList, listOf(listOf("r", outbox.url, "write")), "")
        val profile = signer.sign(1_700_000_000, Kinds.Profile, emptyList(), """{"name":"Ada"}""")
        withIolite(webSocket = network.factory(), writeRelays = setOf(base), signer = signer) { iolite ->
            val settled = async {
                withTimeout(5_000) {
                    iolite.query(
                        Query.profile(signer.publicKey),
                        QueryOptions.localAndRemote(setOf(base), RemoteMode.OneShot(3.seconds), useAuthorRelays = true),
                    ).first { it.settled }
                }
            }
            val profileReq = network.awaitReq(base.url, Kinds.Profile)
            network.push(base.url, eose(profileReq))
            val listReq = network.awaitReq(base.url, Kinds.RelayList)
            network.push(base.url, event(listReq, relayList))
            network.push(base.url, eose(listReq))
            val outboxReq = network.awaitReq(outbox.url, Kinds.Profile)
            network.push(outbox.url, event(outboxReq, profile))
            network.push(outbox.url, eose(outboxReq))
            val state = settled.await()
            assertEquals("Ada", state.items!!.name)
            assertEquals(RelayStatus.Answered, state.relays[outbox])
        }
    }

    @Test
    fun publishReachesTaggedUsersInboxes() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val other = LocalSigner(Hex.decode("0000000000000000000000000000000000000000000000000000000000000003"))
        val write = "ws://write.test".normalizeRelayUrl()
        val inbox = "ws://inbox.test".normalizeRelayUrl()
        withIolite(webSocket = network.factory(), writeRelays = setOf(write), signer = signer) { iolite ->
            iolite.ingest(listOf(other.sign(1, Kinds.RelayList, listOf(listOf("r", inbox.url, "read")), "")))
            val reply = iolite.publish(1111, listOf(listOf("p", other.publicKey)), "hi", signer)
            network.awaitSent(inbox.url) { reply.id in it && it.startsWith("""["EVENT"""") }
            network.awaitSent(write.url) { reply.id in it && it.startsWith("""["EVENT"""") }
        }
    }

    @Test
    fun silentRelayFailsAfterFirstResponseTimeout() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val silent = "ws://silent.test".normalizeRelayUrl()
        val config = IoliteConfig(eoseGrace = 5.milliseconds, oneShotTimeout = 5.seconds, firstResponseTimeout = 100.milliseconds)
        withIolite(webSocket = network.factory(), writeRelays = setOf(silent), signer = signer, config = config) { iolite ->
            val state = withTimeout(3_000) {
                iolite.query(Query.profile(signer.publicKey), QueryOptions.localAndRemote(setOf(silent), RemoteMode.OneShot(5.seconds)))
                    .first { it.settled }
            }
            assertEquals(RelayStatus.Failed("no response"), state.relays[silent])
            assertTrue(state.unreachable)
        }
    }

    @Test
    fun closedIsAFailureNotAnAnswer() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val relay = "ws://closed.test".normalizeRelayUrl()
        withIolite(webSocket = network.factory(), writeRelays = setOf(relay), signer = signer) { iolite ->
            val settled = async {
                withTimeout(3_000) {
                    iolite.query(Query.profile(signer.publicKey), QueryOptions.localAndRemote(setOf(relay), RemoteMode.OneShot(2.seconds)))
                        .first { it.settled }
                }
            }
            val req = network.awaitReq(relay.url, Kinds.Profile)
            network.push(relay.url, JSONArray().put("CLOSED").put(req).put("blocked: no").toString())
            val state = settled.await()
            assertEquals(RelayStatus.Failed("blocked: no"), state.relays[relay])
            assertTrue(state.unreachable)
        }
    }

    @Test
    fun windowClosesEveryRelayAfterTheFirstEosePlusGrace() = runBlocking {
        val network = MemoryNetwork()
        val signer = LocalSigner(SECRET)
        val fast = "ws://fast.test".normalizeRelayUrl()
        val slow = "ws://slow.test".normalizeRelayUrl()
        val config = IoliteConfig(eoseGrace = 50.milliseconds, oneShotTimeout = 5.seconds)
        withIolite(webSocket = network.factory(), writeRelays = setOf(fast), signer = signer, config = config) { iolite ->
            val settled = async {
                withTimeout(3_000) {
                    iolite.query(Query.profile(signer.publicKey), QueryOptions.localAndRemote(setOf(fast, slow), RemoteMode.OneShot(5.seconds)))
                        .first { it.settled }
                }
            }
            val fastReq = network.awaitReq(fast.url, Kinds.Profile)
            val slowReq = network.awaitReq(slow.url, Kinds.Profile)
            network.push(fast.url, eose(fastReq))
            val state = settled.await()
            assertEquals(RelayStatus.Answered, state.relays[fast])
            assertEquals(RelayStatus.Pending, state.relays[slow])
            network.awaitSent(slow.url) { it == JSONArray().put("CLOSE").put(slowReq).toString() }
            network.awaitSent(fast.url) { it == JSONArray().put("CLOSE").put(fastReq).toString() }
        }
    }

    private fun eose(subId: String) = JSONArray().put("EOSE").put(subId).toString()

    private fun event(subId: String, event: Event) = JSONArray().put("EVENT").put(subId).put(event.toJsonObject()).toString()

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
        private val SECRET = Hex.decode("0000000000000000000000000000000000000000000000000000000000000001")
    }
}

internal class MemoryNetwork {
    val relays = java.util.concurrent.ConcurrentHashMap<String, MemoryRelay>()

    fun factory(): WebSocketFactory = WebSocketFactory { url, listener ->
        relays.getOrPut(url) { MemoryRelay(url) }.connect(listener)
    }

    fun push(url: String, text: String) {
        relays.getValue(url).listener?.onMessage(text)
    }

    /** The id of the first REQ to [url] asking for [kind]. */
    suspend fun awaitReq(url: String, kind: Int): String =
        JSONArray(awaitSent(url) { text ->
            if (!text.startsWith("""["REQ"""")) return@awaitSent false
            val req = JSONArray(text)
            (2 until req.length()).any { index ->
                val kinds = req.getJSONObject(index).optJSONArray("kinds") ?: return@any false
                (0 until kinds.length()).any { kinds.getInt(it) == kind }
            }
        }).getString(1)

    suspend fun awaitSent(url: String, predicate: (String) -> Boolean): String = withTimeout(2_000) {
        while (true) {
            relays[url]?.sent?.firstOrNull(predicate)?.let { return@withTimeout it }
            kotlinx.coroutines.delay(5)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
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
