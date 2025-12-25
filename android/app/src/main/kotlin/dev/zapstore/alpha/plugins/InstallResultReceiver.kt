package dev.zapstore.alpha.plugins

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

private const val TAG = "InstallResultReceiver"

/**
 * BroadcastReceiver for APK installation results.
 * 
 * Translates Android PackageInstaller status codes to simple status events
 * that are emitted to Dart via EventChannel.
 * 
 * This receiver is stateless - it simply maps broadcasts to events.
 * All state management happens in AndroidPackageManagerPlugin.
 */
class InstallResultReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) 
            ?: intent.getStringExtra("packageName") 
            ?: ""
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val isUpdate = intent.getBooleanExtra("isUpdate", false)
        
        Log.d(TAG, "Received: status=$status, package=$packageName, session=$sessionId, msg=$message")
        
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // User confirmation required - extract the dialog intent
                val confirmIntent = extractConfirmIntent(intent)
                
                AndroidPackageManagerPlugin.onInstallResult(
                    sessionId = sessionId,
                    status = InstallStatus.PENDING_USER_ACTION,
                    packageName = packageName,
                    isUpdate = isUpdate,
                    confirmIntent = confirmIntent
                )
            }
            
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "Installation successful: $packageName")
                AndroidPackageManagerPlugin.onInstallResult(
                    sessionId = sessionId,
                    status = InstallStatus.SUCCESS,
                    packageName = packageName,
                    isUpdate = isUpdate
                )
            }
            
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.d(TAG, "Installation cancelled: $packageName")
                AndroidPackageManagerPlugin.onInstallResult(
                    sessionId = sessionId,
                    status = InstallStatus.CANCELLED,
                    packageName = packageName,
                    message = "Installation was cancelled"
                )
            }
            
            PackageInstaller.STATUS_FAILURE -> {
                Log.w(TAG, "Installation failed: $packageName - $message")
                // Try to determine error code from message content
                val errorCode = categorizeFailure(message)
                AndroidPackageManagerPlugin.onInstallResult(
                    sessionId = sessionId,
                    status = InstallStatus.FAILED,
                    packageName = packageName,
                    message = message ?: "Installation failed",
                    errorCode = errorCode
                )
            }
            
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Log.w(TAG, "Installation blocked: $packageName")
                AndroidPackageManagerPlugin.onInstallResult(
                    sessionId = sessionId,
                    status = InstallStatus.FAILED,
                    packageName = packageName,
                    message = message ?: "Installation blocked by device policy",
                    errorCode = ErrorCode.BLOCKED
                )
            }
            
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Log.w(TAG, "Installation conflict: $packageName")
                // Conflict typically means signature mismatch
                AndroidPackageManagerPlugin.onInstallResult(
                    sessionId = sessionId,
                    status = InstallStatus.FAILED,
                    packageName = packageName,
                    message = message ?: "Installation conflicts with existing package",
                    errorCode = ErrorCode.CERT_MISMATCH
                )
            }
            
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Log.w(TAG, "Incompatible package: $packageName")
                AndroidPackageManagerPlugin.onInstallResult(
                    sessionId = sessionId,
                    status = InstallStatus.FAILED,
                    packageName = packageName,
                    message = message ?: "Package is incompatible with this device",
                    errorCode = ErrorCode.INCOMPATIBLE
                )
            }
            
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Log.w(TAG, "Invalid package: $packageName")
                AndroidPackageManagerPlugin.onInstallResult(
                    sessionId = sessionId,
                    status = InstallStatus.FAILED,
                    packageName = packageName,
                    message = message ?: "Invalid installation package",
                    errorCode = ErrorCode.INVALID_FILE
                )
            }
            
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.w(TAG, "Storage failure: $packageName")
                AndroidPackageManagerPlugin.onInstallResult(
                    sessionId = sessionId,
                    status = InstallStatus.FAILED,
                    packageName = packageName,
                    message = message ?: "Insufficient storage space",
                    errorCode = ErrorCode.INSUFFICIENT_STORAGE
                )
            }
            
            else -> {
                Log.w(TAG, "Unknown status $status: $packageName")
                AndroidPackageManagerPlugin.onInstallResult(
                    sessionId = sessionId,
                    status = InstallStatus.FAILED,
                    packageName = packageName,
                    message = message ?: "Installation failed (status: $status)",
                    errorCode = ErrorCode.INSTALL_FAILED
                )
            }
        }
    }
    
    /**
     * Categorize a generic failure message to determine the error code.
     */
    private fun categorizeFailure(message: String?): String {
        if (message == null) return ErrorCode.INSTALL_FAILED
        val lower = message.lowercase()
        return when {
            lower.contains("signature") || 
            lower.contains("certificate") || 
            lower.contains("update_incompatible") -> ErrorCode.CERT_MISMATCH
            lower.contains("storage") || lower.contains("space") -> ErrorCode.INSUFFICIENT_STORAGE
            lower.contains("permission") || lower.contains("denied") -> ErrorCode.PERMISSION_DENIED
            else -> ErrorCode.INSTALL_FAILED
        }
    }
    
    /**
     * Extract the confirmation dialog intent from a PENDING_USER_ACTION broadcast.
     */
    private fun extractConfirmIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }
}
