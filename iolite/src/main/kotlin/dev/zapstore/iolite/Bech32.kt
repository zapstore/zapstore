package dev.zapstore.iolite

private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private val BECH32_GENERATOR = longArrayOf(0x3b6a57b2L, 0x26508e6dL, 0x1ea119faL, 0x3d4233ddL, 0x2a1462b3L)

/** NIP-19 `npub` for a 64-char hex pubkey; other strings are returned unchanged. */
fun String.toNpub(): String {
    if (!Hex.isHex(lowercase(), 64)) return this
    val data = convertBits(Hex.decode(this).map { it.toInt() and 0xff }, 8, 5)
    val checksum = bech32Checksum("npub", data)
    return buildString {
        append("npub1")
        (data + checksum).forEach { append(BECH32_CHARSET[it]) }
    }
}

private fun convertBits(data: List<Int>, fromBits: Int, toBits: Int): List<Int> {
    var accumulator = 0
    var bits = 0
    val result = mutableListOf<Int>()
    val maxValue = (1 shl toBits) - 1
    data.forEach { value ->
        accumulator = (accumulator shl fromBits) or value
        bits += fromBits
        while (bits >= toBits) {
            bits -= toBits
            result += (accumulator shr bits) and maxValue
        }
    }
    if (bits > 0) result += (accumulator shl (toBits - bits)) and maxValue
    return result
}

/** Hex pubkey for an `npub`, or null when the string is not a valid NIP-19 public key. */
fun String.decodeNpub(): String? {
    val value = lowercase()
    if (!value.startsWith("npub1")) return null
    val dataChars = value.substring(5)
    if (dataChars.length < 6) return null
    val data = dataChars.map { char ->
        val index = BECH32_CHARSET.indexOf(char)
        if (index < 0) return null
        index
    }
    if (polymod(hrpExpand("npub") + data) != 1L) return null
    val bytes = convertBitsDown(data.dropLast(6)) ?: return null
    if (bytes.size != 32) return null
    return Hex.encode(bytes)
}

private fun hrpExpand(hrp: String): List<Int> =
    hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

private fun polymod(values: List<Int>): Long {
    var checksum = 1L
    values.forEach { value ->
        val top = checksum shr 25
        checksum = ((checksum and 0x1ffffffL) shl 5) xor value.toLong()
        BECH32_GENERATOR.forEachIndexed { index, generator ->
            if (((top shr index) and 1L) != 0L) checksum = checksum xor generator
        }
    }
    return checksum
}

private fun bech32Checksum(hrp: String, data: List<Int>): List<Int> {
    val polymod = polymod(hrpExpand(hrp) + data + List(6) { 0 }) xor 1L
    return (0 until 6).map { shift -> ((polymod shr (5 * (5 - shift))) and 31).toInt() }
}

/** 5-bit bech32 payload to bytes. Leftover padding must be zero. */
private fun convertBitsDown(data: List<Int>): ByteArray? {
    var accumulator = 0
    var bits = 0
    val out = ArrayList<Byte>(data.size * 5 / 8)
    val maxValue = (1 shl 8) - 1
    val maxAccumulator = (1 shl (5 + 8 - 1)) - 1
    for (value in data) {
        if (value < 0 || value shr 5 != 0) return null
        accumulator = ((accumulator shl 5) or value) and maxAccumulator
        bits += 5
        while (bits >= 8) {
            bits -= 8
            out += ((accumulator shr bits) and maxValue).toByte()
        }
    }
    if (bits >= 5 || ((accumulator shl (8 - bits)) and maxValue) != 0) return null
    return out.toByteArray()
}
