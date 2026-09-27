package dev.zapstore.iolite

object Hex {
    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append("0123456789abcdef"[value ushr 4])
            append("0123456789abcdef"[value and 0x0f])
        }
    }

    fun decode(value: String): ByteArray {
        require(value.length % 2 == 0) { "hex length must be even" }
        require(value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "invalid hex" }
        return ByteArray(value.length / 2) { index ->
            ((digit(value[index * 2]) shl 4) or digit(value[index * 2 + 1])).toByte()
        }
    }

    fun isHex(value: String, length: Int): Boolean =
        value.length == length && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun digit(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> error("invalid hex")
    }
}

fun ByteArray.toHex(): String = Hex.encode(this)

fun String.decodeHex(): ByteArray = Hex.decode(this)
