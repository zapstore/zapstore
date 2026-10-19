package dev.zapstore.app

import dev.zapstore.iolite.Event
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LnurlTest {
    private lateinit var server: MockWebServer
    private lateinit var lnurl: Lnurl

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url
                val local = server.url(url.encodedPath).newBuilder().encodedQuery(url.encodedQuery).build()
                chain.proceed(chain.request().newBuilder().url(local).header("X-Host", url.host).build())
            }
            .build()
        lnurl = Lnurl(client)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun resolvesALightningAddress() = runBlocking {
        server.enqueue(MockResponse().setBody(payRequest(allowsNostr = true)))
        val pay = lnurl.pay("Ada@Wallet.test")
        val request = server.takeRequest()
        assertEquals("/.well-known/lnurlp/ada", request.path)
        assertEquals("wallet.test", request.getHeader("X-Host"))
        assertEquals("https://wallet.test/callback", pay.callback)
        assertEquals(1_000L, pay.minMsat)
        assertEquals(10_000_000L, pay.maxMsat)
    }

    @Test
    fun refusesAddressesWithoutNostrZaps() = runBlocking {
        server.enqueue(MockResponse().setBody(payRequest(allowsNostr = false)))
        assertFailure(ZapFailure.NoZaps) { lnurl.pay("ada@wallet.test") }
        assertFailure(ZapFailure.NoAddress) { lnurl.pay("not-an-address") }
    }

    @Test
    fun invoiceCarriesAmountAndZapRequest() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"pr":"lnbc210n1test"}"""))
        val zapRequest = zapRequest()
        val pay = LnurlPay("https://wallet.test/callback?k=1", 1_000, 10_000_000, null)
        assertEquals("lnbc210n1test", lnurl.invoice(pay, 21_000, zapRequest))
        val url = server.takeRequest().requestUrl!!
        assertEquals("1", url.queryParameter("k"))
        assertEquals("21000", url.queryParameter("amount"))
        assertEquals(zapRequest.id, JSONObject(url.queryParameter("nostr")!!).getString("id"))
    }

    @Test
    fun serverErrorsStopTheZap() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"status":"ERROR","reason":"no"}"""))
        assertFailure(ZapFailure.Invoice) { lnurl.invoice(LnurlPay("https://wallet.test/cb", 1, 2, null), 1_000, zapRequest()) }
        server.enqueue(MockResponse().setResponseCode(500))
        assertFailure(ZapFailure.Unreachable) { lnurl.pay("ada@wallet.test") }
    }

    private fun zapRequest() = Event("e".repeat(64), "b".repeat(64), 1, 9_734, listOf(listOf("p", "c".repeat(64))), "thanks", "f".repeat(128))

    private suspend fun assertFailure(expected: ZapFailure, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (error: ZapException) {
            assertEquals(expected, error.failure)
        }
    }

    private fun payRequest(allowsNostr: Boolean) = JSONObject()
        .put("tag", "payRequest")
        .put("callback", "https://wallet.test/callback")
        .put("minSendable", 1_000)
        .put("maxSendable", 10_000_000)
        .put("allowsNostr", allowsNostr)
        .put("nostrPubkey", "d".repeat(64))
        .toString()
}
