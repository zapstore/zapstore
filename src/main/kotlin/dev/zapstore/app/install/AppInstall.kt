package dev.zapstore.app.install

import android.content.Context
import android.os.Build
import dev.zapstore.app.R
import dev.zapstore.app.apk.ApkVerificationException
import dev.zapstore.app.apk.ApkVerifier
import dev.zapstore.iolite.AppRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import kotlin.time.Duration.Companion.minutes

class BlockedInstall(val reason: BlockReason) : Exception()

/** One install session at a time, from download through the status callback. */
object InstallTurn {
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Downloads, checks the listing, and commits a session. The cached APK stays until the
 * status callback deletes it. Throws [BlockedInstall] or [InstallException] when the
 * file must not be installed.
 */
suspend fun stageAndCommit(
    context: Context,
    calls: Call.Factory,
    app: AppRecord,
    origin: InstallOrigin,
    ourPackage: String,
    bulk: Boolean,
    onProgress: (Long, Long?) -> Unit,
) {
    val hash = app.apkHash ?: throw BlockedInstall(BlockReason.MissingFile)
    if (app.certificateHash.isNullOrBlank()) throw BlockedInstall(BlockReason.MissingFile)
    val dest = InstallCache.file(context, app.appId)
    try {
        withContext(Dispatchers.IO) {
            var reported = 0L
            ApkDownloader.download(
                calls = calls,
                urls = ApkDownloader.urls(app.downloadUrl, hash),
                dest = dest,
                expectedHash = hash,
            ) { read, total ->
                if (read - reported < PROGRESS_STEP && read != total) return@download
                reported = read
                onProgress(read, total)
            }
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
            SessionInstaller.commit(context, dest, app.appId, app.name, plan, bulk)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        dest.delete()
        throw e
    } catch (e: Exception) {
        dest.delete()
        throw e
    }
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

fun Context.installFailure(error: Throwable): String = when (error) {
    is BlockedInstall -> getString(
        when (error.reason) {
            BlockReason.Platform -> R.string.install_blocked_platform
            BlockReason.Signer -> R.string.install_blocked_signer
            BlockReason.Downgrade -> R.string.install_downgrade
            BlockReason.MissingFile -> R.string.install_missing
            BlockReason.Banned -> R.string.install_banned
            BlockReason.NotListed -> R.string.install_not_listed
        },
    )
    is InstallException -> when (error.kind) {
        InstallException.Kind.Space -> getString(R.string.install_no_space)
        InstallException.Kind.Hash -> getString(R.string.install_hash)
        InstallException.Kind.Permission -> getString(R.string.install_permission)
        else -> error.message ?: getString(R.string.install_failed)
    }
    is ApkVerificationException -> error.message ?: getString(R.string.install_failed)
    else -> error.message ?: getString(R.string.install_failed)
}

private const val PROGRESS_STEP = 256L * 1024
private val INSTALL_WAIT = 65.minutes
