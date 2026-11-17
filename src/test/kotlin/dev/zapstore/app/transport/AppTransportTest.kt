package dev.zapstore.app.transport

import okhttp3.Call
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit
import dev.zapstore.iolite.HttpResponse
import dev.zapstore.iolite.HttpTransport
import dev.zapstore.iolite.WebSocketFactory
import dev.zapstore.iolite.WebSocketListener
import dev.zapstore.iolite.WebSocketSession

class AppTransportTest {
    @Test
    fun downloadFollowsARedirectButGetDoesNot() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when (request.requestUrl?.encodedPath) {
                    "/v1/bundle" -> okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location", "/cdn/bundle")
                    else -> okhttp3.mockwebserver.MockResponse().setBody("bundle")
                }
            }
            server.start()
            val transport = OkHttpAppTransport()
            val url = server.url("/v1/bundle").toString()
            val file = File.createTempFile("bundle", ".bin")
            try {
                assertEquals(302, transport.get(url, emptyMap()).status)
                assertEquals(200, transport.download(url, emptyMap(), file, 1024).status)
                assertEquals("bundle", file.readText())
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun torModeDoesNotFallBackToDirect() {
        try {
            AppTransports.create(NetworkMode.TorOnly)
            throw AssertionError("expected TorUnavailableException")
        } catch (failure: TorUnavailableException) {
            assertTrue(failure.message!!.contains("refusing Direct fallback"))
        }
    }

    @Test
    fun torModeWithoutStreamsDoesNotFallBackToDirect() {
        try {
            AppTransports.create(NetworkMode.TorOnly, FakeArti(streams = null))
            throw AssertionError("expected TorUnavailableException")
        } catch (failure: TorUnavailableException) {
            assertTrue(failure.message!!.contains("refusing Direct fallback"))
        }
    }

    @Test
    fun directModeUsesOkHttp() {
        val transport = AppTransports.create(NetworkMode.DirectOnly)
        assertTrue(transport.http is OkHttpAppTransport)
        assertTrue(transport.webSocket is OkHttpAppTransport)
        assertEquals(transport.http, transport.webSocket)
    }

    @Test
    fun fakeDnsKeepsTheHostnameAndDoesNotResolve() {
        val addresses = TorFakeDns.lookup("relay.zapstore.dev")
        assertEquals("relay.zapstore.dev", addresses.single().hostName)
        assertTrue(addresses.single().hostAddress!!.startsWith("10."))
        assertEquals("relay.zapstore.dev", TorFakeDns.hostnameFor(addresses.single()))
    }

    @Test
    fun torClientUsesInProcessStreamsAndResolvesDnsInsideArti() {
        val client = torClient(TcpTorStreams(0), TorIsolation.Anonymous)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertTrue(client.socketFactory is TorSocketFactory)
        assertEquals(TorFakeDns, client.dns)
        assertEquals(listOf(okhttp3.Protocol.HTTP_1_1), client.protocols)
        assertTrue(!client.followRedirects)
    }

    @Test
    fun imageTorClientFollowsRedirects() {
        val client = imageTorClient(TcpTorStreams(0))
        assertTrue(client.followRedirects)
        assertTrue(client.followSslRedirects)
        assertEquals(listOf(okhttp3.Protocol.HTTP_1_1), client.protocols)
    }

    @Test
    fun torHttpSendsTheHostnameAndReusesTheStream() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("one"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("two"))
            server.start()
            val streams = TcpTorStreams(server.port)
            val transport = AppTransports.torTransport(streams)
            val url = "http://relay.zapstore.dev:${server.port}/v1/bundle"

            assertEquals("one", String(transport.http.get(url, emptyMap()).body!!))
            assertEquals("two", String(transport.http.get(url, emptyMap()).body!!))

            assertEquals(listOf(Triple("relay.zapstore.dev", server.port, TorIsolation.Anonymous)), streams.connects)
            assertEquals("relay.zapstore.dev:${server.port}", server.takeRequest().getHeader("Host"))
        }
    }

    @Test
    fun torReadTimesOutWithoutHanging() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            server.start()
            val client = torClient(TcpTorStreams(server.port), TorIsolation.Anonymous).newBuilder()
                .readTimeout(200, TimeUnit.MILLISECONDS)
                .build()
            val call = client.newCall(Request.Builder().url("http://relay.zapstore.dev:${server.port}/").build())
            try {
                call.execute()
                throw AssertionError("expected a timeout")
            } catch (expected: java.net.SocketTimeoutException) {
            }
        }
    }

    @Test
    fun torWebSocketUsesTheIdentityCircuit() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                        webSocket.send("echo:$text")
                    }
                }),
            )
            server.start()
            val streams = TcpTorStreams(server.port)
            val received = java.util.concurrent.CompletableFuture<String>()
            val session = AppTransports.torTransport(streams).webSocket.open(
                "ws://relay.zapstore.dev:${server.port}/",
                object : WebSocketListener {
                    override fun onOpen() = Unit
                    override fun onMessage(text: String) {
                        received.complete(text)
                    }
                    override fun onClosing(code: Int, reason: String) = Unit
                    override fun onFailure(error: Throwable) {
                        received.completeExceptionally(error)
                    }
                },
            )
            session.send("hi")
            assertEquals("echo:hi", received.get(5, TimeUnit.SECONDS))
            session.close()
            assertEquals(TorIsolation.Identity, streams.connects.single().third)
        }
    }

    @Test
    fun torTargetHostKeepsLiteralAddressesWithoutResolving() {
        val literal = java.net.InetSocketAddress(java.net.InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)), 443)
        assertEquals("93.184.216.34", torTargetHost(literal))
        val fake = TorFakeDns.lookup("relay.zapstore.dev").single()
        assertEquals("relay.zapstore.dev", torTargetHost(java.net.InetSocketAddress(fake, 443)))
    }

    @Test
    fun torTargetHostSendsTheAddressHostnameWhenFakeAddressesCollide() {
        val taken = TorFakeDns.lookup("first.example").single()
        val sameBytes = java.net.InetAddress.getByAddress("second.example", taken.address)
        assertEquals("second.example", torTargetHost(java.net.InetSocketAddress(sameBytes, 443)))
        val bare = java.net.InetAddress.getByAddress(taken.address)
        assertEquals("first.example", torTargetHost(java.net.InetSocketAddress(bare, 443)))
    }

    @Test
    fun closedTorSocketRejectsReads() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            val socket = TorSocketFactory(TcpTorStreams(server.port), TorIsolation.Anonymous).createSocket()
            socket.connect(java.net.InetSocketAddress(TorFakeDns.lookup("relay.zapstore.dev").single(), server.port))
            val input = socket.getInputStream()
            socket.close()
            assertTrue(socket.isClosed)
            try {
                input.read(ByteArray(1))
                throw AssertionError("expected SocketException")
            } catch (expected: java.net.SocketException) {
            }
        }
    }

    @Test
    fun remoteImagesReadyOnlyWhenDirectOrArtiIsUp() {
        assertTrue(remoteImagesReady(NetworkMode.DirectOnly, TorRuntimeState.Stopped))
        assertTrue(remoteImagesReady(NetworkMode.TorOnly, TorRuntimeState.Ready))
        assertTrue(remoteImagesReady(NetworkMode.TorOnly, TorRuntimeState.Dormant))
        assertTrue(!remoteImagesReady(NetworkMode.TorOnly, TorRuntimeState.Bootstrapping))
        assertTrue(!remoteImagesReady(NetworkMode.TorOnly, TorRuntimeState.Failed))
    }

    @Test
    fun splitKeepsLoopbackOnDirectWhileRemoteUsesTor() {
        val direct = RecordingTransport("direct")
        val tor = RecordingTransport("tor")
        val split = AppTransports.split(
            mode = { NetworkMode.TorOnly },
            torState = { TorRuntimeState.Ready },
            direct = AppTransport(direct, direct, direct),
            tor = { AppTransport(tor, tor, tor) },
        )
        split.http.get("http://127.0.0.1:3334/v1/bundle?from=0", emptyMap())
        split.http.get("https://relay.zapstore.dev", emptyMap())
        assertEquals(listOf("http://127.0.0.1:3334/v1/bundle?from=0"), direct.urls)
        assertEquals(listOf("https://relay.zapstore.dev"), tor.urls)
    }

    @Test
    fun splitDoesNotFallBackToDirectWhenTorFails() {
        val direct = RecordingTransport("direct")
        val split = AppTransports.split(
            mode = { NetworkMode.TorOnly },
            torState = { TorRuntimeState.Failed },
            direct = AppTransport(direct, direct, direct),
            tor = { null },
        )
        try {
            split.http.get("https://relay.zapstore.dev", emptyMap())
            throw AssertionError("expected TorUnavailableException")
        } catch (failure: TorUnavailableException) {
            assertTrue(failure.message!!.contains("refusing Direct fallback"))
        }
        assertTrue(direct.urls.isEmpty())
    }

    @Test
    fun splitAllowsLoopbackDuringBootstrap() {
        val direct = RecordingTransport("direct")
        val split = AppTransports.split(
            mode = { NetworkMode.TorOnly },
            torState = { TorRuntimeState.Bootstrapping },
            direct = AppTransport(direct, direct, direct),
            tor = { null },
        )
        split.http.get("http://localhost:3334/v1/bundle?from=0", emptyMap())
        assertEquals(1, direct.urls.size)
        try {
            split.http.get("https://cdn.zapstore.dev/p/ab.webp", emptyMap())
            throw AssertionError("expected TorUnavailableException")
        } catch (failure: TorUnavailableException) {
            assertTrue(failure.message!!.contains("bootstrapping"))
        }
    }

    @Test
    fun deleteArtiCacheRemovesContentsAndRecreatesDir() {
        val cache = File.createTempFile("arti-cache", "").apply {
            delete()
            mkdirs()
        }
        File(cache, "dir.sqlite3").writeText("consensus")
        File(cache, "dir_blobs").mkdirs()
        File(cache, "dir_blobs/blob").writeText("blob")
        deleteArtiCache(cache)
        assertTrue(cache.isDirectory)
        assertEquals(emptyList<String>(), cache.list()?.toList().orEmpty())
        cache.deleteRecursively()
    }

    @Test
    fun fromStoredMigratesLegacyModeNames() {
        assertEquals(NetworkMode.TorOnly, NetworkMode.fromStored(null))
        assertEquals(NetworkMode.TorOnly, NetworkMode.fromStored("Tor"))
        assertEquals(NetworkMode.DirectOnly, NetworkMode.fromStored("Direct"))
        assertEquals(NetworkMode.TorOnly, NetworkMode.fromStored("TorWithDirectFallback"))
        assertEquals(NetworkMode.TorOnly, NetworkMode.fromStored("TorFallback"))
    }
}

