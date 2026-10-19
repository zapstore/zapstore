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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NwcTest {
    @Test
    fun parsesAConnectionString() {
        val uri = "nostr+walletconnect://${WALLET_PUB}?relay=wss%3A%2F%2Frelay.wallet.test%2F&secret=$CLIENT&lud16=ada%40wallet.test"
        val connection = NwcConnection.parse(uri)!!
        assertEquals(WALLET_PUB, connection.walletPubkey)
        assertEquals(setOf("wss://relay.wallet.test".normalizeRelayUrl()), connection.relays)
        assertEquals(CLIENT, connection.secret)
        assertEquals("ada@wallet.test", connection.lud16)
    }

    @Test
    fun rejectsStringsThatAreNotConnections() {
        assertNull(NwcConnection.parse("nsec1abc"))
        assertNull(NwcConnection.parse("nostr+walletconnect://$WALLET_PUB?secret=$CLIENT"))
        assertNull(NwcConnection.parse("nostr+walletconnect://$WALLET_PUB?relay=wss://r.test"))
        assertNull(NwcConnection.parse("nostr+walletconnect://abc?relay=wss://r.test&secret=$CLIENT"))
    }

    @Test
    fun nip04MatchesNak() {
        val a = Hex.decode("974daf40683f692a8f328c03f6c1bc1927a0b6eb4e328c097cbb7262194caaba")
        val b = Hex.decode("9a16dd6097f673708b50bcc73af983f751d8829e07c0bf6dc101f599379edcf3")
        val payload = "Y5y9F23KmeS6ZuvXC0hRLFdY9wAzD/pDMosy25tepk4=?iv=k2S2Me8UanEre03HkiZZ4A=="
        assertEquals("""{"method":"pay_invoice"}""", Nip04.decrypt(payload, b, Crypto.xOnlyPubkey(a)))
        val back = Nip04.encrypt("hello", b, Crypto.xOnlyPubkey(a))
        assertEquals("hello", Nip04.decrypt(back, a, Crypto.xOnlyPubkey(b)))
    }

    @Test
    fun paysThroughANip04Wallet() = runBlocking {
        val wallet = WalletRelay(nip44 = false) { JSONObject().put("result_type", "pay_invoice").put("result", JSONObject().put("preimage", "ff")) }
        withIolite(wallet) { iolite ->
            val result = iolite.walletRequest(wallet.connection, "pay_invoice", JSONObject().put("invoice", "lnbc1"))
            assertEquals("ff", result.getString("preimage"))
            assertEquals("pay_invoice", wallet.methods.single())
            assertTrue(wallet.requests.single().tags.none { it.firstOrNull() == "encryption" })
        }
    }

    @Test
    fun usesNip44WhenTheWalletOffersIt() = runBlocking {
        val wallet = WalletRelay(nip44 = true) { JSONObject().put("result", JSONObject().put("preimage", "aa")) }
        withIolite(wallet) { iolite ->
            val result = iolite.walletRequest(wallet.connection, "pay_invoice", JSONObject().put("invoice", "lnbc1"))
            assertEquals("aa", result.getString("preimage"))
            assertTrue(listOf("encryption", "nip44_v2") in wallet.requests.single().tags)
        }
    }

    @Test
    fun walletErrorsCarryTheirCode() = runBlocking {
        val wallet = WalletRelay(nip44 = false) {
            JSONObject().put("error", JSONObject().put("code", "INSUFFICIENT_BALANCE").put("message", "not enough sats"))
        }
        withIolite(wallet) { iolite ->
            try {
                iolite.walletRequest(wallet.connection, "pay_invoice", JSONObject().put("invoice", "lnbc1"))
                fail("expected a wallet error")
            } catch (error: NwcException) {
                assertEquals("INSUFFICIENT_BALANCE", error.code)
                assertEquals("not enough sats", error.message)
            }
        }
    }

    /** One relay that is also the wallet: it answers REQs, serves the info event, and replies to 23194. */
    private class WalletRelay(private val nip44: Boolean, private val reply: (JSONObject) -> JSONObject) {
        val url = "ws://wallet.test"
        val connection = NwcConnection(WALLET_PUB, setOf(url.normalizeRelayUrl()), CLIENT, null)
        val requests = mutableListOf<Event>()
        val methods = mutableListOf<String>()
        private val walletSecret = Hex.decode(WALLET)
        private val subscriptions = mutableMapOf<String, JSONArray>()

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
                    subscriptions[id] = filter.optJSONArray("#e") ?: JSONArray()
                    if (nip44 && filter.getJSONArray("kinds").getInt(0) == Kinds.WalletInfo) {
                        val info = Event.sign(walletSecret, 1, Kinds.WalletInfo, listOf(listOf("encryption", "nip44_v2 nip04")), "pay_invoice")
                        listener.onMessage(JSONArray().put("EVENT").put(id).put(info.toJsonObject()).toString())
                    }
                    listener.onMessage(JSONArray().put("EOSE").put(id).toString())
                }
                "EVENT" -> {
                    val event = Event.parse(message.getJSONObject(1))
                    listener.onMessage(JSONArray().put("OK").put(event.id).put(true).put("").toString())
                    if (event.kind != Kinds.WalletRequest) return
                    requests += event
                    val client = Hex.decode(event.pubkey)
                    val key = Nip44.conversationKey(walletSecret, client)
                    val plain = if (nip44) Nip44.decrypt(event.content, key) else Nip04.decrypt(event.content, walletSecret, client)
                    methods += JSONObject(plain).getString("method")
                    val body = reply(JSONObject(plain)).toString()
                    val content = if (nip44) Nip44.encrypt(body, key) else Nip04.encrypt(body, walletSecret, client)
                    val response = Event.sign(walletSecret, 2, Kinds.WalletResponse, listOf(listOf("e", event.id), listOf("p", event.pubkey)), content)
                    subscriptions.filterValues { it.length() > 0 && it.getString(0) == event.id }.keys.forEach { id ->
                        listener.onMessage(JSONArray().put("EVENT").put(id).put(response.toJsonObject()).toString())
                    }
                }
                "CLOSE" -> subscriptions.remove(message.getString(1))
            }
        }
    }

    private suspend fun withIolite(wallet: WalletRelay, block: suspend (Iolite) -> Unit) {
        val dir = File("build/tmp/iolite-nwc-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob())
        val iolite = Iolite.createForTesting(
            databasePath = File(dir, "iolite.db").absolutePath,
            parentScope = scope,
            config = IoliteConfig(eoseGrace = 5.milliseconds, oneShotTimeout = 2.seconds, walletTimeout = 3.seconds),
            webSocket = wallet.factory(),
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
        private const val WALLET = "9a16dd6097f673708b50bcc73af983f751d8829e07c0bf6dc101f599379edcf3"
        private const val WALLET_PUB = "a3c76afced607fce8de10181e702656c96420f9e612816fee166ab3c5bcd62cd"
        private const val CLIENT = "974daf40683f692a8f328c03f6c1bc1927a0b6eb4e328c097cbb7262194caaba"
    }
}
