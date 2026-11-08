package dev.zapstore.app.install

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.zapstore.app.AppLog
import dev.zapstore.app.R
import dev.zapstore.app.Toast
import dev.zapstore.app.ToastKind
import dev.zapstore.app.Toasts
import dev.zapstore.app.apk.ApkVerifier
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CertificateProof
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call

enum class TaskPhase {
    Queued,
    Downloading,
    Paused,
    Verifying,
    /** The session is committed and Android is installing it. */
    Installing,
    /** Android's dialog is up or queued; [Installer.confirm] shows it again. */
    Confirm,
    /** A verified APK waits for a tap: the dialog was declined, or a background run could not prompt. */
    Ready,
    Failed,
    /**
     * Android installed it. The task stays, spinning, until the device's package list shows the new version
     * (see [Installer.prune]), so a row never falls back to "Update" in the moment between the two.
     */
    Done,
}

/** One app's install, from the tap to Android's answer. A success removes it. */
data class InstallTask(
    val appId: String,
    val hash: String,
    val name: String = appId,
    val phase: TaskPhase,
    val received: Long = 0,
    val total: Long? = null,
    /** Android installs it without a dialog, or its dialog was already accepted. */
    val silent: Boolean = false,
    val error: String? = null,
    /**
     * Failed because the installed copy is signed by another key. The listed APK checked out, but
     * Android only takes it after the old copy is uninstalled, so Retry cannot help.
     */
    val needsReinstall: Boolean = false,
) {
    /** Zapstore is working on it or Android is waiting on the user; a new tap must not start another. */
    val busy: Boolean get() = phase in BUSY

    /** Holds the download service up. */
    val transferring: Boolean get() = phase in TRANSFERRING

    /** There is something to stop or drop: work in progress, a paused download, or a verified file waiting for a tap. */
    val cancellable: Boolean get() = busy || phase == TaskPhase.Paused || phase == TaskPhase.Ready
}

private val BUSY = setOf(TaskPhase.Queued, TaskPhase.Downloading, TaskPhase.Verifying, TaskPhase.Installing, TaskPhase.Confirm)
private val TRANSFERRING = setOf(TaskPhase.Queued, TaskPhase.Downloading, TaskPhase.Verifying)

/**
 * Every install and update goes through here, so the app page, My apps and the background run
 * see the same state. Downloads run [MAX_DOWNLOADS] at a time on the process scope and outlive
 * the screen that started them. Sessions commit as soon as their APK verifies; Android's
 * dialogs are queued and shown one at a time while Zapstore is on screen, and a single
 * notification stands in for them while it is not.
 */
