package dev.zapstore.app.transport

import okhttp3.Call
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Proxy
import dev.zapstore.iolite.HttpResponse
import dev.zapstore.iolite.HttpTransport
import dev.zapstore.iolite.WebSocketFactory
import dev.zapstore.iolite.WebSocketListener
import dev.zapstore.iolite.WebSocketSession

class AppTransportTest {
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
    fun torModeWithClosedSocksPortDoesNotFallBackToDirect() {
        try {
            AppTransports.create(NetworkMode.TorOnly, FakeArti(socksPort = 0))
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
    fun socksClientUsesHostnameSocks5AndResolvesDnsInsideArti() {
        val client = socksClient(9050)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertTrue(client.socketFactory is Socks5SocketFactory)
        assertEquals(TorFakeDns, client.dns)
        assertEquals(listOf(okhttp3.Protocol.HTTP_1_1), client.protocols)
        assertTrue(!client.followRedirects)
    }

    @Test
    fun imageSocksClientFollowsRedirects() {
        val client = imageSocksClient(9050)
        assertTrue(client.followRedirects)
        assertTrue(client.followSslRedirects)
        assertEquals(listOf(okhttp3.Protocol.HTTP_1_1), client.protocols)
    }

    @Test
    fun remoteImagesReadyOnlyWhenDirectOrArtiIsUp() {
        assertTrue(remoteImagesReady(NetworkMode.DirectOnly, TorRuntimeState.Stopped))
        assertTrue(remoteImagesReady(NetworkMode.TorOnly, TorRuntimeState.Ready))
        assertTrue(remoteImagesReady(NetworkMode.TorOnly, TorRuntimeState.Dormant))
        assertTrue(!remoteImagesReady(NetworkMode.TorOnly, TorRuntimeState.Bootstrapping))
        assertTrue(!remoteImagesReady(NetworkMode.TorOnly, TorRuntimeState.Failed))
        assertTrue(remoteImagesReady(NetworkMode.TorWithDirectFallback, TorRuntimeState.Failed))
        assertTrue(!remoteImagesReady(NetworkMode.TorWithDirectFallback, TorRuntimeState.Bootstrapping))
    }

    @Test
    fun socks5ConnectSendsDomainNameNotIpv4Zero() {
        val server = java.net.ServerSocket(0)
        val port = server.localPort
        val received = java.util.concurrent.CompletableFuture<ByteArray>()
        Thread {
            server.use { listener ->
                listener.accept().use { sock ->
                    val input = java.io.DataInputStream(sock.getInputStream())
                    val output = sock.getOutputStream()
                    val greeting = ByteArray(3)
                    input.readFully(greeting)
                    output.write(byteArrayOf(0x05, 0x00))
                    output.flush()
                    val header = ByteArray(5)
                    input.readFully(header)
                    val host = ByteArray(header[4].toInt() and 0xff)
                    input.readFully(host)
                    val destPort = ByteArray(2)
                    input.readFully(destPort)
                    received.complete(header + host + destPort)
                    output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                }
            }
        }.apply { isDaemon = true }.start()

        val fake = java.net.InetAddress.getByAddress("relay.zapstore.dev", ByteArray(4))
        Socks5SocketFactory(java.net.InetSocketAddress("127.0.0.1", port))
            .createSocket()
            .use { it.connect(java.net.InetSocketAddress(fake, 443), 2_000) }

        val request = received.get(2, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(0x05, request[0].toInt() and 0xff)
        assertEquals(0x01, request[1].toInt() and 0xff)
        assertEquals(0x03, request[3].toInt() and 0xff)
        val hostLen = request[4].toInt() and 0xff
        assertEquals("relay.zapstore.dev", String(request, 5, hostLen, Charsets.US_ASCII))
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
        split.http.get("http://127.0.0.1:3334/deltas?from=0", emptyMap())
        split.http.get("https://relay.zapstore.dev", emptyMap())
        assertEquals(listOf("http://127.0.0.1:3334/deltas?from=0"), direct.urls)
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
        split.http.get("http://localhost:3334/deltas?from=0", emptyMap())
        assertEquals(1, direct.urls.size)
        try {
            split.http.get("https://cdn.zapstore.dev/p/ab.webp", emptyMap())
            throw AssertionError("expected TorUnavailableException")
        } catch (failure: TorUnavailableException) {
            assertTrue(failure.message!!.contains("bootstrapping"))
        }
    }

    @Test
    fun fallbackUsesDirectWhenTorFails() {
        val transport = AppTransports.create(NetworkMode.TorWithDirectFallback)
        assertTrue(transport.http is OkHttpAppTransport)
    }

    @Test
    fun splitFallsBackToDirectWhenFallbackModeAndTorFails() {
        val direct = RecordingTransport("direct")
        val split = AppTransports.split(
            mode = { NetworkMode.TorWithDirectFallback },
            torState = { TorRuntimeState.Failed },
            direct = AppTransport(direct, direct, direct),
            tor = { null },
        )
        split.http.get("https://relay.zapstore.dev", emptyMap())
        assertEquals(listOf("https://relay.zapstore.dev"), direct.urls)
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
        assertEquals(NetworkMode.TorWithDirectFallback, NetworkMode.fromStored("TorWithDirectFallback"))
    }
}

private class FakeArti(override val socksPort: Int) : ArtiEngine {
    override fun start() = Unit
    override fun stop() = Unit
    override fun destroy() = Unit
    override fun close() = Unit
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
