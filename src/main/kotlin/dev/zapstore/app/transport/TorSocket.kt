package dev.zapstore.app.transport

import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketImpl
import java.net.SocketOptions
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/** Streams that never share a circuit (`zapstore/SPEC.md#network-transport`). */
enum class TorIsolation {
    Anonymous,
    Identity,
}

/**
 * In-process Tor streams. Nothing listens on a port: only this process can open them.
 * Every call blocks its thread and throws [IOException] on failure.
 */
interface TorStreams {
    /** Opens a stream to [host]:[port] resolved by the exit; returns its handle. */
    fun connect(host: String, port: Int, isolation: TorIsolation, timeoutMs: Int): Long

    /** Bytes read into [buffer], or -1 at end of stream. Throws [java.net.SocketTimeoutException] after [timeoutMs] (0 waits forever) without losing data. */
    fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** Writes and flushes all [length] bytes. */
    fun write(handle: Long, buffer: ByteArray, offset: Int, length: Int)

    /** Ends the stream and wakes any read or write blocked on it. Safe to call twice. */
    fun close(handle: Long)
}

class TorSocketFactory(
    private val streams: TorStreams,
    private val isolation: TorIsolation,
) : SocketFactory() {
    override fun createSocket(): Socket = TorSocket(TorSocketImpl(streams, isolation))

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket(address, port)
}

private class TorSocket(impl: SocketImpl) : Socket(impl)

/**
 * A [SocketImpl] over one Tor stream, so OkHttp and the platform TLS stack use it like TCP.
 *
 * [getFileDescriptor] throws: there is no descriptor, and Android's Conscrypt only layers TLS
 * through its engine (stream-based) socket when the probe for one fails.
 */
internal class TorSocketImpl(
    private val streams: TorStreams,
    private val isolation: TorIsolation,
) : SocketImpl() {
    @Volatile private var handle = 0L
    @Volatile private var timeoutMs = 0
    private val closed = AtomicBoolean()
    private val input = Input()
    private val output = Output()

    override fun create(stream: Boolean) {
        if (!stream) throw SocketException("Tor carries TCP streams only")
    }

    override fun connect(host: String, port: Int) = connect(InetSocketAddress.createUnresolved(host, port), 0)

    override fun connect(address: InetAddress, port: Int) = connect(InetSocketAddress(address, port), 0)

    override fun connect(address: SocketAddress, timeout: Int) {
        val target = address as? InetSocketAddress ?: throw SocketException("Tor needs an InetSocketAddress")
        if (closed.get()) throw SocketException("Socket closed")
        val opened = streams.connect(torTargetHost(target), target.port, isolation, timeout)
        handle = opened
        if (closed.get()) streams.close(opened)
        this.address = target.address ?: InetAddress.getByAddress(target.hostString, ByteArray(4))
        this.port = target.port
    }

    override fun bind(host: InetAddress, port: Int) = throw SocketException("Tor sockets do not bind")

    override fun listen(backlog: Int) = throw SocketException("Tor sockets do not listen")

    override fun accept(s: SocketImpl) = throw SocketException("Tor sockets do not accept")

    override fun getInputStream(): InputStream = input

    override fun getOutputStream(): OutputStream = output

    override fun available(): Int = 0

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handle.takeIf { it != 0L }?.let(streams::close)
    }

    override fun sendUrgentData(data: Int) = throw SocketException("Tor sockets do not send urgent data")

    override fun getFileDescriptor(): FileDescriptor =
        throw UnsupportedOperationException("Tor sockets have no file descriptor")

    override fun setOption(optID: Int, value: Any?) {
        if (optID == SocketOptions.SO_TIMEOUT) timeoutMs = (value as? Int) ?: 0
    }

    override fun getOption(optID: Int): Any? = when (optID) {
        SocketOptions.SO_TIMEOUT -> timeoutMs
        SocketOptions.SO_BINDADDR -> InetAddress.getByAddress(ByteArray(4))
        SocketOptions.TCP_NODELAY -> true
        SocketOptions.SO_KEEPALIVE -> false
        SocketOptions.SO_LINGER -> -1
        SocketOptions.SO_RCVBUF, SocketOptions.SO_SNDBUF -> BUFFER_HINT_BYTES
        else -> null
    }

    private fun openHandle(): Long {
        if (closed.get()) throw SocketException("Socket closed")
        return handle.takeIf { it != 0L } ?: throw SocketException("Socket is not connected")
    }

    private inner class Input : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkRange(b, off, len)
            if (len == 0) return 0
            return streams.read(openHandle(), b, off, len, timeoutMs)
        }

        override fun available(): Int = 0

        override fun close() = this@TorSocketImpl.close()
    }

    private inner class Output : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            checkRange(b, off, len)
            if (len == 0) return
            streams.write(openHandle(), b, off, len)
        }

        override fun close() = this@TorSocketImpl.close()
    }

    private companion object {
        const val BUFFER_HINT_BYTES = 64 * 1024

        fun checkRange(b: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException("off $off len $len size ${b.size}")
        }
    }
}

/**
 * The exit resolves names: send the hostname, never the fake address [TorFakeDns] handed OkHttp.
 * Reads [InetSocketAddress.getHostString], not `getHostName`, which can resolve outside Tor.
 */
internal fun torTargetHost(address: InetSocketAddress): String {
    address.address?.let(TorFakeDns::hostnameFor)?.let { return it }
    return address.hostString.takeIf { it.isNotBlank() } ?: throw IOException("Tor target host is missing")
}
