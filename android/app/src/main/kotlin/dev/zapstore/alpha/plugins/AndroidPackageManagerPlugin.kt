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

private const val TAG = "AndroidPackageManager"

/** Uninstall polling timeout - 30 seconds is plenty for user to confirm/cancel */
private const val UNINSTALL_TIMEOUT_MS = 30_000L
private const val UNINSTALL_POLL_INTERVAL_MS = 500L

/** Watchdog timeouts (bounded with backoff to avoid false negatives on slow devices) */
private const val VERIFY_WATCHDOG_MS = 10_000L
private const val INSTALL_WATCHDOG_MS = 10_000L
private const val MAX_INSTALL_WATCHDOG_MS = 120_000L

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
        
        if (expectedHash != null && expectedSize != null) {
            // Emit verifying status before starting hash check
            emitInstallStatus(packageName, InstallStatus.VERIFYING)
            result.success(mapOf("started" to true, "verifying" to true))
            
            val t = Thread {
                val verification = verifyApk(file, expectedHash, expectedSize)
                mainHandler.post {
                    if (verification.isSuccess) {
                        startInstallSession(file, packageName, result = null) // Result already sent
                    } else {
                        emitInstallStatus(
                            packageName, 
                            InstallStatus.FAILED, 
                            verification.errorTitle,
                            verification.errorCode,
                            verification.errorDescription
                        )
                    }
                    verificationThreads.remove(packageName)
                }
            }
            verificationThreads[packageName] = t
            t.start()
        } else {
            startInstallSession(file, packageName, result)
        }
    }
    
    private fun startInstallSession(apkFile: File, packageName: String, result: Result?) {
        try {
            val userManager = context.getSystemService(UserManager::class.java)
            val installBlocked = userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS) ||
                userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
                userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
            
            if (installBlocked) {
                emitInstallStatus(packageName, InstallStatus.FAILED, "Installation blocked by device policy", ErrorCode.BLOCKED)
                result?.success(mapOf("started" to false, "error" to "Blocked by policy", "errorCode" to ErrorCode.BLOCKED))
                return
            }
            
            val packageInstaller = context.packageManager.packageInstaller
            val pkgInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (pkgInfo == null) {
                emitInstallStatus(packageName, InstallStatus.FAILED, "Invalid APK file", ErrorCode.INVALID_FILE)
                result?.success(mapOf("started" to false, "error" to "Invalid APK", "errorCode" to ErrorCode.INVALID_FILE))
                return
            }
            
            // CRITICAL: Use the passed packageName for tracking, not the APK's internal package name
            // This ensures Dart's operation key matches the events we emit
            // The APK's packageName should match, but we log a warning if not
            val trackingPackageName = packageName
            if (pkgInfo.packageName != packageName) {
                Log.w(TAG, "Package name mismatch: tracking='$packageName', APK contains='${pkgInfo.packageName}'. Using tracking name for events.")
            }
            
            val isUpdate = try {
                context.packageManager.getPackageInfo(pkgInfo.packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
            
            abandonExistingSession(trackingPackageName)
            
            val sessionParams = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                // Set app package name for proper installer tracking
                setAppPackageName(pkgInfo.packageName)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    // Explicitly set ourselves as the installer for update ownership
                    setInstallerPackageName(context.packageName)
                }
                setInstallLocation(pkgInfo.installLocation)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setRequestUpdateOwnership(true)
                }
            }
            
            val sessionId = packageInstaller.createSession(sessionParams)
            val session = packageInstaller.openSession(sessionId)
            // Use tracking name so events match Dart's operation key
            sessionToPackage[sessionId] = trackingPackageName
            
            FileInputStream(apkFile).use { fis ->
                val fileSize = Os.fstat(fis.fd).st_size
                val sessionStream = session.openWrite(apkFile.name, 0, fileSize)
                fis.copyTo(sessionStream)
                fis.close()
                session.fsync(sessionStream)
                sessionStream.close()
            }
            
            val intent = Intent(context.applicationContext, InstallResultReceiver::class.java).apply {
                putExtra("sessionId", sessionId)
                // Use tracking name so events match Dart's operation key
                putExtra("packageName", trackingPackageName)
                putExtra("isUpdate", isUpdate)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context.applicationContext, sessionId, intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            session.commit(pendingIntent.intentSender)
            session.close()
            
            emitInstallStatus(trackingPackageName, InstallStatus.STARTED)
            result?.success(mapOf("started" to true, "sessionId" to sessionId, "packageName" to trackingPackageName))
            
        } catch (e: SecurityException) {
            emitInstallStatus(packageName, InstallStatus.FAILED, "Permission denied: ${e.message}", ErrorCode.PERMISSION_DENIED)
            result?.success(mapOf("started" to false, "error" to "Permission denied", "errorCode" to ErrorCode.PERMISSION_DENIED))
        } catch (e: Exception) {
            emitInstallStatus(packageName, InstallStatus.FAILED, e.message ?: "Installation failed", ErrorCode.INSTALL_FAILED)
            result?.success(mapOf("started" to false, "error" to e.message, "errorCode" to ErrorCode.INSTALL_FAILED))
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
    // APK VERIFICATION
    // ═══════════════════════════════════════════════════════════════════════════════
    
    private data class VerificationResult(
        val isSuccess: Boolean, 
        val errorTitle: String = "",
        val errorDescription: String? = null,
        val errorCode: String? = null
    )
    
    private fun verifyApk(file: File, expectedHash: String, expectedSize: Long): VerificationResult {
        if (!isValidApkFormat(file)) {
            file.delete()
            return VerificationResult(
                false, 
                "Invalid APK file",
                "The downloaded file is not a valid APK format.",
                ErrorCode.INVALID_FILE
            )
        }
        
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        
        if (actualHash.lowercase() != expectedHash.lowercase()) {
            return VerificationResult(
                false, 
                "Hash verification failed",
                "The downloaded file hash does not match.\n\nExpected: $expectedHash\nActual: $actualHash",
                ErrorCode.HASH_MISMATCH
            )
        }
        return VerificationResult(true)
    }
    
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
