package dev.zapstore.iolite

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublisherLookupTest {
    @Test
    fun showsTheC1OwnerWhenAProofExists() {
        val owner = "a".repeat(64)
        val catalog = "c".repeat(64)
        assertEquals(owner, app(proof = owner, signer = catalog, catalog = catalog).authorPubkey)
        assertEquals(owner, app(proof = owner, signer = "d".repeat(64), catalog = catalog).authorPubkey)
    }

    @Test
    fun showsNoAuthorWithoutAProof() {
        assertNull(app(proof = null, signer = "d".repeat(64), catalog = "c".repeat(64)).authorPubkey)
    }

    @Test
    fun hidesTheCatalogKey() {
        val catalog = "c".repeat(64)
        assertNull(app(proof = null, signer = catalog, catalog = catalog).authorPubkey)
        assertNull(app(proof = catalog, signer = "d".repeat(64), catalog = catalog).authorPubkey)
    }

    private fun app(proof: String?, signer: String, catalog: String) = AppRecord(
        id = ByteArray(32),
        catalogId = 1,
        appId = "com.example.app",
        certificateHash = "ab".repeat(32),
        createdAt = 0,
        proofPubkey = proof,
        eventPubkey = signer,
        catalogManifestPubkey = catalog,
        name = "Example",
        summary = "",
        repository = null,
        version = "1",
        versionCode = 1,
        channel = "main",
        metadata = JSONObject(),
    )
}
