/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dev.zapstore.app.transport

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * JNI bridge to the custom-built Arti native library (`libarti_android.so`).
 *
 * The native TorClient is created once via [initialize] and persists until [destroy].
 * Streams are opened in process through [connect]; the library binds no port.
 */
object ArtiNative {
    init {
        System.loadLibrary("arti_android")
    }

    external fun getVersion(): String

    external fun setLogCallback(callback: ArtiLogCallback)

    /** Create the Arti runtime. Returns 0 on success, negative on error. */
    external fun initialize(dataDir: String): Int

    /** 1 when circuits can be built, 0 when not yet, -1 when there is no client. */
    external fun isBootstrapped(): Int

    /** Directory-download progress in permille (0..1000), or -1 when there is no client. */
    external fun bootstrapProgressPermille(): Int

    /** Stream handle (> 0), or a negative `CONNECT_*` code. [isolation] is a [TorIsolation] ordinal. */
    external fun connect(host: String, port: Int, isolation: Int, timeoutMs: Int): Long

    /** Bytes read, or a negative `IO_*` code. [timeoutMs] 0 waits forever. */
    external fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** 0 once all bytes are written and flushed, or a negative `IO_*` code. */
    external fun write(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Ends the stream and wakes its blocked reads and writes. Unknown handles are ignored. */
    external fun closeStream(handle: Long)

    /** Drop the in-process TorClient and every open stream so the next [initialize] rebuilds it. */
    external fun destroy(): Int
}

/** [TorStreams] over [ArtiNative]. The codes mirror `tools/arti-build/src/lib.rs`. */
internal object ArtiStreams : TorStreams {
    override fun connect(host: String, port: Int, isolation: TorIsolation, timeoutMs: Int): Long {
        val handle = ArtiNative.connect(host, port, isolation.ordinal, timeoutMs)
        if (handle > 0) return handle
        throw when (handle.toInt()) {
            CONNECT_NO_CLIENT -> ConnectException("Arti is not running")
            CONNECT_HOST_NOT_FOUND -> UnknownHostException("Arti cannot resolve $host")
            CONNECT_REFUSED -> ConnectException("Arti connection refused by $host:$port")
            CONNECT_EXIT_POLICY -> ConnectException("Arti exit policy rejects $host:$port")
            CONNECT_NETWORK_UNREACHABLE -> NoRouteToHostException("Arti cannot reach the network of $host")
            CONNECT_TIMEOUT -> SocketTimeoutException("Arti timed out connecting to $host:$port")
            CONNECT_BAD_TARGET -> UnknownHostException("Arti rejects target $host:$port")
            else -> ConnectException("Arti could not open a circuit to $host:$port ($handle)")
        }
    }

    override fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val read = ArtiNative.read(handle, buffer, offset, length, timeoutMs)
        return if (read >= 0 || read == IO_EOF) read else throw ioFailure(read, "read")
    }

    override fun write(handle: Long, buffer: ByteArray, offset: Int, length: Int) {
        val result = ArtiNative.write(handle, buffer, offset, length)
        if (result != 0) throw ioFailure(result, "write")
    }

    override fun close(handle: Long) = ArtiNative.closeStream(handle)

    private fun ioFailure(code: Int, op: String): IOException = when (code) {
        IO_TIMEOUT -> SocketTimeoutException("Arti $op timed out")
        IO_CLOSED -> SocketException("Socket closed")
        else -> SocketException("Arti stream $op failed ($code)")
    }

    private const val CONNECT_NO_CLIENT = -1
    private const val CONNECT_HOST_NOT_FOUND = -3
    private const val CONNECT_REFUSED = -4
    private const val CONNECT_EXIT_POLICY = -5
    private const val CONNECT_NETWORK_UNREACHABLE = -6
    private const val CONNECT_TIMEOUT = -7
    private const val CONNECT_BAD_TARGET = -8

    private const val IO_EOF = -1
    private const val IO_TIMEOUT = -2
    private const val IO_CLOSED = -3
}

fun interface ArtiLogCallback {
    fun onLogLine(line: String)
}
