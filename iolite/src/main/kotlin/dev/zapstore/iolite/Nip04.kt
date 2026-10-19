package dev.zapstore.iolite

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** NIP-04, kept only for wallets that do not speak NIP-44 over NWC. */
object Nip04 {
    fun encrypt(plaintext: String, secretKey: ByteArray, publicKey: ByteArray, iv: ByteArray = Crypto.randomBytes(16)): String {
        val cipher = cipher(Cipher.ENCRYPT_MODE, secretKey, publicKey, iv)
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(body) + "?iv=" + Base64.getEncoder().encodeToString(iv)
    }

    fun decrypt(payload: String, secretKey: ByteArray, publicKey: ByteArray): String {
        val parts = payload.split("?iv=")
        require(parts.size == 2) { "not a NIP-04 payload" }
        val iv = Base64.getDecoder().decode(parts[1])
        val cipher = cipher(Cipher.DECRYPT_MODE, secretKey, publicKey, iv)
        return String(cipher.doFinal(Base64.getDecoder().decode(parts[0])), Charsets.UTF_8)
    }

    private fun cipher(mode: Int, secretKey: ByteArray, publicKey: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(Crypto.sharedX(secretKey, publicKey), "AES"), IvParameterSpec(iv))
        }
}
