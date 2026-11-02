package dev.zapstore.app.screens

import dev.zapstore.app.AppConfig
import dev.zapstore.iolite.AppRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SocialReachTest {
    @Test
    fun aClaimedAppReadsTheCatalogTheSignerAndTheOldKey() {
        val reach = SocialReach.of(app(proof = OWNER, signer = OWNER))
        assertEquals(listOf(CATALOG, OWNER, AppConfig.LEGACY_CATALOG_PUBKEY), reach.anchors.map { it.pubkey })
        assertEquals(listOf(CATALOG, OWNER, AppConfig.LEGACY_CATALOG_PUBKEY), reach.recipients)
        assertEquals(listOf(CATALOG, OWNER), reach.lists)
    }

    @Test
    fun theC1OwnerNotTheDelegateIsWhoseInboxIsRead() {
        val reach = SocialReach.of(app(proof = OWNER, signer = DELEGATE))
        assertEquals(listOf(CATALOG, OWNER), reach.lists)
        assertEquals(listOf(CATALOG, DELEGATE, OWNER, AppConfig.LEGACY_CATALOG_PUBKEY), reach.recipients)
    }

    @Test
    fun theC1OwnerIsAKnownPubkeyEvenWhenADelegateSignedTheListing() {
        val reach = SocialReach.of(app(proof = OWNER, signer = DELEGATE))
        assertEquals(listOf(CATALOG, DELEGATE, OWNER, AppConfig.LEGACY_CATALOG_PUBKEY), reach.anchors.map { it.pubkey })
    }

    @Test
    fun withoutAProofTheListingSignerIsRead() {
        val reach = SocialReach.of(app(proof = null, signer = DELEGATE))
        assertEquals(listOf(CATALOG, DELEGATE), reach.lists)
        assertEquals(listOf(CATALOG, DELEGATE, AppConfig.LEGACY_CATALOG_PUBKEY), reach.recipients)
    }

    @Test
    fun anIndexedAppIsOnlyTheCatalog() {
        val reach = SocialReach.of(app(proof = null, signer = CATALOG))
        assertEquals(listOf(CATALOG), reach.lists)
        assertEquals(listOf(CATALOG, AppConfig.LEGACY_CATALOG_PUBKEY), reach.anchors.map { it.pubkey })
    }

    private fun app(proof: String?, signer: String) = AppRecord(
        id = ByteArray(32),
        catalogId = 1,
        appId = "com.example.app",
        certificateHash = null,
        createdAt = 0,
        proofPubkey = proof,
        eventPubkey = signer,
        catalogManifestPubkey = CATALOG,
        name = "Example",
        summary = "",
        repository = null,
        version = "1",
        versionCode = 1,
        channel = "main",
        metadata = JSONObject(),
    )

    private companion object {
        val CATALOG = "c".repeat(64)
        val OWNER = "a".repeat(64)
        val DELEGATE = "d".repeat(64)
    }
}