private class FakeArti(override val streams: TorStreams?) : ArtiEngine {
    override fun start() = Unit
    override fun destroy() = Unit
    override fun close() = Unit
}

/** Stands in for Arti: every stream is a loopback TCP connection to [port], whatever host was asked for. */
private class TcpTorStreams(private val port: Int) : TorStreams {
    val connects = java.util.concurrent.CopyOnWriteArrayList<Triple<String, Int, TorIsolation>>()
    private val sockets = java.util.concurrent.ConcurrentHashMap<Long, java.net.Socket>()
    private val next = java.util.concurrent.atomic.AtomicLong(1)

    override fun connect(host: String, port: Int, isolation: TorIsolation, timeoutMs: Int): Long {
        connects += Triple(host, port, isolation)
        val socket = java.net.Socket().apply { connect(java.net.InetSocketAddress("127.0.0.1", this@TcpTorStreams.port), timeoutMs) }
        return next.getAndIncrement().also { sockets[it] = socket }
    }

    override fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val socket = sockets[handle] ?: throw java.net.SocketException("Socket closed")
        socket.soTimeout = timeoutMs
        return socket.getInputStream().read(buffer, offset, length)
    }

    override fun write(handle: Long, buffer: ByteArray, offset: Int, length: Int) {
        val output = (sockets[handle] ?: throw java.net.SocketException("Socket closed")).getOutputStream()
        output.write(buffer, offset, length)
        output.flush()
    }

    override fun close(handle: Long) {
        sockets.remove(handle)?.close()
    }
}

private class RecordingTransport(
    val name: String,
) : HttpTransport, WebSocketFactory, Call.Factory {
    val urls = mutableListOf<String>()

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        urls += url
        return HttpResponse(200, emptyMap(), ByteArray(0))
    }

    override fun open(url: String, listener: WebSocketListener): WebSocketSession {
        urls += url
        return object : WebSocketSession {
            override fun send(text: String) = Unit
            override fun close() = Unit
        }
    }

    override fun newCall(request: Request): Call = error("unused")
}
