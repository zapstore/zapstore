package dev.zapstore.app.apk

import dev.zapstore.iolite.CertificateProof
import java.io.ByteArrayInputStream
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64

/** NIP-C1 certificate-key check of a proof against the certificate that signed the APK. */
object ProofVerifier {
    /** True when [certificateDer] hashes to the proof's `d` and its key signed the proof message. */
    fun verify(proof: CertificateProof, certificateDer: ByteArray): Boolean {
        if (!certificateDer.sha256Hex().equals(proof.certificateHash, ignoreCase = true)) return false
        val key = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificateDer))
                .publicKey
        }.getOrNull() ?: return false
        return verify(proof, key)
    }

    fun verify(proof: CertificateProof, key: PublicKey): Boolean {
        val algorithm = when (key.algorithm) {
            "RSA" -> "SHA256withRSA"
            "EC" -> "SHA256withECDSA"
            else -> return false
        }
        val signature = runCatching { Base64.getDecoder().decode(proof.signature) }.getOrNull() ?: return false
        if (signature.isEmpty()) return false
        return runCatching {
            Signature.getInstance(algorithm).run {
                initVerify(key)
                update(proof.message().toByteArray(Charsets.UTF_8))
                verify(signature)
            }
        }.getOrDefault(false)
    }
}
