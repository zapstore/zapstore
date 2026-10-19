package dev.zapstore.app.install

import android.content.Context
import android.os.Build
import dev.zapstore.app.R
import dev.zapstore.app.apk.ApkVerificationException
import dev.zapstore.app.apk.ApkVerifier
import dev.zapstore.app.apk.ProofVerifier
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CertificateProof
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes

class BlockedInstall(val reason: BlockReason) : Exception()

/** One install session at a time, from download through the status callback. */
object InstallTurn {
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

enum class Staged { Committed, Ready }

/** What [stageAndCommit] is doing after the download. */
sealed interface StagePhase {
    data object Verifying : StagePhase

    /** [silent] is false when Android will ask the user to confirm. */
    data class Committing(val silent: Boolean) : StagePhase
}

/**
 * Downloads, checks the listing, and commits a session. The cached APK stays until the
 * status callback deletes it. Throws [BlockedInstall] or [InstallException] when the
 * file must not be installed. With [promptAllowed] false, an install that would prompt is
 * kept in [InstallCache.ready] and returns [Staged.Ready]; the next foreground install reuses it.
 * A listing with a C1 owner needs its proof from [proofOf]; an active one must verify against the APK's certificate.
 */
suspend fun stageAndCommit(
    context: Context,
    calls: Call.Factory,
    app: AppRecord,
    origin: InstallOrigin,
    ourPackage: String,
    bulk: Boolean,
    proofOf: (AppRecord) -> CertificateProof?,
    promptAllowed: Boolean = true,
    onPhase: (StagePhase) -> Unit = {},
    onProgress: (Long, Long?) -> Unit,
): Staged {
    val hash = app.apkHash ?: throw BlockedInstall(BlockReason.MissingFile)
    if (app.certificateHash.isNullOrBlank()) throw BlockedInstall(BlockReason.MissingFile)
    val dest = InstallCache.file(context, app.appId)
    try {
        return withContext(Dispatchers.IO) {
            val ready = InstallCache.ready(context, app.appId)
            if (ready.exists() && ready.sha256Hex().equals(hash, ignoreCase = true) && ready.renameTo(dest)) {
                dest.length().let { onProgress(it, it) }
            } else {
                ready.delete()
                var reported = 0L
                val partial = InstallCache.partial(context, hash)
                ApkDownloader.download(
                    calls = calls,
                    urls = ApkDownloader.urls(app.downloadUrl, hash),
                    dest = partial,
                    expectedHash = hash,
                ) { read, total ->
                    if (read - reported < PROGRESS_STEP && read != total) return@download
                    reported = read
                    onProgress(read, total)
                }
                if (!partial.renameTo(dest)) {
                    partial.copyTo(dest, overwrite = true)
                    partial.delete()
                }
            }
            onPhase(StagePhase.Verifying)
            val identity = ApkVerifier.inspect(context, dest)
            val plan = installPlan(
                deviceSdk = Build.VERSION.SDK_INT,
                ourPackage = ourPackage,
                packageId = app.appId,
                origin = origin,
                listedVersionCode = app.versionCode,
                listedHash = hash,
                listedCertificate = app.certificateHash,
                apk = identity,
            )
            if (plan is InstallPlan.Blocked) {
                dest.delete()
                throw BlockedInstall(plan.reason)
            }
            if (!proofHolds(app, proofOf(app), identity.certificateDer)) {
                dest.delete()
                throw BlockedInstall(BlockReason.Proof)
            }
            if (!promptAllowed && plan !is InstallPlan.Silent) {
                if (!dest.renameTo(ready)) dest.delete()
                return@withContext Staged.Ready
            }
            onPhase(StagePhase.Committing(silent = plan is InstallPlan.Silent))
            SessionInstaller.commit(context, dest, app.appId, app.name, plan, bulk)
            Staged.Committed
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        dest.delete()
        throw e
    } catch (e: Exception) {
        dest.delete()
        throw e
    }
}

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

/** Runs [block], then waits for that package's terminal install status. */
suspend fun awaitTerminal(packageId: String, block: suspend () -> Unit): InstallResult {
    val results = Channel<InstallResult>(Channel.BUFFERED)
    val stop = InstallEvents.listen { result ->
        if (result.packageId == packageId && !result.awaitingUser) results.trySend(result)
    }
    return try {
        block()
        withTimeout(INSTALL_WAIT) { results.receive() }
    } catch (e: TimeoutCancellationException) {
        InstallResult(packageId, success = false, message = null)
    } finally {
        stop()
        results.close()
    }
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

private fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val PROGRESS_STEP = 256L * 1024
private val INSTALL_WAIT = 65.minutes