class Installer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val calls: () -> Call.Factory,
    private val proofOf: (AppRecord) -> CertificateProof?,
) {
    private val _tasks = MutableStateFlow<Map<String, InstallTask>>(emptyMap())
    val tasks: StateFlow<Map<String, InstallTask>> = _tasks.asStateFlow()

    /** A download is running, so the network must stay up even with Zapstore off screen. */
    val transferring: Boolean get() = _tasks.value.values.any { it.transferring }

    private val jobs = HashMap<String, Job>()
    private val pausing = HashSet<String>()
    private val backgroundErrors = java.util.concurrent.ConcurrentHashMap<String, Throwable>()
    private val downloads = Semaphore(MAX_DOWNLOADS)
    private val main = Handler(Looper.getMainLooper())

    /** Main thread only: dialogs Android handed us, oldest first, and the one on screen. */
    private val confirmations = LinkedHashMap<String, Intent>()
    private var presenting: String? = null
    /** Main thread only: the dialog that was up when Zapstore last paused. */
    private var covered: String? = null

    @Volatile
    private var visible = false

    init {
        scope.launch(Dispatchers.IO) { restore() }
    }

    /**
     * After a restart: drop stale files, abandon sessions whose dialog died with the process, keep the
     * silent ones Android is still installing, and show each verified APK left on disk as [TaskPhase.Ready].
     */
    private fun restore() {
        InstallCache.sweep(context)
        val sessions = context.packageManager.packageInstaller
        val installing = HashSet<String>()
        sessions.mySessions.forEach { session ->
            val id = session.appPackageName
            val silent = Build.VERSION.SDK_INT >= 31 && session.isCommitted &&
                session.requireUserAction == android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
            if (silent && id != null) installing += id else runCatching { sessions.abandonSession(session.sessionId) }
        }
        val found = InstallCache.artifacts(context)
        _tasks.update { tasks ->
            val restored = found.filter { it.appId !in tasks }.associate { artifact ->
                val phase = when {
                    artifact.appId in installing -> TaskPhase.Installing
                    artifact.verified -> TaskPhase.Ready
                    else -> TaskPhase.Paused
                }
                val silent = artifact.appId in installing
                artifact.appId to InstallTask(artifact.appId, artifact.hash, phase = phase, received = artifact.bytes, silent = silent)
            }
            tasks + restored
        }
    }

    /**
     * Drops every file and idle task the catalog no longer asks for: the app left the catalog, a newer
     * build replaced the one on disk, or the installed version caught up (another store, or by hand).
     */
    fun prune(listings: List<AppRecord>, installed: List<dev.zapstore.app.catalog.InstalledApp>) {
        val byId = listings.associateBy { it.appId }
        val versions = installed.associate { it.packageId to it.versionCode }
        val stale = synchronized(jobs) {
            _tasks.value.values.filter { task ->
                if (task.busy || task.phase == TaskPhase.Failed) return@filter false
                val app = byId[task.appId] ?: return@filter true
                !app.apkHash.equals(task.hash, ignoreCase = true) || (versions[task.appId] ?: -1L) >= app.versionCode
            }.map { it.appId }
        }
        if (stale.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            stale.forEach { InstallCache.delete(context, it) }
            _tasks.update { tasks -> tasks.filterKeys { it !in stale || tasks[it]?.busy == true } }
        }
    }

    /** Starts, resumes or retries [app]. A pending dialog for it is shown again instead. */
    fun install(app: AppRecord, bulk: Boolean = false) {
        val current = _tasks.value[app.appId]
        if (current?.phase == TaskPhase.Confirm) {
            confirm(app.appId)
            return
        }
        if (!claim(app, bulk, background = false)) return
        if (visible) runCatching { DownloadService.start(context) }.onFailure { AppLog.w(TAG, "download service", it) }
    }

    /** Stops the download and keeps what arrived. */
    fun pause(appId: String) {
        val job = synchronized(jobs) {
            if (_tasks.value[appId]?.phase !in setOf(TaskPhase.Queued, TaskPhase.Downloading)) return
            pausing += appId
            jobs[appId]
        }
        job?.cancel()
    }

    /** Clears a failure once it was shown, with whatever it left on disk. */
    fun dismiss(appId: String) {
        val failed = _tasks.value[appId]?.phase == TaskPhase.Failed
        _tasks.update { tasks -> if (tasks[appId]?.phase == TaskPhase.Failed) tasks - appId else tasks }
        if (failed) scope.launch(Dispatchers.IO) { InstallCache.delete(context, appId) }
    }

    /**
     * Ends [appId]'s install at any stage: the download stops, Android's session is abandoned, the
     * file is deleted and the task is gone at once. Cleanup never waits on the download job.
     */
    fun cancel(appId: String) {
        val job = synchronized(jobs) {
            pausing -= appId
            jobs[appId]
        }
        job?.cancel()
        _tasks.update { it - appId }
        main.post {
            confirmations.remove(appId)
            if (presenting == appId) presenting = null
            if (covered == appId) covered = null
            if (visible) presentNext() else if (confirmations.isEmpty()) ConfirmNotifier.clear(context)
        }
        scope.launch(Dispatchers.IO) {
            if (job != null) withTimeoutOrNull(CANCEL_JOIN_MS) { job.join() }
            // A new install of the same app may have started meanwhile; its files are not ours to delete.
            if (_tasks.value[appId] == null) {
                SessionInstaller.abandon(context, appId)
                InstallCache.delete(context, appId)
            }
        }
    }

    /**
     * Stages one update for the background run: a silent one commits, one that needs a dialog stays
     * [TaskPhase.Ready]. Null when the app was already in progress.
     */
    suspend fun runInBackground(app: AppRecord): Result<TaskPhase?>? {
        val job = synchronized(jobs) {
            if (!claim(app, bulk = true, background = true)) return null
            jobs[app.appId]
        }
        job?.join()
        backgroundErrors.remove(app.appId)?.let { return Result.failure(it) }
        return Result.success(_tasks.value[app.appId]?.phase)
    }

    /** Shows Android's dialog for [appId] now, ahead of the queue. */
    fun confirm(appId: String) {
        main.post { if (appId in confirmations) present(appId) }
    }

    /** MainActivity's start and stop. Dialogs wait for the screen; a notification covers the gap. */
    fun setVisible(on: Boolean) {
        main.post {
            visible = on
            if (on) {
                ConfirmNotifier.clear(context)
                presentNext()
            } else {
                ConfirmNotifier.post(context, confirmations.keys.map(::nameOf))
            }
        }
    }

    /**
     * MainActivity's resume and pause. Android's dialog only pauses Zapstore, so a resume after a pause
     * while it was up means the dialog closed: show the update as installing instead of Confirm while
     * Android works. A decline answers within moments; no answer at all puts Confirm back.
     */
    fun setResumed(on: Boolean) {
        main.post {
            if (!on) {
                covered = presenting
                return@post
            }
            val id = covered ?: return@post
            covered = null
            if (presenting != id || _tasks.value[id]?.phase != TaskPhase.Confirm) return@post
            set(id) { it.copy(phase = TaskPhase.Installing, silent = true) }
            main.postDelayed({
                if (presenting == id && _tasks.value[id]?.phase == TaskPhase.Installing) {
                    set(id) { it.copy(phase = TaskPhase.Confirm, silent = false) }
                }
            }, CONFIRMED_GRACE_MS)
        }
    }

    /** From [InstallStatusReceiver], on the main thread. */
    fun onStatus(packageId: String, status: Int, confirm: Intent?, message: String?) {
        AppLog.i(TAG, "install $packageId status $status${message?.let { ": $it" }.orEmpty()}")
        when (status) {
            STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) {
                    fail(packageId, context.getString(R.string.install_failed))
                    return
                }
                confirmations[packageId] = confirm
                set(packageId) { it.copy(phase = TaskPhase.Confirm, silent = false) }
                if (visible) presentNext() else ConfirmNotifier.post(context, confirmations.keys.map(::nameOf))
                return
            }
            STATUS_SUCCESS -> {
                scope.launch(Dispatchers.IO) { InstallCache.delete(context, packageId) }
                if (packageId in _tasks.value) {
                    // Kept until the package list catches up; the delay only covers a list that never does.
                    set(packageId) { it.copy(phase = TaskPhase.Done, error = null, silent = true) }
                    main.postDelayed({
                        _tasks.update { tasks -> if (tasks[packageId]?.phase == TaskPhase.Done) tasks - packageId else tasks }
                    }, DONE_HOLD_MS)
                }
            }
            // A cancelled install has no task left to put back.
            STATUS_FAILURE_ABORTED -> if (packageId in _tasks.value) set(packageId) { it.copy(phase = TaskPhase.Ready, error = null) }
            else -> if (isSignatureConflict(status, message)) {
                // The APK verified against the listing; keep it for the reinstall.
                fail(packageId, context.getString(R.string.install_blocked_signer), needsReinstall = true)
            } else {
                scope.launch(Dispatchers.IO) { InstallCache.delete(context, packageId) }
                fail(packageId, message ?: context.getString(R.string.install_failed))
            }
        }
        confirmations.remove(packageId)
        if (presenting == packageId) presenting = null
        if (visible) presentNext() else if (confirmations.isEmpty()) ConfirmNotifier.clear(context)
    }

    private fun claim(app: AppRecord, bulk: Boolean, background: Boolean): Boolean = synchronized(jobs) {
        // Done: Android just installed this build; another tap before the package list catches up would fetch it again.
        if (jobs[app.appId]?.isActive == true || _tasks.value[app.appId]?.let { it.busy || it.phase == TaskPhase.Done } == true) return false
        val hash = app.apkHash.orEmpty()
        val previous = _tasks.value[app.appId]?.takeIf { it.hash == hash }
        _tasks.update {
            it + (app.appId to InstallTask(app.appId, hash, app.name, TaskPhase.Queued, previous?.received ?: 0, previous?.total))
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { run(app, bulk, background) }
        jobs[app.appId] = job
        job.invokeOnCompletion { synchronized(jobs) { if (jobs[app.appId] === job) jobs -= app.appId } }
        job.start()
        true
    }

    private suspend fun run(app: AppRecord, bulk: Boolean, background: Boolean) {
        val id = app.appId
        try {
            val (apk, plan) = stage(app)
            if (background && plan !is InstallPlan.Silent) {
                set(id) { it.copy(phase = TaskPhase.Ready) }
                return
            }
            val silent = plan is InstallPlan.Silent && SessionInstaller.silentSupported
            set(id) { it.copy(phase = TaskPhase.Installing, silent = silent) }
            withContext(Dispatchers.IO) { SessionInstaller.commit(context, apk, id, app.name, plan, bulk) }
            AppLog.i(TAG, "committed $id${if (silent) " silently" else ""}")
        } catch (e: CancellationException) {
            val paused = synchronized(jobs) { pausing.remove(id) }
            if (paused) set(id) { it.copy(phase = TaskPhase.Paused) } else _tasks.update { it - id }
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "install $id failed", e)
            val reinstall = e is BlockedInstall && e.reason == BlockReason.Signer
            withContext(NonCancellable + Dispatchers.IO) {
                SessionInstaller.abandon(context, id)
                when {
                    // A dropped connection keeps the partial for a Range resume; a verified APK does not outlive a failed commit.
                    e.isTransient() -> app.apkHash?.let { InstallCache.apk(context, id, it).delete() }
                    // Only the installed copy's key is wrong; the reinstall uses this file.
                    reinstall -> Unit
                    else -> InstallCache.delete(context, id)
                }
            }
            if (background) {
                backgroundErrors[id] = e
                _tasks.update { it - id }
                return
            }
            fail(id, context.installFailure(e), needsReinstall = reinstall)
        }
    }

    /** Downloads unless a verified copy is on disk, then checks it against the listing and the device. */
    private suspend fun stage(app: AppRecord): Pair<java.io.File, InstallPlan> = withContext(Dispatchers.IO) {
        val id = app.appId
        val hash = app.apkHash ?: throw BlockedInstall(BlockReason.MissingFile)
        val early = plan(app, context.installOrigin(id), apk = null)
        if (early is InstallPlan.Blocked) throw BlockedInstall(early.reason)
        InstallCache.delete(context, id, keepHash = hash)
        val dest = InstallCache.apk(context, id, hash)
        if (!dest.exists()) {
            downloads.withPermit {
                set(id) { it.copy(phase = TaskPhase.Downloading) }
                val partial = InstallCache.partial(context, id, hash)
                var reported = 0L
                ApkDownloader.download(calls(), ApkDownloader.urls(app.downloadUrl, hash), partial, hash) { read, total ->
                    if (read - reported < PROGRESS_STEP && read != total) return@download
                    reported = read
                    set(id) { it.copy(received = read, total = total) }
                }
                if (!partial.renameTo(dest)) {
                    partial.copyTo(dest, overwrite = true)
                    partial.delete()
                }
            }
        }
        set(id) { it.copy(phase = TaskPhase.Verifying) }
        val identity = ApkVerifier.inspect(context, dest)
        val plan = plan(app, context.installOrigin(id), identity)
        if (plan is InstallPlan.Blocked) throw BlockedInstall(plan.reason)
        if (!proofHolds(app, proofOf(app), identity.certificateDer)) throw BlockedInstall(BlockReason.Proof)
        dest to plan
    }

    private fun plan(app: AppRecord, origin: InstallOrigin, apk: dev.zapstore.app.apk.ApkIdentity?) = installPlan(
        deviceSdk = Build.VERSION.SDK_INT,
        ourPackage = context.packageName,
        packageId = app.appId,
        origin = origin,
        listedVersionCode = app.versionCode,
        listedHash = app.apkHash,
        listedCertificate = app.certificateHash,
        listedChain = app.certificateChain(),
        apk = apk,
    )

    private fun presentNext() {
        if (presenting != null) return
        confirmations.keys.firstOrNull()?.let(::present)
    }

    private fun present(packageId: String) {
        val intent = confirmations[packageId] ?: return
        presenting = packageId
        val opened = runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        if (opened.isFailure) {
            AppLog.w(TAG, "confirm $packageId", opened.exceptionOrNull())
            presenting = null
            confirmations.remove(packageId)
            fail(packageId, context.getString(R.string.install_failed))
        }
    }

    /** Marks [appId] failed and says why in a toast that stays until closed, with Copy for a bug report. */
    private fun fail(appId: String, message: String, needsReinstall: Boolean = false) {
        _tasks.update { tasks ->
            val task = tasks[appId] ?: InstallTask(appId, "", phase = TaskPhase.Failed)
            tasks + (appId to task.copy(phase = TaskPhase.Failed, error = message, needsReinstall = needsReinstall))
        }
        val reason = if (needsReinstall) context.getString(R.string.install_needs_reinstall) else message
        val text = context.getString(R.string.install_failed_app, nameOf(appId), reason)
        val copied = if (reason == message) text else "$text\n$message"
        Toasts.show(
            Toast(text, ToastKind.Error, context.getString(R.string.copy)) {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(nameOf(appId), copied))
            },
        )
    }

    /** Updates [appId]'s task, or makes one for a session that outlived the process. */
    private fun set(appId: String, change: (InstallTask) -> InstallTask) {
        _tasks.update { tasks -> tasks + (appId to change(tasks[appId] ?: InstallTask(appId, "", phase = TaskPhase.Queued))) }
    }

    private fun nameOf(appId: String) = _tasks.value[appId]?.name ?: appId

    companion object {
        private const val TAG = "Installer"
        private const val MAX_DOWNLOADS = 3
        private const val PROGRESS_STEP = 256L * 1024
        private const val CONFIRMED_GRACE_MS = 60_000L
        private const val CANCEL_JOIN_MS = 10_000L
        private const val DONE_HOLD_MS = 5_000L
        private const val STATUS_PENDING_USER_ACTION = android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION
        private const val STATUS_SUCCESS = android.content.pm.PackageInstaller.STATUS_SUCCESS
        private const val STATUS_FAILURE_ABORTED = android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED
    }
}
