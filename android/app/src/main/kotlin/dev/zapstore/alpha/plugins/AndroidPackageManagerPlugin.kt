package dev.zapstore.alpha.plugins

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.system.Os
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "AndroidPackageManager"

/** Uninstall polling timeout - 30 seconds is plenty for user to confirm/cancel */
private const val UNINSTALL_TIMEOUT_MS = 30_000L
private const val UNINSTALL_POLL_INTERVAL_MS = 500L

/** Watchdog timeouts (bounded with backoff to avoid false negatives on slow devices) */
private const val VERIFY_WATCHDOG_MS = 10_000L
private const val INSTALL_WATCHDOG_MS = 10_000L
private const val MAX_INSTALL_WATCHDOG_MS = 120_000L

/** Buffer size for file operations - 64KB for optimal throughput on large APKs */
private const val COPY_BUFFER_SIZE = 65536

/**
 * Install status values emitted via EventChannel.
 * These form a simple state machine with no hanging states.
 */
object InstallStatus {
    const val STARTED = "started"
    const val VERIFYING = "verifying"
    const val PENDING_USER_ACTION = "pendingUserAction"
    const val ALREADY_IN_PROGRESS = "alreadyInProgress"
    const val SUCCESS = "success"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}

/**
 * Structured error codes for failures.
 * Dart uses these for reliable error categorization instead of parsing strings.
 */
object ErrorCode {
    const val DOWNLOAD_FAILED = "downloadFailed"
    const val HASH_MISMATCH = "hashMismatch"
    const val INVALID_FILE = "invalidFile"
    const val INSTALL_FAILED = "installFailed"
    const val CERT_MISMATCH = "certMismatch"
    const val PERMISSION_DENIED = "permissionDenied"
    const val INSUFFICIENT_STORAGE = "insufficientStorage"
    const val INCOMPATIBLE = "incompatible"
    const val BLOCKED = "blocked"
    const val ALREADY_IN_PROGRESS = "alreadyInProgress"
    const val INSTALL_TIMEOUT = "installTimeout"
}

/**
 * AndroidPackageManagerPlugin - Event-driven architecture for clean state management.
 * 
 * Architecture:
 * - MethodChannel: Fire-and-forget commands (install, uninstall, etc.)
 * - EventChannel: Streams install status events to Dart
 * - ProcessLifecycleOwner: Auto-detects app foreground state (no Dart involvement)
 * 
 * The native side is the single source of truth for install state.
 * Dart simply reacts to events - no polling, no probing, no hanging awaits.
 */
