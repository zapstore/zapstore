package dev.zapstore.iolite

import com.github.luben.zstd.ZstdInputStream
import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

internal data class TarMember(val name: String, val data: ByteArray)

internal class TarLimits(
    val maxCompressedBytes: Long,
    val maxUncompressedBytes: Long,
    val maxMembers: Int,
    val maxMemberBytes: Long,
) {
    companion object {
        fun of(config: IoliteConfig) = TarLimits(
            maxCompressedBytes = config.maxCompressedBytes,
            maxUncompressedBytes = config.maxUncompressedBytes,
            maxMembers = config.maxMembers,
            maxMemberBytes = config.maxUncompressedBytes,
        )
    }
}

internal object TarZstd {
    /**
     * Streams the members in tar order. [visit] gets a stream that ends with the member; whatever it
     * leaves unread is skipped. Returning false stops the walk. Only one member is open at a time, so
     * memory does not grow with the bundle. Returns the compressed bytes read.
     */
    fun forEach(compressed: InputStream, limits: TarLimits, visit: (name: String, size: Long, data: InputStream) -> Boolean): Long {
        val limited = BoundedInputStream(compressed, limits.maxCompressedBytes, "compressed catalog bundle exceeds limit")
        ZstdInputStream(limited).use { zstd ->
            val tar = CountingInputStream(zstd)
            val names = HashSet<String>()
            val header = ByteArray(512)
            while (true) {
                if (tar.bytesRead >= limits.maxUncompressedBytes) error("uncompressed catalog bundle exceeds limit")
                if (!tar.readFully(header)) break
                if (header.all { it == 0.toByte() }) break
                val name = header.cString(0, 100)
                val size = header.octal(124, 12)
                val type = header[156].toInt().toChar()
                val prefix = header.cString(345, 155)
                val fullName = if (prefix.isEmpty()) name else "$prefix/$name"
                rejectName(fullName)
                when (type) {
                    '0', '\u0000' -> Unit
                    'x', 'g', 'L', 'K' -> error("PAX or GNU tar extensions are not allowed")
                    '1', '2', '3', '4', '5', '6' -> error("directories, links, and devices are not allowed")
                    else -> error("unsupported tar type $type")
                }
                if (names.isEmpty() && fullName != "manifest.json") error("manifest.json must be the first tar member")
                if (size > limits.maxMemberBytes) error("catalog member $fullName exceeds size limit")
                if (names.size >= limits.maxMembers) error("catalog bundle has too many members")
                if (!names.add(fullName)) error("duplicate tar path $fullName")
                val member = MemberInputStream(tar, size)
                val more = visit(fullName, size, member)
                member.skipRest()
                val padding = (512 - (size % 512)) % 512
                if (padding > 0) tar.skipFully(padding)
                if (tar.bytesRead > limits.maxUncompressedBytes) error("uncompressed catalog bundle exceeds limit")
                if (!more) return limited.readCount
            }
            if (names.isEmpty()) error("manifest.json must be the first tar member")
        }
        return limited.readCount
    }

    /** Every member in memory. Only for small bundles; import streams with [forEach]. */
    fun read(compressed: InputStream, limits: TarLimits): List<TarMember> {
        val members = ArrayList<TarMember>()
        forEach(compressed, limits) { name, size, data ->
            members += TarMember(name, data.readExactly(size.toInt()))
            true
        }
        return members
    }

    private fun rejectName(name: String) {
        if (name.isEmpty() || name.startsWith("/") || name.contains('\u0000') || name.contains("..") || name.contains('\\')) {
            error("illegal tar path $name")
        }
    }
}

/** Reads exactly [size] bytes into one array, with no intermediate copy. */
internal fun InputStream.readExactly(size: Int): ByteArray {
    val out = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val n = read(out, offset, size - offset)
        if (n < 0) throw EOFException("truncated tar member")
        offset += n
    }
    return out
}

/** SHA-256 of what is left in the stream, read in small blocks. */
internal fun InputStream.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return Hex.encode(digest.digest())
}

private fun ByteArray.cString(offset: Int, length: Int): String {
    var end = offset
    val limit = offset + length
    while (end < limit && this[end] != 0.toByte()) end++
    return String(this, offset, end - offset, Charsets.UTF_8)
}

private fun ByteArray.octal(offset: Int, length: Int): Long {
    val raw = cString(offset, length).trim()
    if (raw.isEmpty()) return 0
    return raw.trimEnd { it == ' ' || it == '\u0000' }.toLong(8)
}

/** One member's bytes; it reports end of stream at the member's end and fails if the tar ends first. */
private class MemberInputStream(private val tar: CountingInputStream, size: Long) : InputStream() {
    private var remaining = size

    override fun read(): Int {
        if (remaining == 0L) return -1
        val value = tar.read()
        if (value < 0) throw EOFException("truncated tar member")
        remaining--
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining == 0L) return -1
        val n = tar.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw EOFException("truncated tar member")
        remaining -= n
        return n
    }

    override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()

    fun skipRest() {
        if (remaining > 0) tar.skipFully(remaining)
        remaining = 0
    }
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

    fun skipFully(n: Long) {
        var remaining = n
        val buf = ByteArray(8 * 1024)
        while (remaining > 0) {
            val read = read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (read < 0) throw EOFException("truncated tar")
            remaining -= read
        }
    }
}

private class BoundedInputStream(private val input: InputStream, private val limit: Long, private val message: String) : InputStream() {
    var readCount = 0L
        private set

    override fun read(): Int {
        val value = input.read()
        if (value >= 0) {
            readCount++
            if (readCount > limit) error(message)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)
        if (n > 0) {
            readCount += n
            if (readCount > limit) error(message)
        }
        return n
    }
}

internal fun ByteArray.sha256Hex(): String = Hex.encode(Crypto.sha256(this))

internal fun String.asciiLower(): String = lowercase(Locale.ROOT)
