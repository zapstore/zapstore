package dev.zapstore.app.apk

import dev.zapstore.app.install.proofHolds
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CertificateProof
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProofVerifierTest {
    private val der = Base64.getDecoder().decode(CERT)
    private val proof = CertificateProof(
        pubkey = OWNER,
        certificateHash = HASH,
        createdAt = 1772114325,
        expiry = 1803650325,
        revoked = false,
        signature = SIG,
    )

    @Test
    fun ecdsaProofVerifiesAgainstItsCertificate() {
        assertTrue(ProofVerifier.verify(proof, der))
    }

    @Test
    fun anyChangeToTheSignedFieldsFails() {
        assertFalse(ProofVerifier.verify(proof.copy(pubkey = "b".repeat(64)), der))
        assertFalse(ProofVerifier.verify(proof.copy(createdAt = proof.createdAt + 1), der))
        assertFalse(ProofVerifier.verify(proof.copy(expiry = proof.expiry + 1), der))
        assertFalse(ProofVerifier.verify(proof.copy(signature = ""), der))
        assertFalse(ProofVerifier.verify(proof.copy(signature = "not base64!"), der))
    }

    @Test
    fun certificateMustHashToD() {
        assertFalse(ProofVerifier.verify(proof.copy(certificateHash = "00".repeat(32)), der))
    }

    @Test
    fun rsaUsesPkcs1WithSha256() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(proof.message().toByteArray())
            sign()
        }
        val rsa = proof.copy(signature = Base64.getEncoder().encodeToString(signature))
        assertTrue(ProofVerifier.verify(rsa, keys.public))
        assertFalse(ProofVerifier.verify(rsa.copy(expiry = 1), keys.public))
    }

    @Test
    fun installNeedsAVerifiedActiveProofWhenTheListingHasAnOwner() {
        val now = proof.createdAt + 10
        val certs = mapOf(HASH to der)
        assertTrue(proofHolds(app(proofPubkey = null), null, emptyMap(), now))
        assertTrue(proofHolds(app(OWNER), proof, certs, now))
        assertFalse(proofHolds(app(OWNER), null, certs, now))
        assertFalse(proofHolds(app(OWNER), proof, emptyMap(), now))
        assertFalse(proofHolds(app(OWNER), proof.copy(signature = SIG.reversed()), certs, now))
        assertFalse(proofHolds(app(OWNER), proof.copy(revoked = true), certs, now))
        assertFalse(proofHolds(app("b".repeat(64)), proof, certs, now))
    }

    @Test
    fun anExpiredProofIsInactiveNotAFailure() {
        assertTrue(proofHolds(app(OWNER), proof.copy(signature = ""), emptyMap(), proof.expiry))
    }

    private fun app(proofPubkey: String?) = AppRecord(
        id = ByteArray(32),
        catalogId = 1,
        appId = "com.example.app",
        certificateHash = HASH,
        createdAt = 0,
        proofPubkey = proofPubkey,
        eventPubkey = "d".repeat(64),
        catalogManifestPubkey = "c".repeat(64),
        name = "Example",
        summary = "",
        repository = null,
        version = "1",
        versionCode = 1,
        channel = "main",
        metadata = JSONObject(),
    )

    private companion object {
        const val OWNER = "726a1e261cc6474674e8285e3951b3bb139be9a773d1acf49dc868db861a1c11"
        const val HASH = "5018614bc047b29f79597b29e1115e5690ea5b8dc3555413ae3105531b0c7d86"
        const val SIG = "MEUCIQC8W3P2hegUq3IjwyNNqHee68RAqOo7a/djWASLxhArQQIgEKxPAQ0bCvBXjgTtScnDzv1ONIhZT3nWSiGS8M9u6yA="
        const val CERT = "MIIBVjCB/KADAgECAhRJMcln1wDOfFHAiXWoFbU0DR/VDTAKBggqhkjOPQQDAjARMQ8wDQYDVQQDDAZjMXRlc3QwHhcNMjYxMDE3MjAyODQ2WhcNMzYxMDE0MjAyODQ2WjARMQ8wDQYDVQQDDAZjMXRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAS8nffKka10pqf1GJmqd1B2qi0imaDd9IKfz21JYbMyiOW2PhN/MIRfCSXsazRcXHI8UuEuTfES1CEVV/2uTSvGozIwMDAdBgNVHQ4EFgQUn98x28y9l38NrDsRDkrr3amfuFwwDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNJADBGAiEArxVNLYszklvQ1bDT37GM9JEgRlPK10TEblnNfa9buiACIQDm/VLvs1bCDQ6ZMwCKyanTA4PDSvJcXzImHN/Pfoz/hw=="
    }
}
