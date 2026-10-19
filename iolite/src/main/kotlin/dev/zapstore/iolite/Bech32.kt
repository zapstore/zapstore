package dev.zapstore.iolite

private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private val BECH32_GENERATOR = longArrayOf(0x3b6a57b2L, 0x26508e6dL, 0x1ea119faL, 0x3d4233ddL, 0x2a1462b3L)

/** NIP-19 `npub` for a 64-char hex pubkey; other strings are returned unchanged. */
fun String.toNpub(): String = encodeKey("npub", this) ?: this

/** NIP-19 `nsec` for a 64-char hex secret key; other strings are returned unchanged. */
fun String.toNsec(): String = encodeKey("nsec", this) ?: this

private fun encodeKey(hrp: String, hex: String): String? {
    if (!Hex.isHex(hex.lowercase(), 64)) return null
    val data = convertBits(Hex.decode(hex).map { it.toInt() and 0xff }, 8, 5)
    val checksum = bech32Checksum(hrp, data)
    return buildString {
        append(hrp).append('1')
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
fun String.decodeNpub(): String? = decodeKey("npub", this)

/** Hex secret key for an `nsec`, or null when the string is not a valid NIP-19 secret key. */
fun String.decodeNsec(): String? = decodeKey("nsec", this)

private fun decodeKey(hrp: String, input: String): String? =
    decodeBech32(hrp, input)?.takeIf { it.size == 32 }?.let(Hex::encode)

/** A NIP-19 `naddr`: the address of a replaceable event. */
data class Naddr(val kind: Int, val pubkey: String, val identifier: String)

/** Decodes an `naddr`, or null when it is not one or lacks a kind or author. */
fun String.decodeNaddr(): Naddr? {
    val tlv = decodeTlv(decodeBech32("naddr", this) ?: return null) ?: return null
    val identifier = tlv[0]?.firstOrNull()?.toString(Charsets.UTF_8) ?: return null
    val author = tlv[2]?.firstOrNull()?.takeIf { it.size == 32 } ?: return null
    val kind = tlv[3]?.firstOrNull()?.takeIf { it.size == 4 } ?: return null
    val kindValue = kind.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff) }
    return Naddr(kindValue, Hex.encode(author), identifier)
}

/** Hex pubkey of an `nprofile`, or null when it is not one. */
fun String.decodeNprofile(): String? {
    val tlv = decodeTlv(decodeBech32("nprofile", this) ?: return null) ?: return null
    return tlv[0]?.firstOrNull()?.takeIf { it.size == 32 }?.let(Hex::encode)
}

private fun decodeTlv(bytes: ByteArray): Map<Int, List<ByteArray>>? {
    val out = mutableMapOf<Int, MutableList<ByteArray>>()
    var index = 0
    while (index < bytes.size) {
        if (index + 2 > bytes.size) return null
        val type = bytes[index].toInt() and 0xff
        val length = bytes[index + 1].toInt() and 0xff
        if (index + 2 + length > bytes.size) return null
        out.getOrPut(type) { mutableListOf() } += bytes.copyOfRange(index + 2, index + 2 + length)
        index += 2 + length
    }
    return out
}

/** Bech32 payload bytes for [hrp], without the 90-character limit (`naddr` can be longer). */
private fun decodeBech32(hrp: String, input: String): ByteArray? {
    val value = input.trim().lowercase()
    if (!value.startsWith("${hrp}1")) return null
    val dataChars = value.substring(hrp.length + 1)
    if (dataChars.length < 6) return null
    val data = dataChars.map { char ->
        val index = BECH32_CHARSET.indexOf(char)
        if (index < 0) return null
        index
    }
    if (polymod(hrpExpand(hrp) + data) != 1L) return null
    return convertBitsDown(data.dropLast(6))
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
