package dev.zapstore.iolite

import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.util.Locale

internal data class TarMember(val name: String, val data: ByteArray)

internal object TarZstd {
    fun read(
        compressed: InputStream,
        maxCompressedBytes: Long,
        maxUncompressedBytes: Long,
        maxMembers: Int,
        maxMemberBytes: Long,
    ): List<TarMember> {
        val limited = compressed.bounded(maxCompressedBytes, "compressed catalog bundle exceeds limit")
        val zstd = ZstdInputStream(limited)
        val tar = CountingInputStream(zstd)
        val members = ArrayList<TarMember>()
        val names = HashSet<String>()
        while (true) {
            if (tar.bytesRead >= maxUncompressedBytes) {
                error("uncompressed catalog bundle exceeds limit")
            }
            val header = ByteArray(512)
            if (!tar.readFully(header)) break
            if (header.all { it == 0.toByte() }) {
                val next = ByteArray(512)
                tar.readFully(next)
                break
            }
            val name = header.cString(0, 100)
            val size = header.octal(124, 12)
            val type = header[156].toInt().toChar()
            val magic = header.cString(257, 6)
            if (magic.startsWith("ustar")) {
                // ustar
            }
            val prefix = header.cString(345, 155)
            val fullName = if (prefix.isEmpty()) name else "$prefix/$name"
            rejectName(fullName)
            when (type) {
                '0', '\u0000' -> Unit
                'x', 'g', 'L', 'K' -> error("PAX or GNU tar extensions are not allowed")
                '1', '2', '3', '4', '5', '6' -> error("directories, links, and devices are not allowed")
                else -> error("unsupported tar type $type")
            }
            if (size > maxMemberBytes) error("catalog member $fullName exceeds size limit")
            if (members.size >= maxMembers) error("catalog bundle has too many members")
            if (!names.add(fullName)) error("duplicate tar path $fullName")
            val data = tar.readExact(size.toInt())
            val padding = ((512 - (size % 512)) % 512).toInt()
            if (padding > 0) tar.skipFully(padding.toLong())
            if (tar.bytesRead > maxUncompressedBytes) error("uncompressed catalog bundle exceeds limit")
            members += TarMember(fullName, data)
        }
        if (members.isEmpty() || members.first().name != "manifest.json") {
            error("manifest.json must be the first tar member")
        }
        return members
    }

    private fun rejectName(name: String) {
        if (name.isEmpty() || name.startsWith("/") || name.contains('\u0000') || name.contains("..") || name.contains('\\')) {
            error("illegal tar path $name")
        }
    }
}

private fun ByteArray.cString(offset: Int, length: Int): String {
    var end = offset
    val limit = offset + length
    while (end < limit && this[end] != 0.toByte()) end++
    return copyOfRange(offset, end).toString(Charsets.UTF_8)
}

private fun ByteArray.octal(offset: Int, length: Int): Long {
    val raw = cString(offset, length).trim()
    if (raw.isEmpty()) return 0
    return raw.trimEnd { it == ' ' || it == '\u0000' }.toLong(8)
}

private class CountingInputStream(private val input: InputStream) : InputStream() {
    var bytesRead = 0L
        private set

    override fun read(): Int {
        val value = input.read()
        if (value >= 0) bytesRead++
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)
        if (n > 0) bytesRead += n
        return n
    }

    fun readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val n = read(buffer, offset, buffer.size - offset)
            if (n < 0) {
                if (offset == 0) return false
                throw EOFException("truncated tar")
            }
            offset += n
        }
        return true
    }

    fun readExact(size: Int): ByteArray {
        val out = ByteArrayOutputStream(size)
        var remaining = size
        val buf = ByteArray(minOf(size, 16_384))
        while (remaining > 0) {
            val n = read(buf, 0, minOf(remaining, buf.size))
            if (n < 0) throw EOFException("truncated tar member")
            out.write(buf, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    fun skipFully(n: Long) {
        var remaining = n
        val buf = ByteArray(512)
        while (remaining > 0) {
            val read = read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (read < 0) throw EOFException("truncated tar padding")
            remaining -= read
        }
    }
}

private fun InputStream.bounded(limit: Long, message: String): InputStream = object : InputStream() {
    var readCount = 0L
    override fun read(): Int {
        val value = this@bounded.read()
        if (value >= 0) {
            readCount++
            if (readCount > limit) error(message)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = this@bounded.read(b, off, len)
        if (n > 0) {
            readCount += n
            if (readCount > limit) error(message)
        }
        return n
    }
}

internal fun ByteArray.sha256Hex(): String = Hex.encode(Crypto.sha256(this))

internal fun String.asciiLower(): String = lowercase(Locale.ROOT)
