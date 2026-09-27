package dev.zapstore.iolite

internal object ChaCha20 {
    fun xor(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }
        val out = data.copyOf()
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = key.leInt(i * 4)
        for (i in 0 until 3) state[13 + i] = nonce.leInt(i * 4)
        val block = ByteArray(64)
        var offset = 0
        var counter = 0
        while (offset < out.size) {
            state[12] = counter
            block(state, block)
            counter += 1
            val n = minOf(64, out.size - offset)
            for (i in 0 until n) {
                out[offset + i] = (out[offset + i].toInt() xor (block[i].toInt() and 0xff)).toByte()
            }
            offset += n
        }
        return out
    }

    private fun block(input: IntArray, out: ByteArray) {
        val x = input.copyOf()
        repeat(10) {
            quarter(x, 0, 4, 8, 12)
            quarter(x, 1, 5, 9, 13)
            quarter(x, 2, 6, 10, 14)
            quarter(x, 3, 7, 11, 15)
            quarter(x, 0, 5, 10, 15)
            quarter(x, 1, 6, 11, 12)
            quarter(x, 2, 7, 8, 13)
            quarter(x, 3, 4, 9, 14)
        }
        for (i in x.indices) x[i] += input[i]
        for (i in x.indices) {
            val word = x[i]
            val o = i * 4
            out[o] = word.toByte()
            out[o + 1] = (word ushr 8).toByte()
            out[o + 2] = (word ushr 16).toByte()
            out[o + 3] = (word ushr 24).toByte()
        }
    }

    private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    private fun ByteArray.leInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
}
