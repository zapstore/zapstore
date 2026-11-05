package dev.zapstore.app.transport

import dev.zapstore.iolite.HttpResponse
import dev.zapstore.iolite.HttpTransport
import dev.zapstore.iolite.WebSocketFactory
import dev.zapstore.iolite.WebSocketListener
import dev.zapstore.iolite.WebSocketSession
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Tor never falls back to Direct (`zapstore/SPEC.md#network-transport`). */
enum class NetworkMode {
    TorOnly,
    DirectOnly,
    ;

    val usesTor: Boolean get() = this != DirectOnly

    companion object {
        /** The retired Tor-with-Direct-fallback values read as Tor. */
        fun fromStored(value: String?): NetworkMode = when (value) {
            "Direct", DirectOnly.name -> DirectOnly
            else -> TorOnly
        }
    }
}

enum class TorRuntimeState {
    Stopped,
    Bootstrapping,
    Ready,
    Dormant,
    Failed,
}

/** Remote image hosts can be reached without throwing [TorUnavailableException]. */
fun remoteImagesReady(mode: NetworkMode, torState: TorRuntimeState): Boolean =
    mode == NetworkMode.DirectOnly ||
        torState == TorRuntimeState.Ready ||
        torState == TorRuntimeState.Dormant

class TorUnavailableException(message: String) : IllegalStateException(message)

interface ArtiEngine : AutoCloseable {
    val socksPort: Int
    fun start()
    fun stop()
    /** Drop the in-process TorClient so cache files can be deleted. */
    fun destroy()
}

data class AppTransport(
    val http: HttpTransport,
    val webSocket: WebSocketFactory,
    val callFactory: Call.Factory,
)

object AppTransports {
    fun create(mode: NetworkMode, arti: ArtiEngine? = null): AppTransport = when (mode) {
        NetworkMode.DirectOnly -> directTransport()
        NetworkMode.TorOnly -> {
            val socksPort = arti?.socksPort ?: 0
            when {
                socksPort > 0 -> torTransport(socksPort)
                arti == null -> throw TorUnavailableException(
                    "Tor is selected and Arti is not available; refusing Direct fallback",
                )
                else -> throw TorUnavailableException(
                    "Tor is selected and Arti is not ready; refusing Direct fallback",
                )
            }
        }
    }

    fun directTransport(): AppTransport {
        val transport = OkHttpAppTransport(directClient())
        return AppTransport(transport, transport, transport.client)
    }

    fun torTransport(socksPort: Int): AppTransport {
        val anonymous = OkHttpAppTransport(socksClient(socksPort))
        val identity = OkHttpAppTransport(socksClient(socksPort))
        return AppTransport(anonymous, identity, anonymous.client)
    }

    fun directImageTransport(): AppTransport {
        val transport = OkHttpAppTransport(imageDirectClient())
        return AppTransport(transport, transport, transport.client)
    }

    fun torImageTransport(socksPort: Int): AppTransport {
        val transport = OkHttpAppTransport(imageSocksClient(socksPort))
        return AppTransport(transport, transport, transport.client)
    }

    fun split(
        mode: () -> NetworkMode,
        torState: () -> TorRuntimeState,
        direct: AppTransport,
        tor: () -> AppTransport?,
    ): AppTransport {
        val router = SplitRouter(mode, torState, direct, tor)
        return AppTransport(router, router, router)
    }
}

fun isLoopbackHost(host: String): Boolean {
    val value = host.trim().trimStart('[').trimEnd(']').lowercase()
    return value == "localhost" ||
        value == "127.0.0.1" ||
        value == "::1" ||
        value == "0:0:0:0:0:0:0:1"
}

fun isLoopbackUrl(url: String): Boolean {
    val host = runCatching { URI(url).host }.getOrNull() ?: return false
    return isLoopbackHost(host)
}

object TorFakeDns : Dns {
    private val hosts = ConcurrentHashMap<String, String>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (isLoopbackHost(hostname)) return Dns.SYSTEM.lookup(hostname)
        val address = fakeTorAddress(hostname)
        hosts[checkNotNull(address.hostAddress)] = hostname
        return listOf(address)
    }

    fun hostnameFor(address: InetAddress): String? = address.hostAddress?.let { hosts[it] }
}

/** HTTP/1.1 only: HTTP/2 over Arti's SOCKS port fails the CDN fetches Coil uses for icons. */
internal fun socksClient(socksPort: Int): OkHttpClient = baseClient()
    .proxy(Proxy.NO_PROXY)
    .socketFactory(Socks5SocketFactory(InetSocketAddress("127.0.0.1", socksPort)))
    .dns(TorFakeDns)
    .protocols(listOf(Protocol.HTTP_1_1))
    .build()