class AndroidPackageManagerPlugin : FlutterPlugin, MethodCallHandler, 
    EventChannel.StreamHandler, DefaultLifecycleObserver {
    
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var context: Context
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Per-app watchdog generation counters (cancels old scheduled callbacks) */
    private val watchdogGen = mutableMapOf<String, Int>()
    private val watchdogDeadlineMs = mutableMapOf<String, Long>()

    /** Track verification threads so we can "ping" and avoid false timeouts */
    private val verificationThreads = mutableMapOf<String, Thread>()
    
    private var eventSink: EventChannel.EventSink? = null
    
    companion object {
        private var instance: AndroidPackageManagerPlugin? = null
        
        /** Map sessionId to packageName for reverse lookup in broadcasts */
        private val sessionToPackage = mutableMapOf<Int, String>()
        
        /** Pending user action intents - stored for re-launch when app returns to foreground */
        private val pendingUserActionIntents = mutableMapOf<String, Intent>()
        
        /** Buffer last-known event per appId when Dart isn't listening yet */
        private val bufferedEvents = mutableMapOf<String, Map<String, Any?>>()

        /** App foreground state - auto-managed by ProcessLifecycleOwner */
        private var isAppInForeground = true
        
        private var appContext: Context? = null
        
        /**
         * Called by InstallResultReceiver when a broadcast arrives.
         * Emits the status event to Dart via EventChannel.
         */
        fun onInstallResult(
            sessionId: Int,
            status: String,
            packageName: String,
            message: String? = null,
            isUpdate: Boolean = false,
            confirmIntent: Intent? = null,
            errorCode: String? = null
        ) {
            val pkg = packageName.ifEmpty { sessionToPackage[sessionId] ?: "unknown" }
            
            Log.d(TAG, "onInstallResult: pkg=$pkg, status=$status, msg=$message, errorCode=$errorCode, sessionId=$sessionId")
            
            // Handle pending user action - store intent for potential re-launch
            if (status == InstallStatus.PENDING_USER_ACTION && confirmIntent != null) {
                pendingUserActionIntents[pkg] = confirmIntent
                
                // Auto-launch dialog if app is in foreground
                if (isAppInForeground) {
                    launchConfirmDialog(pkg, confirmIntent)
                }
            }
            
            // Clean up tracking on terminal states
            if (status in listOf(InstallStatus.SUCCESS, InstallStatus.FAILED, InstallStatus.CANCELLED)) {
                sessionToPackage.remove(sessionId)
                pendingUserActionIntents.remove(pkg)
                
                if (status != InstallStatus.SUCCESS) {
                    abandonSession(sessionId)
                }
            }
            
            val inst = instance
            if (inst == null) {
                Log.e(TAG, "CRITICAL: Cannot emit event - plugin instance is null! pkg=$pkg, status=$status")
                bufferEvent(pkg, status, message, errorCode)
                return
            }
            inst.emitInstallStatus(pkg, status, message, errorCode)
        }

        private fun bufferEvent(
            appId: String,
            status: String,
            message: String? = null,
            errorCode: String? = null
        ) {
            val event = mutableMapOf<String, Any?>(
                "appId" to appId,
                "status" to status
            )
            if (message != null) event["message"] = message
            if (errorCode != null) event["errorCode"] = errorCode
            bufferedEvents[appId] = event
        }
        
        private fun launchConfirmDialog(packageName: String, intent: Intent) {
            val ctx = appContext ?: return
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ctx.startActivity(intent)
                Log.d(TAG, "Launched confirmation dialog for $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch confirmation dialog for $packageName", e)
            }
        }
        
        private fun abandonSession(sessionId: Int) {
            val ctx = appContext ?: return
            try {
                ctx.packageManager.packageInstaller.abandonSession(sessionId)
            } catch (e: Exception) {
                Log.d(TAG, "Could not abandon session $sessionId: ${e.message}")
            }
        }
        
        fun isAppInForeground(): Boolean = isAppInForeground
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // FLUTTER PLUGIN LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════════
    
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        appContext = context
        instance = this
        
        methodChannel = MethodChannel(binding.binaryMessenger, "android_package_manager")
        methodChannel.setMethodCallHandler(this)
        
        eventChannel = EventChannel(binding.binaryMessenger, "android_package_manager/events")
        eventChannel.setStreamHandler(this)
        
        // Auto-detect foreground state via ProcessLifecycleOwner
        mainHandler.post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
        
        cleanupStaleSessions()
        Log.d(TAG, "AndroidPackageManagerPlugin initialized")
    }
    
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        mainHandler.post {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        }
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        eventSink = null
        instance = null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // LIFECYCLE OBSERVER (Auto foreground detection)
    // ═══════════════════════════════════════════════════════════════════════════════
    
    override fun onStart(owner: LifecycleOwner) {
        // App came to foreground
        val wasForeground = isAppInForeground
        isAppInForeground = true
        Log.d(TAG, "App foregrounded")
        
        if (!wasForeground) {
            // Re-launch any pending install dialogs
            for ((packageName, intent) in pendingUserActionIntents.toMap()) {
                Log.d(TAG, "Re-launching pending dialog for $packageName")
                launchConfirmDialog(packageName, intent)
            }
        }
    }
    
    override fun onStop(owner: LifecycleOwner) {
        // App went to background
        isAppInForeground = false
        Log.d(TAG, "App backgrounded")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // EVENT CHANNEL STREAM HANDLER
    // ═══════════════════════════════════════════════════════════════════════════════
    
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        // Flush buffered events (if any) so Dart can reconcile state.
        val sink = eventSink ?: return
        mainHandler.post {
            if (bufferedEvents.isNotEmpty()) {
                for (event in bufferedEvents.values) {
                    try {
                        sink.success(event)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to flush buffered event: $event", e)
                    }
                }
                bufferedEvents.clear()
            }
        }
    }
    
    override fun onCancel(arguments: Any?) {
        eventSink = null
    }
    
    private fun emitInstallStatus(
        appId: String, 
        status: String, 
        message: String? = null,
        errorCode: String? = null,
        description: String? = null
    ) {
        val emitNow = emit@{
            // Update watchdog regardless of whether Dart is listening.
            updateWatchdog(appId, status)

            val sink = eventSink
            if (sink == null) {
                Log.e(TAG, "CRITICAL: Cannot emit event - eventSink is null! appId=$appId, status=$status, msg=$message")
                bufferEvent(appId, status, message, errorCode)
                return@emit
            }
            
            val event = mutableMapOf<String, Any?>(
                "appId" to appId,
                "status" to status
            )
            if (message != null) {
                event["message"] = message
            }
            if (errorCode != null) {
                event["errorCode"] = errorCode
            }
            if (description != null) {
                event["description"] = description
            }
            Log.d(TAG, "Emitting to Dart: $event")
            sink.success(event)
        }

        // If we're already on the main thread (common for MethodChannel calls),
        // emit synchronously so the UI can transition immediately.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            emitNow()
        } else {
            mainHandler.post { emitNow() }
        }
    }

    private fun bumpWatchdogGen(appId: String): Int {
        val next = (watchdogGen[appId] ?: 0) + 1
        watchdogGen[appId] = next
        return next
    }

    private fun clearWatchdog(appId: String) {
        bumpWatchdogGen(appId)
        watchdogDeadlineMs.remove(appId)
    }

    private fun scheduleWatchdog(appId: String, initialDelayMs: Long) {
        val gen = bumpWatchdogGen(appId)
        val now = System.currentTimeMillis()
        val deadline = watchdogDeadlineMs[appId] ?: (now + MAX_INSTALL_WATCHDOG_MS).also {
            watchdogDeadlineMs[appId] = it
        }

        mainHandler.postDelayed({
            if (watchdogGen[appId] != gen) return@postDelayed

            // "Ping" reality before timing out.
            val hasSession = hasActiveSession(appId)
            val hasPendingUi = pendingUserActionIntents.containsKey(appId)
            val verifyThread = verificationThreads[appId]
            val verifyingAlive = verifyThread?.isAlive == true

            // If work is still happening, extend (bounded by deadline).
            val now2 = System.currentTimeMillis()
            if (now2 < deadline && (hasSession || hasPendingUi || verifyingAlive)) {
                // Nudge Dart to show the most accurate state (esp. after reconnect).
                if (hasPendingUi) {
                    emitInstallStatus(appId, InstallStatus.PENDING_USER_ACTION, "User confirmation pending")
                } else if (hasSession) {
                    emitInstallStatus(appId, InstallStatus.STARTED)
                } else if (verifyingAlive) {
                    emitInstallStatus(appId, InstallStatus.VERIFYING)
                }
                // Backoff: next check is another INSTALL_WATCHDOG_MS.
                scheduleWatchdog(appId, INSTALL_WATCHDOG_MS)
                return@postDelayed
            }

            // Deadline exceeded or no evidence of progress: fail fast and cleanup.
            Log.w(TAG, "Watchdog timeout for $appId (hasSession=$hasSession, pendingUi=$hasPendingUi, verifyingAlive=$verifyingAlive)")
            abandonExistingSession(appId)
            emitInstallStatus(
                appId,
                InstallStatus.FAILED,
                "Installation timed out. Please retry.",
                ErrorCode.INSTALL_TIMEOUT
            )
            clearWatchdog(appId)
        }, initialDelayMs)
    }

    private fun updateWatchdog(appId: String, status: String) {
        when (status) {
            InstallStatus.VERIFYING -> {
                // Start watchdog for verify stage
                scheduleWatchdog(appId, VERIFY_WATCHDOG_MS)
            }
            InstallStatus.STARTED, InstallStatus.PENDING_USER_ACTION -> {
                // Start/refresh watchdog for install stage
                scheduleWatchdog(appId, INSTALL_WATCHDOG_MS)
            }
            InstallStatus.SUCCESS, InstallStatus.FAILED, InstallStatus.CANCELLED -> {
                clearWatchdog(appId)
                verificationThreads.remove(appId)
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // METHOD CHANNEL HANDLER
    // ═══════════════════════════════════════════════════════════════════════════════
    
    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "install" -> {
                val filePath = call.argument<String>("filePath")
                val packageName = call.argument<String>("packageName")
                val expectedHash = call.argument<String>("expectedHash")
                val expectedSize = call.argument<Number>("expectedSize")?.toLong()
                
                if (filePath == null || packageName == null) {
                    result.error("MISSING_ARGUMENT", "filePath and packageName required", null)
                    return
                }
                installApk(filePath, packageName, expectedHash, expectedSize, result)
            }
            
            "canInstallSilently" -> {
                val packageName = call.argument<String>("packageName")
                result.success(canInstallSilently(packageName))
            }
            
            "hasUnknownSourcesPermission" -> result.success(hasUnknownSourcesPermission())
            
            "requestInstallPermission" -> requestInstallPermission(result)
            
            "getInstalledApps" -> {
                val includeSystem = call.argument<Boolean>("includeSystemApps") ?: false
                result.success(getInstalledApps(includeSystem))
            }
            
            "uninstall" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName == null) {
                    result.error("MISSING_ARGUMENT", "packageName required", null)
                    return
                }
                uninstallApp(packageName, result)
            }
            
            "launchApp" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName == null) {
                    result.error("MISSING_ARGUMENT", "packageName required", null)
                    return
                }
                launchApp(packageName, result)
            }
            
            else -> result.notImplemented()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // INSTALLATION
    // ═══════════════════════════════════════════════════════════════════════════════
    
    private fun installApk(
        filePath: String,
        packageName: String,
        expectedHash: String?,
        expectedSize: Long?,
        result: Result
    ) {
        val file = File(filePath)
        if (!file.exists()) {
            emitInstallStatus(packageName, InstallStatus.FAILED, "APK file not found", ErrorCode.DOWNLOAD_FAILED)
            result.success(mapOf("started" to false, "error" to "APK file not found", "errorCode" to ErrorCode.DOWNLOAD_FAILED))
            return
        }
        
        if (hasActiveSession(packageName)) {
            if (pendingUserActionIntents.containsKey(packageName)) {
                // Real pending dialog - re-launch it and let user complete
                pendingUserActionIntents[packageName]?.let { launchConfirmDialog(packageName, it) }
                emitInstallStatus(packageName, InstallStatus.PENDING_USER_ACTION, "User confirmation pending")
                result.success(mapOf("started" to false, "alreadyInProgress" to true))
                return
            }
            // No pending dialog = stale session. Abandon and proceed with fresh install.
            Log.d(TAG, "Abandoning stale session for $packageName")
            abandonExistingSession(packageName)
        }
        
        // Check for install restrictions on main thread before starting background work
        val userManager = context.getSystemService(UserManager::class.java)
        val installBlocked = userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS) ||
            userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
            userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        
        if (installBlocked) {
            emitInstallStatus(packageName, InstallStatus.FAILED, "Installation blocked by device policy", ErrorCode.BLOCKED)
            result.success(mapOf("started" to false, "error" to "Blocked by policy", "errorCode" to ErrorCode.BLOCKED))
            return
        }
        
        // Emit verifying status - all install work happens on background thread
        emitInstallStatus(packageName, InstallStatus.VERIFYING)
        result.success(mapOf("started" to true, "verifying" to true))
        
        // All heavy I/O work runs on background thread to prevent ANRs
        val t = Thread {
            verifyAndInstall(file, packageName, expectedHash, expectedSize)
            verificationThreads.remove(packageName)
        }
        verificationThreads[packageName] = t
        t.start()
    }
    
    /**
     * Single-pass verify and install - runs entirely on background thread.
     * 
     * This method:
     * 1. Validates APK format (ZIP magic bytes)
     * 2. Creates install session
     * 3. Streams file ONCE - computing hash AND copying to session simultaneously
     * 4. Reports progress during copy
     * 5. Commits session on success, cleans up on failure
     * 
     * Benefits:
     * - 50% less I/O (file read once instead of twice)
     * - No ANRs (runs off main thread)
     * - Better UX with progress reporting
     * - Proper resource cleanup on all error paths
     */
    private fun verifyAndInstall(
        apkFile: File,
        packageName: String,
        expectedHash: String?,
        expectedSize: Long?
    ) {
        // Step 1: Validate APK format before doing any heavy work
        if (!isValidApkFormat(apkFile)) {
            apkFile.delete()
            mainHandler.post {
                emitInstallStatus(
                    packageName,
                    InstallStatus.FAILED,
                    "Invalid APK file",
                    ErrorCode.INVALID_FILE,
                    "The downloaded file is not a valid APK format."
                )
            }
            return
        }
        
        val packageInstaller = context.packageManager.packageInstaller
        var sessionId = -1
        var session: PackageInstaller.Session? = null
        
        try {
            // Step 2: Check if this is an update
            val isUpdate = try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
            
            // Step 3: Abandon any existing session and create new one
            abandonExistingSession(packageName)
            
            val sessionParams = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                // Use the passed packageName directly - no getPackageArchiveInfo() needed
                // Android validates the APK during commit anyway
                setAppPackageName(packageName)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    setInstallerPackageName(context.packageName)
                }
                // Use AUTO install location - safe default without parsing APK
                setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_AUTO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setRequestUpdateOwnership(true)
                }
            }
            
            sessionId = packageInstaller.createSession(sessionParams)
            session = packageInstaller.openSession(sessionId)
            sessionToPackage[sessionId] = packageName
            
            // Step 4: Single-pass verify + copy with progress reporting
            val digest = if (expectedHash != null) MessageDigest.getInstance("SHA-256") else null
            
            FileInputStream(apkFile).use { fis ->
                val fileSize = Os.fstat(fis.fd).st_size
                
                session.openWrite(apkFile.name, 0, fileSize).use { sessionStream ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var bytesCopied = 0L
                    var lastProgressPercent = -1
                    var bytesRead: Int
                    
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        // Hash the chunk if verification is needed
                        digest?.update(buffer, 0, bytesRead)
                        
                        // Copy to session
                        sessionStream.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead
                        
                        // Report progress (throttled to whole percentage changes)
                        if (fileSize > 0) {
                            val progressPercent = ((bytesCopied * 100) / fileSize).toInt()
                            if (progressPercent != lastProgressPercent && progressPercent % 5 == 0) {
                                lastProgressPercent = progressPercent
                                Log.d(TAG, "Copy progress for $packageName: $progressPercent%")
                            }
                        }
                    }
                    
                    session.fsync(sessionStream)
                }
            }
            
            // Step 5: Verify hash if expected
            if (digest != null && expectedHash != null) {
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    // Hash mismatch - clean up and fail
                    session.close()
                    packageInstaller.abandonSession(sessionId)
                    sessionToPackage.remove(sessionId)
                    
                    mainHandler.post {
                        emitInstallStatus(
                            packageName,
                            InstallStatus.FAILED,
                            "Hash verification failed",
                            ErrorCode.HASH_MISMATCH,
                            "The downloaded file hash does not match.\n\nExpected: $expectedHash\nActual: $actualHash"
                        )
                    }
                    return
                }
            }
            
            // Step 6: Emit STARTED *before* commit to avoid race with SUCCESS broadcast
            // For silent installs, the SUCCESS broadcast can arrive immediately after commit.
            // We use a CountDownLatch to ensure STARTED is processed on main thread before
            // we call commit(), which could trigger an immediate broadcast.
            val startedLatch = CountDownLatch(1)
            mainHandler.post {
                emitInstallStatus(packageName, InstallStatus.STARTED)
                startedLatch.countDown()
            }
            // Wait for STARTED to be emitted (timeout prevents deadlock if main thread is blocked)
            startedLatch.await(1, TimeUnit.SECONDS)
            
            // Step 7: Commit the session - this triggers the install broadcast
            val intent = Intent(context.applicationContext, InstallResultReceiver::class.java).apply {
                putExtra("sessionId", sessionId)
                putExtra("packageName", packageName)
                putExtra("isUpdate", isUpdate)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context.applicationContext, sessionId, intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            session.commit(pendingIntent.intentSender)
            session.close()
            session = null  // Prevent double-close in finally
            
        } catch (e: SecurityException) {
            // Clean up session on error
            session?.close()
            if (sessionId >= 0) {
                try { packageInstaller.abandonSession(sessionId) } catch (_: Exception) {}
                sessionToPackage.remove(sessionId)
            }
            
            mainHandler.post {
                emitInstallStatus(
                    packageName,
                    InstallStatus.FAILED,
                    "Permission denied: ${e.message}",
                    ErrorCode.PERMISSION_DENIED
                )
            }
        } catch (e: Exception) {
            // Clean up session on error
            session?.close()
            if (sessionId >= 0) {
                try { packageInstaller.abandonSession(sessionId) } catch (_: Exception) {}
                sessionToPackage.remove(sessionId)
            }
            
            mainHandler.post {
                emitInstallStatus(
                    packageName,
                    InstallStatus.FAILED,
                    e.message ?: "Installation failed",
                    ErrorCode.INSTALL_FAILED
                )
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════════
    
    private fun hasActiveSession(packageName: String): Boolean {
        return sessionToPackage.containsValue(packageName) || 
               findExistingSession(packageName) != null
    }
    
    private fun findExistingSession(packageName: String): PackageInstaller.SessionInfo? {
        return try {
            context.packageManager.packageInstaller.mySessions
                .find { it.appPackageName == packageName }
        } catch (_: Exception) { null }
    }
    
    private fun abandonExistingSession(packageName: String) {
        val session = findExistingSession(packageName) ?: return
        try {
            context.packageManager.packageInstaller.abandonSession(session.sessionId)
            sessionToPackage.remove(session.sessionId)
            pendingUserActionIntents.remove(packageName)
        } catch (_: Exception) {}
    }
    
    private fun cleanupStaleSessions() {
        try {
            val installer = context.packageManager.packageInstaller
            installer.mySessions.filter { !it.isActive }.forEach { session ->
                try { installer.abandonSession(session.sessionId) } catch (_: Exception) {}
            }
            sessionToPackage.clear()
            pendingUserActionIntents.clear()
        } catch (_: Exception) {}
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // APK VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * Quick APK format check - validates ZIP magic bytes.
     * This is a lightweight check that doesn't load the APK into memory.
     */
    private fun isValidApkFormat(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val magic = ByteArray(4)
                if (fis.read(magic) < 4) return false
                magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                    magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
            }
        } catch (_: Exception) { false }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ═══════════════════════════════════════════════════════════════════════════════
    
    private fun canInstallSilently(packageName: String? = null): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.d(TAG, "canInstallSilently($packageName): false - API < 31")
            return false
        }
        
        val canRequest = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.packageManager.canRequestPackageInstalls()
            } else true
        } catch (_: Throwable) { false }
        
        if (!canRequest) {
            Log.d(TAG, "canInstallSilently($packageName): false - no install permission")
            return false
        }
        
        if (packageName != null) {
            return try {
                val installSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.packageManager.getInstallSourceInfo(packageName)
                } else null
                
                val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && installSource != null) {
                    installSource.installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getInstallerPackageName(packageName)
                }
                
                val ourPackage = context.packageName
                val result = installer == ourPackage
                
                Log.d(TAG, "canInstallSilently($packageName): installer=$installer, us=$ourPackage, result=$result")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && installSource != null) {
                    Log.d(TAG, "  initiatingPackage=${installSource.initiatingPackageName}")
                    Log.d(TAG, "  originatingPackage=${installSource.originatingPackageName}")
                }
                
                result
            } catch (_: PackageManager.NameNotFoundException) {
                Log.d(TAG, "canInstallSilently($packageName): false - package not found")
                false  // Package not found = can't silently install it
            } catch (e: Exception) {
                Log.d(TAG, "canInstallSilently($packageName): false - error: ${e.message}")
                false
            }
        }
        
        // Check general capability (no specific package)
        val hasPermission = try {
            context.checkSelfPermission("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION") ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        
        Log.d(TAG, "canInstallSilently(general): hasUpdatePermission=$hasPermission")
        return hasPermission
    }
    
    private fun hasUnknownSourcesPermission(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.packageManager.canRequestPackageInstalls()
            } else true
        } catch (_: Throwable) { false }
    }
    
    private fun requestInstallPermission(result: Result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
            !context.packageManager.canRequestPackageInstalls()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                result.success(mapOf("success" to true))
            } catch (e: Exception) {
                result.success(mapOf("success" to false, "message" to e.message))
            }
        } else {
            result.success(mapOf("success" to true))
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // UNINSTALL
    // ═══════════════════════════════════════════════════════════════════════════════
    
    private fun uninstallApp(packageName: String, result: Result) {
        try {
            try {
                context.packageManager.getPackageInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                result.success(mapOf("isSuccess" to false, "errorMessage" to "Package not found"))
                return
            }
            
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            
            // Poll for removal with 30 second timeout
            Thread {
                val startTime = System.currentTimeMillis()
                
                while (System.currentTimeMillis() - startTime < UNINSTALL_TIMEOUT_MS) {
                    try {
                        Thread.sleep(UNINSTALL_POLL_INTERVAL_MS)
                        context.packageManager.getPackageInfo(packageName, 0)
                    } catch (_: PackageManager.NameNotFoundException) {
                        mainHandler.post {
                            result.success(mapOf("isSuccess" to true, "packageName" to packageName))
                        }
                        return@Thread
                    } catch (_: InterruptedException) { break }
                }
                
                val stillInstalled = try {
                    context.packageManager.getPackageInfo(packageName, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) { false }
                
                mainHandler.post {
                    result.success(mapOf(
                        "isSuccess" to !stillInstalled,
                        "packageName" to packageName,
                        "cancelled" to stillInstalled
                    ))
                }
            }.start()
            
        } catch (e: Exception) {
            result.success(mapOf("isSuccess" to false, "errorMessage" to e.message))
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // APP LAUNCH
    // ═══════════════════════════════════════════════════════════════════════════════
    
    private fun launchApp(packageName: String, result: Result) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                result.success(mapOf("isSuccess" to false, "errorMessage" to "No launch activity"))
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(launchIntent)
            result.success(mapOf("isSuccess" to true))
        } catch (e: Exception) {
            result.success(mapOf("isSuccess" to false, "errorMessage" to e.message))
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // INSTALLED APPS
    // ═══════════════════════════════════════════════════════════════════════════════
    
    private fun getInstalledApps(includeSystemApps: Boolean): List<Map<String, Any?>> {
        val pm = context.packageManager
        return pm.getInstalledPackages(0).mapNotNull { pkg ->
            val appInfo = pkg.applicationInfo ?: return@mapNotNull null
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystemApps && isSystem) return@mapNotNull null
            
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }
            
            mapOf(
                "name" to appInfo.loadLabel(pm)?.toString(),
                "bundleId" to pkg.packageName,
                "versionName" to pkg.versionName,
                "versionCode" to versionCode,
                "signatureHash" to getSignatureHash(pkg.packageName),
                "canInstallSilently" to canInstallSilently(pkg.packageName)
            )
        }
    }
    
    private fun getSignatureHash(packageName: String): String {
        return try {
            val pm = context.packageManager
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
            
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.signingInfo?.let { si ->
                    if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                } ?: emptyArray()
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.signatures ?: emptyArray()
            }
            
            if (signatures.isEmpty()) return ""
            
            MessageDigest.getInstance("SHA-256")
                .digest(signatures[0].toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }
}
