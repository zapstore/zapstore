package dev.zapstore.app.install

import android.content.Context
import dev.zapstore.app.R
import dev.zapstore.app.apk.ApkVerificationException
import dev.zapstore.app.apk.ProofVerifier
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CertificateProof

class BlockedInstall(val reason: BlockReason) : Exception()

/** An expired proof is inactive, not a failure. A revoked one no longer names the owner, so it fails. */
internal fun proofHolds(
    app: AppRecord,
    proof: CertificateProof?,
    certificateDer: Map<String, ByteArray>,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
): Boolean {
    val owner = app.proofPubkey ?: return true
    if (proof == null || !proof.pubkey.equals(owner, ignoreCase = true)) return false
    if (proof.revoked) return false
    if (nowSeconds >= proof.expiry) return true
    val der = certificateDer[proof.certificateHash.lowercase()] ?: return false
    return ProofVerifier.verify(proof, der)
}

val BlockReason.message: Int
    get() = when (this) {
        BlockReason.Platform -> R.string.install_blocked_platform
        BlockReason.Signer -> R.string.install_blocked_signer
        BlockReason.Downgrade -> R.string.install_downgrade
        BlockReason.MissingFile -> R.string.install_missing
        BlockReason.Banned -> R.string.install_banned
        BlockReason.NotListed -> R.string.install_not_listed
        BlockReason.Proof -> R.string.install_proof
    }

fun Context.installFailure(error: Throwable): String = when (error) {
    is BlockedInstall -> getString(error.reason.message)
    is InstallException -> when (error.kind) {
        InstallException.Kind.Space -> getString(R.string.install_no_space)
        InstallException.Kind.Hash -> getString(R.string.install_hash)
        InstallException.Kind.Permission -> getString(R.string.install_permission)
        else -> error.message ?: getString(R.string.install_failed)
    }
    is ApkVerificationException -> error.message ?: getString(R.string.install_failed)
    else -> error.message ?: getString(R.string.install_failed)
}