internal fun imageSocksClient(socksPort: Int): OkHttpClient = socksClient(socksPort).newBuilder()
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

internal fun directClient(): OkHttpClient = baseClient().build()

internal fun imageDirectClient(): OkHttpClient = directClient().newBuilder()
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

private fun fakeTorAddress(hostname: String): InetAddress {
    val hash = hostname.hashCode()
    val bytes = byteArrayOf(
        10,
        (hash ushr 16).toByte(),
        (hash ushr 8).toByte(),
        (hash.let { if (it and 0xff == 0) 1 else it }).toByte(),
    )
    return InetAddress.getByAddress(hostname, bytes)
}

private fun baseClient(): OkHttpClient.Builder = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .protocols(listOf(Protocol.HTTP_1_1, Protocol.HTTP_2))
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)

fun OkHttpClient.closeAppConnections() {
    dispatcher.cancelAll()
    runCatching { connectionPool.evictAll() }
}

class OkHttpAppTransport(
    val client: OkHttpClient,
) : HttpTransport, WebSocketFactory {
    constructor() : this(directClient())

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        client.newCall(request(url, headers)).execute().use { response ->
            return HttpResponse(
                status = response.code,
                headers = response.reasonHeaders(),
                body = response.body.bytes().takeIf { response.code == 200 },
            )
        }
    }

    /** Streams a 200 body to [destination] in segments; the heap never holds more than one. */
    override fun download(url: String, headers: Map<String, String>, destination: File, maxBytes: Long): HttpResponse {
        client.newCall(request(url, headers)).execute().use { response ->
            if (response.code != 200) return HttpResponse(response.code, response.reasonHeaders(), null)
            if (response.body.contentLength() > maxBytes) throw IOException("response body exceeds $maxBytes bytes")
            val source = response.body.source()
            destination.sink().buffer().use { sink ->
                var total = 0L
                while (true) {
                    val read = source.read(sink.buffer, DOWNLOAD_SEGMENT_BYTES)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw IOException("response body exceeds $maxBytes bytes")
                    sink.emitCompleteSegments()
                }
            }
            return HttpResponse(response.code, response.reasonHeaders(), null)
        }
    }

    private fun request(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun Response.reasonHeaders(): Map<String, String> = buildMap {
        header("X-Error-Code")?.let { put("X-Error-Code", it) }
        header("X-Reason")?.let { put("X-Reason", it) }
    }

    override fun open(url: String, listener: WebSocketListener): WebSocketSession {
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    listener.onOpen()
                }

                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    listener.onMessage(text)
                }

                override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    listener.onClosing(code, reason)
                }

                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    listener.onFailure(t)
                }
            },
        )
        return object : WebSocketSession {
            override fun send(text: String) {
                check(socket.send(text)) { "WebSocket send failed" }
            }

            override fun close() {
                socket.close(1000, null)
            }
        }
    }

    fun closeConnections() {
        client.closeAppConnections()
    }

    private companion object {
        const val DOWNLOAD_SEGMENT_BYTES = 64L * 1024
    }
}

internal class SplitRouter(
    private val mode: () -> NetworkMode,
    private val torState: () -> TorRuntimeState,
    private val direct: AppTransport,
    private val tor: () -> AppTransport?,
) : HttpTransport, WebSocketFactory, Call.Factory {
    override fun get(url: String, headers: Map<String, String>): HttpResponse =
        route(url).http.get(url, headers)

    override fun download(url: String, headers: Map<String, String>, destination: File, maxBytes: Long): HttpResponse =
        route(url).http.download(url, headers, destination, maxBytes)

    override fun open(url: String, listener: WebSocketListener): WebSocketSession =
        route(url).webSocket.open(url, listener)

    override fun newCall(request: Request): Call = routeHost(request.url.host).callFactory.newCall(request)

    private fun route(url: String): AppTransport =
        if (!mode().usesTor || isLoopbackUrl(url)) direct else remote()

    private fun routeHost(host: String): AppTransport =
        if (!mode().usesTor || isLoopbackHost(host)) direct else remote()

    private fun remote(): AppTransport = when (torState()) {
        TorRuntimeState.Ready, TorRuntimeState.Dormant -> tor() ?: throw TorUnavailableException(
            "Tor is selected and Arti is not available; refusing Direct fallback",
        )
        TorRuntimeState.Bootstrapping -> throw TorUnavailableException(
            "Tor is bootstrapping; refusing Direct fallback",
        )
        TorRuntimeState.Failed, TorRuntimeState.Stopped -> throw TorUnavailableException(
            "Tor is not available; refusing Direct fallback",
        )
    }
}
