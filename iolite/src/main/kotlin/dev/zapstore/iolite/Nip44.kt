package dev.zapstore.iolite

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Nip44 {
    private const val VERSION: Byte = 0x02
    private val salt = "nip44-v2".toByteArray(Charsets.UTF_8)

    fun conversationKey(secretKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(secretKey.size == 32) { "secret key must be 32 bytes" }
        require(publicKey.size == 32) { "public key must be 32 bytes" }
        return hkdfExtract(salt, Crypto.sharedX(secretKey, publicKey))
    }

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray = Crypto.randomBytes(32)): String {
        require(conversationKey.size == 32) { "invalid conversation_key length" }
        require(nonce.size == 32) { "invalid nonce length" }
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val ciphertext = ChaCha20.xor(chachaKey, chachaNonce, pad(plaintext))
        val mac = hmacAad(hmacKey, ciphertext, nonce)
        val payload = ByteArray(1 + nonce.size + ciphertext.size + mac.size)
        payload[0] = VERSION
        nonce.copyInto(payload, 1)
        ciphertext.copyInto(payload, 33)
        mac.copyInto(payload, 33 + ciphertext.size)
        return Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        require(conversationKey.size == 32) { "invalid conversation_key length" }
        if (payload.isEmpty() || payload[0] == '#') throw IllegalArgumentException("unknown version")
        if (payload.length < 132) throw IllegalArgumentException("invalid payload size")
        val data = try {
            Base64.getDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("invalid payload")
        }
        if (data.size < 99) throw IllegalArgumentException("invalid data size")
        if (data[0] != VERSION) throw IllegalArgumentException("unknown version ${data[0].toInt() and 0xff}")
        val nonce = data.copyOfRange(1, 33)
        val ciphertext = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val expected = hmacAad(hmacKey, ciphertext, nonce)
        if (!MessageDigest.isEqual(expected, mac)) throw IllegalArgumentException("invalid MAC")
        return unpad(ChaCha20.xor(chachaKey, chachaNonce, ciphertext))
    }

    internal fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        require(conversationKey.size == 32) { "invalid conversation_key length" }
        require(nonce.size == 32) { "invalid nonce length" }
        val keys = hkdfExpand(conversationKey, nonce, 76)
        return Triple(keys.copyOfRange(0, 32), keys.copyOfRange(32, 44), keys.copyOfRange(44, 76))
    }

    internal fun paddedLength(unpadded: Int): Int {
        require(unpadded in 1..Int.MAX_VALUE)
        if (unpadded <= 32) return 32
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(unpadded - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * (((unpadded - 1) / chunk) + 1)
    }

    private fun pad(plaintext: String): ByteArray {
        val unpadded = plaintext.toByteArray(Charsets.UTF_8)
        if (unpadded.isEmpty()) throw IllegalArgumentException("invalid plaintext length")
        val prefix = if (unpadded.size >= 65_536) {
            ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).putShort(0).putInt(unpadded.size).array()
        } else {
            ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(unpadded.size.toShort()).array()
        }
        val paddedLen = paddedLength(unpadded.size)
        val out = ByteArray(prefix.size + paddedLen)
        prefix.copyInto(out)
        unpadded.copyInto(out, prefix.size)
        return out
    }

    private fun unpad(padded: ByteArray): String {
        if (padded.size < 2) throw IllegalArgumentException("invalid padding")
        val firstTwo = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        val (unpaddedLen, prefixLen) = if (firstTwo == 0) {
            if (padded.size < 6) throw IllegalArgumentException("invalid padding")
            val len = ByteBuffer.wrap(padded, 2, 4).order(ByteOrder.BIG_ENDIAN).int
            if (len < 65_536) throw IllegalArgumentException("invalid padding")
            len to 6
        } else {
            firstTwo to 2
        }
        if (unpaddedLen <= 0 || padded.size != prefixLen + paddedLength(unpaddedLen)) {
            throw IllegalArgumentException("invalid padding")
        }
        val unpadded = padded.copyOfRange(prefixLen, prefixLen + unpaddedLen)
        if (unpadded.size != unpaddedLen) throw IllegalArgumentException("invalid padding")
        return String(unpadded, Charsets.UTF_8)
    }

    private fun hmacAad(key: ByteArray, message: ByteArray, aad: ByteArray): ByteArray {
        require(aad.size == 32) { "AAD associated data must be 32 bytes" }
        return hmac(key, aad + message)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hashLen = 32
        val n = (length + hashLen - 1) / hashLen
        val out = ByteArray(n * hashLen)
        var previous = ByteArray(0)
        var offset = 0
        for (i in 1..n) {
            previous = hmac(prk, previous + info + byteArrayOf(i.toByte()))
            previous.copyInto(out, offset)
            offset += hashLen
        }
        return out.copyOf(length)
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }
}
