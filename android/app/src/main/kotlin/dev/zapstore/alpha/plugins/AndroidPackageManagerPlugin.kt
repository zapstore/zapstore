package dev.zapstore.alpha.plugins

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.app.admin.DevicePolicyManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.system.Os
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

private const val TAG = "AndroidPackageManager"

/**
 * AndroidPackageManagerPlugin following Accrescent's proven silent installation approach.
 * 
 * Silent Installation Behavior:
 * - Apps installed BY Zapstore: Updates are completely silent (no user prompt)
 * - Apps installed by OTHER sources: First update requires user confirmation to transfer
 *   installer ownership. After accepting once, all subsequent updates are silent.
 * 
 * This is Android's security model to prevent malicious "store hijacking" - even with
 * APP_MARKET category and UPDATE_PACKAGES_WITHOUT_USER_ACTION permission.
 * 
 * Requirements:
 * - Android 12+ (API 31+) for USER_ACTION_NOT_REQUIRED
 * - APP_MARKET category in AndroidManifest.xml
 * - UPDATE_PACKAGES_WITHOUT_USER_ACTION permission
 * - ENFORCE_UPDATE_OWNERSHIP permission (Android 14+)
 */
class AndroidPackageManagerPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    
    companion object {
        // Store pending install results keyed by sessionId - completed when broadcast arrives
        private val pendingInstallResults = mutableMapOf<Int, Result>()
        
        // Map sessionId to packageName for reverse lookup
        private val sessionToPackage = mutableMapOf<Int, String>()
        
        // Store pending user action intents keyed by packageName - for re-launching after foreground
        private val pendingUserActionIntents = mutableMapOf<String, Intent>()

        // Track whether the pending user action prompt was deferred (app backgrounded or launch failed).
        // Only deferred prompts should be re-launched on app resume to avoid double system dialogs.
        private val pendingUserActionDeferred = mutableMapOf<String, Boolean>()
        
        // Track app foreground state - set by Dart via setAppForegroundState
        private var isAppInForeground = true
        
        // Reference to context for static methods (set during onAttachedToEngine)
        private var appContext: Context? = null
        
        /**
         * Set the app's foreground state. Called from Dart when lifecycle changes.
         */
        fun setAppForegroundState(foreground: Boolean) {
            isAppInForeground = foreground
            Log.d(TAG, "App foreground state: $foreground")
        }
        
        /**
         * Check if the app is currently in foreground.
         */
        fun isAppInForeground(): Boolean = isAppInForeground
        
        /**
         * Check if we're already waiting for a result for this package.
         */
        fun hasPendingResultForPackage(packageName: String): Boolean {
            return sessionToPackage.containsValue(packageName)
        }
        
        /**
         * Called by InstallResultReceiver when installation completes/fails.
         * Completes the pending method channel result so Dart await finishes.
         */
        fun completeInstallResult(sessionId: Int, resultMap: Map<String, Any?>, context: Context? = null) {
            val result = pendingInstallResults.remove(sessionId)
            val packageName = resultMap["packageName"] as? String ?: sessionToPackage[sessionId]
            
            // Clear all tracking for this session/package
            sessionToPackage.remove(sessionId)
            if (packageName != null) {
                pendingUserActionIntents.remove(packageName)
                pendingUserActionDeferred.remove(packageName)
                Log.d(TAG, "Cleared all tracking for $packageName (session $sessionId)")
            }
            
            // For failed/cancelled installs, try to abandon the session to clean up
            val isSuccess = resultMap["isSuccess"] as? Boolean ?: false
            val ctx = context ?: appContext
            if (!isSuccess && ctx != null) {
                try {
                    val packageInstaller = ctx.packageManager.packageInstaller
                    packageInstaller.abandonSession(sessionId)
                    Log.d(TAG, "Abandoned failed session $sessionId")
                } catch (e: Exception) {
                    // Session might already be abandoned or completed
                    Log.d(TAG, "Could not abandon session $sessionId: ${e.message}")
                }
            }
            
            if (result != null) {
                Log.d(TAG, "Completing install result for session $sessionId: $resultMap")
                result.success(resultMap)
            } else {
                Log.w(TAG, "No pending result found for session $sessionId")
            }
        }
        
        /**
         * Check if we have a pending result for this session (used by receiver)
         */
        fun hasPendingResult(sessionId: Int): Boolean {
            return pendingInstallResults.containsKey(sessionId)
        }
        
        /**
         * Store a pending user action intent for later re-launch.
         */
        fun storePendingUserActionIntent(packageName: String, intent: Intent) {
            pendingUserActionIntents[packageName] = intent
            Log.d(TAG, "Stored pending user action intent for $packageName")
        }

        /**
         * Mark whether a pending user action prompt was deferred.
         *
         * - deferred=true: prompt was NOT shown (or failed to show). Safe to re-launch on resume.
         * - deferred=false: prompt was shown while in foreground. Do NOT re-launch on resume.
         */
        fun setPendingUserActionDeferred(packageName: String, deferred: Boolean) {
            pendingUserActionDeferred[packageName] = deferred
            Log.d(TAG, "Pending user action deferred for $packageName: $deferred")
        }
        
        /**
         * Check if there's a pending user action for a package
         */
        fun hasPendingUserAction(packageName: String): Boolean {
            return pendingUserActionIntents.containsKey(packageName)
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "android_package_manager")
        channel.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
        appContext = context
        
        // Clean up ALL existing sessions on startup - start fresh
        abandonAllSessions()
        
        Log.d(TAG, "AndroidPackageManagerPlugin initialized")
    }
    
    /**
     * Abandon ALL PackageInstaller sessions owned by this app.
     * Called on startup to ensure clean state.
     */
    private fun abandonAllSessions() {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val mySessions = packageInstaller.mySessions
            var abandonedCount = 0
            
            for (session in mySessions) {
                // Never abandon active sessions: doing so can cause "session files in use" and/or
                // break in-progress installs when the engine re-attaches.
                if (session.isActive) {
                    Log.d(TAG, "Startup cleanup: skipping active session ${session.sessionId} for ${session.appPackageName}")
                    continue
                }
                Log.d(TAG, "Abandoning startup session ${session.sessionId} for ${session.appPackageName}")
                try {
                    packageInstaller.abandonSession(session.sessionId)
                    abandonedCount++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to abandon session ${session.sessionId}", e)
                }
            }
            
            // Clear all in-memory state
            pendingInstallResults.clear()
            sessionToPackage.clear()
            pendingUserActionIntents.clear()
            pendingUserActionDeferred.clear()
            
            if (abandonedCount > 0) {
                Log.d(TAG, "Startup cleanup: abandoned $abandonedCount sessions")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup sessions on startup", e)
        }
    }
    
    /**
     * Find an existing session for a package, if any.
     * Returns the session info or null if no session exists.
     */
    private fun findExistingSession(packageName: String): PackageInstaller.SessionInfo? {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            packageInstaller.mySessions.find { it.appPackageName == packageName }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check existing sessions", e)
            null
        }
    }
    
    /**
     * Abandon any existing session for a package.
     * Returns true if a session was abandoned.
     */
    private fun abandonExistingSession(packageName: String): Boolean {
        val existingSession = findExistingSession(packageName)
        if (existingSession != null) {
            try {
                val packageInstaller = context.packageManager.packageInstaller
                packageInstaller.abandonSession(existingSession.sessionId)
                pendingInstallResults.remove(existingSession.sessionId)
                sessionToPackage.remove(existingSession.sessionId)
                pendingUserActionIntents.remove(packageName)
                pendingUserActionDeferred.remove(packageName)
                Log.d(TAG, "Abandoned existing session ${existingSession.sessionId} for $packageName")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to abandon session for $packageName", e)
            }
        }
        return false
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        // Clear any pending results on detach
        pendingInstallResults.clear()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "install" -> {
                val filePath = call.argument<String>("filePath")
                if (filePath == null) {
                    result.error("MISSING_ARGUMENT", "File path is required", null)
                    return
                }
                val expectedHash = call.argument<String>("expectedHash")
                // Dart integers come as Integer, not Long, so we need to safely convert
                val expectedSize = call.argument<Number>("expectedSize")?.toLong()
                val skipVerification = call.argument<Boolean>("skipVerification") ?: false
                installApk(filePath, expectedHash, expectedSize, skipVerification, result)
            }
            "canInstallSilently" -> {
                val packageName = call.argument<String>("packageName")
                result.success(canInstallSilently(packageName))
            }
            "hasUnknownSourcesPermission" -> {
                result.success(hasUnknownSourcesPermission())
            }
            "getInstalledApps" -> {
                val includeSystem = call.argument<Boolean>("includeSystemApps") ?: false
                result.success(getInstalledApps(includeSystem))
            }
            "requestInstallPermission" -> {
                requestInstallPermission(result)
            }
            "uninstall" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName == null) {
                    result.error("MISSING_ARGUMENT", "Package name is required", null)
                    return
                }
                uninstallApp(packageName, result)
            }
            "launchApp" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName == null) {
                    result.error("MISSING_ARGUMENT", "Package name is required", null)
                    return
                }
                launchApp(packageName, result)
            }
            "retryPendingInstall" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName == null) {
                    result.error("MISSING_ARGUMENT", "Package name is required", null)
                    return
                }
                retryPendingInstall(packageName, result)
            }
            "setAppForegroundState" -> {
                val foreground = call.argument<Boolean>("foreground") ?: true
                setAppForegroundState(foreground)
                result.success(null)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun canInstallSilently(packageName: String? = null): Boolean {
        try {
            // Android 12+ (API 31+) is required for USER_ACTION_NOT_REQUIRED to work
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return false
            }

            // 1) UPDATE_PACKAGES_WITHOUT_USER_ACTION permission (the main one we rely on)
            val hasUpdateWithoutUserActionPerm = try {
                context.checkSelfPermission("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION") == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) { false }

            // 2) Device owner (enterprise) can generally install without user action
            val isDeviceOwner = try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.isDeviceOwnerApp(context.packageName)
            } catch (_: Throwable) { false }

            // 3) Default package installer role (rare; typically system store)
            val hasInstallerRole = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
                    val installerRole = "android.app.role.PACKAGE_INSTALLER"
                    roleManager.isRoleAvailable(installerRole) &&
                        roleManager.isRoleHeld(installerRole)
                } else false
            } catch (_: Throwable) { false }

            // 4) Check if we can request package installs (basic requirement)
            val canRequestInstalls = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.packageManager.canRequestPackageInstalls()
                } else true
            } catch (_: Throwable) { false }

            // Additional check for installer ownership if package name provided
            val weAreInstaller = if (packageName != null) {
                try {
                    val installerPackageName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.packageManager.getInstallSourceInfo(packageName).installingPackageName
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getInstallerPackageName(packageName)
                    }
                    val isInstaller = installerPackageName == context.packageName
                    isInstaller
                } catch (e: PackageManager.NameNotFoundException) {
                    // Package not installed yet, so we can install it silently (first install)
                    Log.d(TAG, "Package $packageName not installed yet (NameNotFoundException)")
                    true
                } catch (e: Exception) {
                    // If we can't verify we're the installer, assume we're not
                    Log.w(TAG, "Failed to check installer for $packageName", e)
                    false
                }
            } else {
                true // No specific package check
            }

            // Can install silently if we have the permission OR (we're the installer/device owner/have role)
            val hasSpecialPrivilege = hasUpdateWithoutUserActionPerm || isDeviceOwner || hasInstallerRole
            
            // CRITICAL: Even with UPDATE_PACKAGES_WITHOUT_USER_ACTION permission,
            // Android requires one-time user confirmation to transfer installer ownership.
            // For UI categorization, we must check if we're already the installer.
            // If a specific package is being checked, installer ownership is REQUIRED for truly silent updates.
            val canSilently = if (packageName != null) {
                // For specific package checks: must be installer OR have special privileges AND be installer
                // Actually, even with privileges, first update of non-owned app requires user action
                canRequestInstalls && weAreInstaller
            } else {
                // General capability check (no specific package)
                canRequestInstalls && hasSpecialPrivilege
            }
            
            return canSilently
        } catch (e: Throwable) {
            Log.w(TAG, "canInstallSilently check failed", e)
            return false
        }
    }

    private fun hasUnknownSourcesPermission(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.packageManager.canRequestPackageInstalls()
            } else true
        } catch (_: Throwable) { false }
    }

    private fun requestInstallPermission(result: Result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    result.success(mapOf(
                        "success" to true,
                        "message" to "Permission request launched"
                    ))
                } catch (e: Exception) {
                    result.success(mapOf(
                        "success" to false,
                        "message" to "Failed to launch permission request: ${e.message}"
                    ))
                }
            } else {
                result.success(mapOf(
                    "success" to true,
                    "message" to "Permission already granted"
                ))
            }
        } else {
            result.success(mapOf(
                "success" to true,
                "message" to "Permission not required on this Android version"
            ))
        }
    }

    private fun isValidApk(apkFile: File): Boolean {
        // Check if file is a valid ZIP/APK by verifying ZIP magic bytes
        // APK files are ZIP archives, so they should start with PK\x03\x04 (0x504B0304)
        try {
            FileInputStream(apkFile).use { fis ->
                val magicBytes = ByteArray(4)
                val bytesRead = fis.read(magicBytes)
                if (bytesRead < 4) {
                    Log.w(TAG, "File too small to be a valid APK: ${apkFile.length()} bytes")
                    return false
                }
                
                // Check for ZIP magic number: 0x504B0304 (PK\x03\x04)
                val isZip = magicBytes[0] == 0x50.toByte() && 
                           magicBytes[1] == 0x4B.toByte() && 
                           magicBytes[2] == 0x03.toByte() && 
                           magicBytes[3] == 0x04.toByte()
                
                if (!isZip) {
                    Log.w(TAG, "File does not have valid ZIP/APK magic bytes")
                    return false
                }
                
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking APK validity", e)
            return false
        }
    }
    
    private fun verifyApk(
        apkFile: File, 
        expectedHash: String,
        expectedSize: Long,
        result: Result
    ): Boolean {
        val actualSize = apkFile.length()
        
        // First, verify this is a valid APK/ZIP file
        if (!isValidApk(apkFile)) {
            val errorMsg = """
                Invalid APK file. The downloaded file is not a valid Android package.
                This may indicate:
                • Incomplete download
                • Corrupted file
                • Wrong file type
                
                File size: ${String.format("%.2f", actualSize / 1024.0 / 1024.0)} MB ($actualSize bytes)
                Please try downloading again.
            """.trimIndent()
            
            result.success(mapOf(
                "isSuccess" to false,
                "errorMessage" to errorMsg
            ))
            apkFile.delete()
            return false
        }
        
        Log.d(TAG, "APK file format validation passed")
        
        // Calculate SHA-256 hash for verification
        // This is the primary integrity check - if hash matches, file is correct
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        FileInputStream(apkFile).use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        
        if (actualHash.lowercase() != expectedHash.lowercase()) {
            // Hash mismatch - include size information in error
            val expectedSizeMb = String.format("%.2f", expectedSize / 1024.0 / 1024.0)
            val actualSizeMb = String.format("%.2f", actualSize / 1024.0 / 1024.0)
            val sizeDiff = actualSize - expectedSize
            val sizeDiffMb = String.format("%.2f", kotlin.math.abs(sizeDiff) / 1024.0 / 1024.0)
            val sizeDiffSign = if (sizeDiff > 0) "+" else ""
            
            val errorMsg = """
                Hash verification failed. File may be corrupted or tampered.
                Expected hash: $expectedHash
                Actual hash: $actualHash
                Expected size: $expectedSizeMb MB ($expectedSize bytes)
                Actual size: $actualSizeMb MB ($actualSize bytes)
                Difference: $sizeDiffSign$sizeDiffMb MB ($sizeDiffSign$sizeDiff bytes)
            """.trimIndent()
            
            result.success(mapOf(
                "isSuccess" to false,
                "errorMessage" to errorMsg
            ))
            // Don't delete the file - user might want to proceed anyway
            return false
        }
        
        Log.d(TAG, "APK verification successful: size=$actualSize bytes (expected $expectedSize), hash=$actualHash")
        return true
    }

    private fun installApk(
        filePath: String, 
        expectedHash: String?,
        expectedSize: Long?,
        skipVerification: Boolean,
        result: Result
    ) {
        val file = File(filePath)
        if (!file.exists()) {
            result.success(mapOf(
                "isSuccess" to false,
                "errorMessage" to "The APK file was not found"
            ))
            return
        }

        // Verify APK if hash and size are provided, unless skipping verification
        if (skipVerification) {
            Log.w(TAG, "⚠️ RECKLESS MODE: APK verification SKIPPED by user request ⚠️")
            Log.w(TAG, "Installing APK without hash verification: $filePath")
        } else if (expectedHash != null && expectedSize != null) {
            if (!verifyApk(file, expectedHash, expectedSize, result)) {
                return  // Verification failed, error already sent
            }
        } else {
            Log.w(TAG, "APK verification skipped - hash or size not provided")
        }

        try {
            installApp(file, result)
        } catch (e: SecurityException) {
            // Fallback to system installer
            launchSystemInstaller(filePath, result)
        } catch (e: Exception) {
            result.success(mapOf(
                "isSuccess" to false,
                "errorMessage" to "Installation failed: ${e.message}"
            ))
        }
    }

    private fun installApp(apkFile: File, result: Result) {
        // Check UserManager restrictions (like Accrescent does)
        val userManager = context.getSystemService(UserManager::class.java)
        val installBlocked =
            userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS) ||
            userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
            userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        
        if (installBlocked) {
            Log.w(TAG, "Installation blocked by UserManager policy")
            result.success(mapOf(
                "isSuccess" to false,
                "errorMessage" to "Installation blocked by device policy"
            ))
            return
        }
        
        val packageInstaller = context.packageManager.packageInstaller
        
        // Get package info from the APK
        val pkgInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        if (pkgInfo == null) {
            throw IOException("Invalid APK file")
        }
        
        val packageName = pkgInfo.packageName
        val isUpdate = try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        
        // CRITICAL: Check if we're already waiting for a result for this package
        // This prevents double-prompts and "session files in use" errors
        if (hasPendingResultForPackage(packageName)) {
            Log.d(TAG, "Already have pending result for $packageName")
            
            // Only re-show the dialog if the prompt was actually deferred.
            // If the prompt was already shown in the foreground, re-launching it causes double prompts.
            val pendingIntent = pendingUserActionIntents[packageName]
            val deferred = pendingUserActionDeferred[packageName] == true
            if (pendingIntent != null && deferred && isAppInForeground()) {
                Log.d(TAG, "Re-launching deferred dialog for $packageName")
                try {
                    pendingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    context.startActivity(pendingIntent)
                    pendingUserActionDeferred[packageName] = false
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to re-launch dialog", e)
                }
            }
            
            // Tell Dart that installation is already in progress - don't start a new one
            result.success(mapOf(
                "isSuccess" to false,
                "errorMessage" to "Installation already in progress",
                "packageName" to packageName,
                "alreadyInProgress" to true
            ))
            return
        }
        
        // Check for orphaned system session (no pending result, but session exists)
        val existingSession = findExistingSession(packageName)
        if (existingSession != null) {
            Log.d(TAG, "Found orphaned session ${existingSession.sessionId} for $packageName, abandoning it")
            abandonExistingSession(packageName)
        }
        
        Log.d(TAG, "Installing $packageName (update=$isUpdate)")
        
        // Match Accrescent's session setup exactly
        // The APP_MARKET category in the manifest grants silent install privileges
        val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sessionParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        sessionParams.setInstallLocation(pkgInfo.installLocation)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sessionParams.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            sessionParams.setRequestUpdateOwnership(true)
        }
        
        val sessionId = packageInstaller.createSession(sessionParams)
        val session = packageInstaller.openSession(sessionId)
        
        Log.d(TAG, "Created new session $sessionId for $packageName")
        
        // Transfer the APK file - match Accrescent's approach exactly
        FileInputStream(apkFile).use { fileInputStream ->
            // Use Os.fstat for accurate file size (like Accrescent)
            val fileDescriptor = fileInputStream.fd
            val fileSize = Os.fstat(fileDescriptor).st_size
            
            // Use APK file name instead of generic "package"
            val sessionStream = session.openWrite(apkFile.name, 0, fileSize)
            
            fileInputStream.copyTo(sessionStream)
            
            // Close in Accrescent's order: file first, then session
            fileInputStream.close()
            session.fsync(sessionStream)
            sessionStream.close()
        }
        
        // Store pending result BEFORE committing - will be completed by InstallResultReceiver
        pendingInstallResults[sessionId] = result
        sessionToPackage[sessionId] = packageName
        Log.d(TAG, "Stored pending result for session $sessionId ($packageName), awaiting broadcast...")
        
        // Create pending intent for installation result
        val intent = Intent(context.applicationContext, InstallResultReceiver::class.java).apply {
            putExtra("sessionId", sessionId)
            putExtra("packageName", packageName)
            putExtra("isUpdate", isUpdate)
        }
        val pendingIntentForBroadcast = PendingIntent.getBroadcast(
            context.applicationContext,
            sessionId,  // Use sessionId as requestCode for uniqueness
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Commit the session - result will be delivered via broadcast to InstallResultReceiver
        // which will call completeInstallResult() to finish the method channel call
        session.commit(pendingIntentForBroadcast.intentSender)
        session.close()
        
        // DO NOT call result.success() here - wait for InstallResultReceiver broadcast
        // The Dart side will block on await until completeInstallResult() is called
    }
    
    private fun launchSystemInstaller(filePath: String, result: Result) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("file://$filePath"), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(intent)
            
            result.success(mapOf(
                "isSuccess" to true,
                "errorMessage" to "",
                "fallbackMethod" to "system_installer"
            ))
        } catch (e: Exception) {
            result.success(mapOf(
                "isSuccess" to false,
                "errorMessage" to "Failed to launch installer: ${e.message}"
            ))
        }
    }

    private fun uninstallApp(packageName: String, result: Result) {
        try {
            // Check if the package is installed
            val pm = context.packageManager
            try {
                pm.getPackageInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                result.success(mapOf(
                    "isSuccess" to false,
                    "errorMessage" to "Package not found: $packageName"
                ))
                return
            }
            
            // Launch system uninstaller
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(intent)
            
            // Poll for package removal since ACTION_DELETE has no callback
            // This runs on a background thread to avoid blocking the main thread
            Thread {
                val timeoutMs = 120_000L  // 2 minute timeout for user to confirm
                val pollIntervalMs = 500L
                val startTime = System.currentTimeMillis()
                
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    try {
                        Thread.sleep(pollIntervalMs)
                        
                        // Check if package is still installed
                        try {
                            pm.getPackageInfo(packageName, 0)
                            // Package still exists, keep polling
                        } catch (e: PackageManager.NameNotFoundException) {
                            // Package removed! Success
                            Log.d(TAG, "Package $packageName successfully uninstalled")
                            android.os.Handler(context.mainLooper).post {
                                result.success(mapOf(
                                    "isSuccess" to true,
                                    "packageName" to packageName
                                ))
                            }
                            return@Thread
                        }
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                
                // Timeout - check one more time then treat as cancelled
                val stillInstalled = try {
                    pm.getPackageInfo(packageName, 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
                
                android.os.Handler(context.mainLooper).post {
                    result.success(mapOf(
                        "isSuccess" to !stillInstalled,
                        "packageName" to packageName,
                        "cancelled" to stillInstalled
                    ))
                }
            }.start()
            
            // DO NOT call result.success() here - wait for polling to complete
            
        } catch (e: Exception) {
            Log.e(TAG, "Uninstall failed for $packageName", e)
            result.success(mapOf(
                "isSuccess" to false,
                "errorMessage" to "Uninstall failed: ${e.message}"
            ))
        }
    }

    private fun launchApp(packageName: String, result: Result) {
        try {
            val pm = context.packageManager
            
            // Check if the package is installed
            try {
                pm.getPackageInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                result.success(mapOf(
                    "isSuccess" to false,
                    "errorMessage" to "Package not found: $packageName"
                ))
                return
            }
            
            // Get the launch intent for the package
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) {
                result.success(mapOf(
                    "isSuccess" to false,
                    "errorMessage" to "No launch activity found for package: $packageName"
                ))
                return
            }
            
            // Add flags to ensure the app launches properly
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            
            // Launch the app
            context.startActivity(launchIntent)
            
            result.success(mapOf(
                "isSuccess" to true,
                "errorMessage" to "",
                "packageName" to packageName
            ))
            
        } catch (e: Exception) {
            Log.e(TAG, "Launch failed for $packageName", e)
            result.success(mapOf(
                "isSuccess" to false,
                "errorMessage" to "Launch failed: ${e.message}"
            ))
        }
    }
    
    /**
     * Launch a pending install prompt when app returns to foreground.
     * Called from Dart after setAppForegroundState(true).
     * 
     * Returns success if:
     * - There's a pending user action intent (stored when app was backgrounded)
     * - OR there's an active session in the system
     */
    private fun retryPendingInstall(packageName: String, result: Result) {
        // First check if we have a stored user action intent
        val pendingIntent = pendingUserActionIntents[packageName]
        if (pendingIntent != null) {
            val deferred = pendingUserActionDeferred[packageName] == true
            if (!deferred) {
                // Prompt was already shown while app was in foreground.
                // Do not re-launch it on resume (prevents double system dialogs).
                result.success(mapOf(
                    "isSuccess" to false,
                    "hasPending" to true,
                    "relaunched" to false,
                    "promptAlreadyShown" to true,
                    "packageName" to packageName
                ))
                return
            }

            Log.d(TAG, "Re-launching deferred user action for $packageName")
            try {
                pendingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(pendingIntent)
                pendingUserActionDeferred[packageName] = false
                result.success(mapOf(
                    "isSuccess" to true,
                    "hasPending" to true,
                    "relaunched" to true,
                    "promptAlreadyShown" to false,
                    "packageName" to packageName
                ))
                return
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch install prompt for $packageName", e)
                // Clear the stale intent
                pendingUserActionIntents.remove(packageName)
                pendingUserActionDeferred.remove(packageName)
            }
        }
        
        // Check if there's a system session waiting
        val existingSession = findExistingSession(packageName)
        if (existingSession != null) {
            Log.d(TAG, "Found system session ${existingSession.sessionId} for $packageName but no user action intent")
            // Session exists but we don't have the intent - user needs to re-trigger install
            result.success(mapOf(
                "isSuccess" to false,
                "hasPending" to true,
                "sessionPending" to true,
                "relaunched" to false,
                "promptAlreadyShown" to false,
                "packageName" to packageName
            ))
        } else {
            // No pending intent and no active session
            result.success(mapOf(
                "isSuccess" to false,
                "hasPending" to false,
                "relaunched" to false,
                "promptAlreadyShown" to false,
                "packageName" to packageName
            ))
        }
    }

    private fun getInstalledApps(includeSystemApps: Boolean): List<Map<String, Any?>> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        val out = mutableListOf<Map<String, Any?>>()
        
        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystemApps && isSystem) continue
            
            val name = appInfo.loadLabel(pm)?.toString()
            val bundleId = pkg.packageName
            val versionName = pkg.versionName
            val versionCode: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }
            
            // Get signature hash
            val signatureHash = try {
                getSignatureHash(bundleId)
            } catch (e: Exception) {
                ""
            }
            
            // Check if we can silently update this package (we are the installer)
            val canSilentlyUpdate = canInstallSilently(bundleId)
            
            out.add(mapOf(
                "name" to name,
                "bundleId" to bundleId,
                "versionName" to versionName,
                "versionCode" to versionCode,
                "signatureHash" to signatureHash,
                "canInstallSilently" to canSilentlyUpdate,
            ))
        }
        return out
    }
    
    private fun getSignatureHash(packageName: String): String {
        val pm = context.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.let { signingInfo ->
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures ?: emptyArray()
        }
        
        if (signatures.isEmpty()) return ""
        
        // Get SHA-256 hash of first signature
        val signature = signatures[0]
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(signature.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

}