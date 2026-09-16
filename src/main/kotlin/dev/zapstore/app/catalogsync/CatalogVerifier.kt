package dev.zapstore.app.catalogsync

import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import java.io.File
import java.security.MessageDigest

class CatalogVerificationException(message: String) : Exception(message)

object CatalogVerifier {
    fun verifyEnvelope(
        envelope: DecodedEnvelope,
        trustedPubkey: String,
        expectedKind: String? = null,
        expectedOldEpoch: Long? = null,
        schemaVersion: Int = CatalogSchema.VERSION,
        searchModel: String = CatalogSchema.SEARCH_MODEL,
        catalog: String = CatalogSchema.CATALOG,
    ): CatalogManifest {
        val manifest = verifyManifest(
            envelope.manifest,
            trustedPubkey = trustedPubkey,
            expectedKind = expectedKind,
            expectedOldEpoch = expectedOldEpoch,
            schemaVersion = schemaVersion,
            searchModel = searchModel,
            catalog = catalog,
        )
        verifyPayloadHash(manifest, envelope.payload)
        return manifest
    }

    fun verifyManifest(
        manifest: CatalogManifest,
        trustedPubkey: String,
        expectedKind: String? = null,
        expectedOldEpoch: Long? = null,
        schemaVersion: Int = CatalogSchema.VERSION,
        searchModel: String = CatalogSchema.SEARCH_MODEL,
        catalog: String = CatalogSchema.CATALOG,
    ): CatalogManifest {
        if (manifest.event.kind != CatalogSchema.MANIFEST_KIND) {
            throw CatalogVerificationException("manifest kind is not a catalog manifest")
        }
        if (manifest.event.pubKey != trustedPubkey) {
            throw CatalogVerificationException("manifest signer is not the configured catalog key")
        }
        if (!manifest.event.verifyId() || !manifest.event.verifySignature()) {
            throw CatalogVerificationException("manifest signature is invalid")
        }
        if (manifest.catalog != catalog) {
            throw CatalogVerificationException("manifest catalog does not match")
        }
        if (manifest.schemaVersion != schemaVersion) {
            throw CatalogVerificationException("manifest schema is incompatible")
        }
        if (manifest.searchModel != searchModel) {
            throw CatalogVerificationException("manifest search model is incompatible")
        }
        if (expectedKind != null && manifest.kind != expectedKind) {
            throw CatalogVerificationException("manifest kind is ${manifest.kind}, expected $expectedKind")
        }
        if (expectedOldEpoch != null && manifest.oldEpoch != expectedOldEpoch) {
            throw CatalogVerificationException("manifest epoch does not continue the local catalog")
        }
        return manifest
    }

    fun verifyPayloadHash(manifest: CatalogManifest, payload: ByteArray) {
        if (sha256Hex(payload) != manifest.contentHash) {
            throw CatalogVerificationException("payload hash does not match the manifest")
        }
    }

    fun verifyPayloadHash(manifest: CatalogManifest, payload: File) {
        if (sha256Hex(payload) != manifest.contentHash) {
            throw CatalogVerificationException("payload hash does not match the manifest")
        }
    }

    fun verifyEvent(event: com.vitorpamplona.quartz.nip01Core.core.Event) {
        if (!event.verifyId() || !event.verifySignature()) {
            throw CatalogVerificationException("catalog event ${event.id} failed verification")
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }
}
