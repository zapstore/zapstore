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

/**
 * JNI bridge to the custom-built Arti native library (`libarti_android.so`).
 *
 * The native TorClient is created once via [initialize] and persists for the
 * process lifetime. The SOCKS proxy can be started and stopped independently
 * via [startSocksProxy] and [stopSocksProxy] without dropping the client.
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

    /** Bind SOCKS5 on [port]. Returns 0 on success, negative on error. */
    external fun startSocksProxy(port: Int): Int

    /** Stop the SOCKS listener. The TorClient stays alive. */
    external fun stopSocksProxy(): Int

    /** Drop the in-process TorClient so the next [initialize] rebuilds it. */
    external fun destroy(): Int
}

fun interface ArtiLogCallback {
    fun onLogLine(line: String)
}
