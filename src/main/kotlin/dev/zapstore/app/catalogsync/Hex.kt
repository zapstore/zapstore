package dev.zapstore.app.catalogsync

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex length must be even" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
