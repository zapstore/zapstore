package dev.zapstore.iolite

import fr.acinq.secp256k1.Secp256k1

object Crypto {
    private val secp: Secp256k1 = Secp256k1.get()

    fun xOnlyPubkey(secretKey: ByteArray): ByteArray {
        require(secretKey.size == 32) { "secret key must be 32 bytes" }
        val uncompressed = secp.pubkeyCreate(secretKey)
        val compressed = secp.pubKeyCompress(uncompressed)
        return compressed.copyOfRange(1, 33)
    }

    fun signSchnorr(message: ByteArray, secretKey: ByteArray, auxRand: ByteArray? = ByteArray(32)): ByteArray {
        require(message.size == 32) { "message must be 32 bytes" }
        require(secretKey.size == 32) { "secret key must be 32 bytes" }
        return secp.signSchnorr(message, secretKey, auxRand)
    }

    fun verifySchnorr(signature: ByteArray, message: ByteArray, xOnlyPubkey: ByteArray): Boolean {
        if (signature.size != 64 || message.size != 32 || xOnlyPubkey.size != 32) return false
        return runCatching { secp.verifySchnorr(signature, message, xOnlyPubkey) }.getOrDefault(false)
    }

    fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { java.security.SecureRandom().nextBytes(it) }

    fun sharedX(secretKey: ByteArray, xOnlyPubkey: ByteArray): ByteArray {
        require(secretKey.size == 32) { "secret key must be 32 bytes" }
        require(xOnlyPubkey.size == 32) { "public key must be 32 bytes" }
        val compressed = ByteArray(33)
        xOnlyPubkey.copyInto(compressed, 1)
        val uncompressed = runCatching {
            compressed[0] = 0x02
            secp.pubkeyParse(compressed)
        }.recoverCatching {
            compressed[0] = 0x03
            secp.pubkeyParse(compressed)
        }.getOrElse { throw IllegalArgumentException("invalid public key") }
        return secp.pubKeyTweakMul(uncompressed, secretKey).copyOfRange(1, 33)
    }
}
