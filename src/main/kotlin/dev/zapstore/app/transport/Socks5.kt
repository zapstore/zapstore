package dev.zapstore.app.transport

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * SOCKS5 client that always sends the destination hostname (ATYP=DOMAIN).
 *
 * Java's [java.net.Proxy.Type.SOCKS] often CONNECTs to `0.0.0.0` instead of the name
 * when [TorFakeDns] is used; Arti then replies with "SOCKS server general failure".
 */
class Socks5SocketFactory(
    private val proxyAddress: InetSocketAddress,
) : SocketFactory() {
    override fun createSocket(): Socket = Socks5Socket(proxyAddress)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket(address, port)
}

internal class Socks5Socket(
    private val proxyAddress: InetSocketAddress,
) : Socket() {
    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        val target = endpoint as? InetSocketAddress
            ?: throw IOException("SOCKS5 requires an InetSocketAddress")
        super.connect(proxyAddress, timeout)
        socks5Connect(getOutputStream(), getInputStream(), socks5TargetHost(target), target.port)
    }
}

internal fun socks5TargetHost(address: InetSocketAddress): String {
    val raw = address.hostString
    if (raw.isNotBlank() && !isNumericIp(raw)) return raw
    address.address?.let { ip ->
        TorFakeDns.hostnameFor(ip)?.let { return it }
        ip.hostName?.takeIf { it.isNotBlank() && !isNumericIp(it) }?.let { return it }
    }
    throw IOException("SOCKS5 target hostname is missing")
}

internal fun socks5Connect(output: OutputStream, input: InputStream, host: String, port: Int) {
    val hostBytes = host.toByteArray(Charsets.US_ASCII)
    require(hostBytes.isNotEmpty() && hostBytes.size <= 255) { "SOCKS5 hostname length ${hostBytes.size}" }
    require(port in 1..65535) { "SOCKS5 port $port" }

    output.write(byteArrayOf(0x05, 0x01, 0x00))
    output.flush()
    val greeting = ByteArray(2)
    DataInputStream(input).readFully(greeting)
    if (greeting[0] != 0x05.toByte()) throw IOException("SOCKS5 version ${greeting[0]}")
    if (greeting[1] != 0x00.toByte()) throw IOException("SOCKS5 authentication rejected")

    val request = ByteArray(7 + hostBytes.size)
    request[0] = 0x05
    request[1] = 0x01
    request[2] = 0x00
    request[3] = 0x03
    request[4] = hostBytes.size.toByte()
    hostBytes.copyInto(request, 5)
    request[5 + hostBytes.size] = (port ushr 8).toByte()
    request[6 + hostBytes.size] = port.toByte()
    output.write(request)
    output.flush()

    val header = ByteArray(4)
    val reply = DataInputStream(input)
    reply.readFully(header)
    if (header[0] != 0x05.toByte()) throw IOException("SOCKS5 reply version ${header[0]}")
    val status = header[1].toInt() and 0xff
    if (status != 0) throw IOException(socks5StatusMessage(status))
    when (header[3].toInt() and 0xff) {
        0x01 -> reply.readFully(ByteArray(6))
        0x04 -> reply.readFully(ByteArray(18))
        0x03 -> {
            val length = ByteArray(1)
            reply.readFully(length)
            reply.readFully(ByteArray((length[0].toInt() and 0xff) + 2))
        }
        else -> throw IOException("SOCKS5 unknown address type ${header[3]}")
    }
}

private fun isNumericIp(host: String): Boolean =
    host == "0.0.0.0" ||
        host == "::" ||
        host == "0:0:0:0:0:0:0:0" ||
        host.all { it.isDigit() || it == '.' } ||
        host.contains(':')

private fun socks5StatusMessage(status: Int): String = when (status) {
    0x01 -> "Arti rejected the circuit"
    0x03 -> "Arti cannot reach the destination network"
    0x04 -> "Arti cannot reach the destination host"
    0x05 -> "Arti connection refused"
    0x07 -> "SOCKS5 command not supported"
    0x08 -> "SOCKS5 address type not supported"
    else -> "Arti SOCKS5 status $status"
}
