package dev.zapstore.alpha.plugins

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

private const val TAG = "InstallResultReceiver"

/**
 * BroadcastReceiver to handle APK installation results.
 * 
 * This receiver completes the pending method channel Result stored in 
 * AndroidPackageManagerPlugin, allowing Dart's await to finish.
 * 
 * For STATUS_PENDING_USER_ACTION (installer takeover prompt), we launch the
 * confirmation dialog but DON'T complete the Result yet - we wait for the
 * actual success/failure that follows after user confirms/cancels.
 */
class InstallResultReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) 
            ?: intent.getStringExtra("packageName") 
            ?: "unknown"
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val isUpdate = intent.getBooleanExtra("isUpdate", false)

        Log.d(TAG, "Install result: status=$status, package=$packageName, sessionId=$sessionId, message=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.d(TAG, "User confirmation required for $packageName (installer takeover)")
                
                // Launch confirmation dialog - user needs to approve
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    // Store the intent so it can be re-launched if app is backgrounded
                    AndroidPackageManagerPlugin.storePendingUserActionIntent(packageName, confirmIntent)
                    
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to launch confirmation dialog", e)
                        showUserActionNotification(context, sessionId, confirmIntent, packageName)
                    }
                }
                
                // DON'T complete the Result here - wait for the actual success/failure
                // that will come after user confirms or cancels the dialog.
                // The same sessionId will be used for the final result.
            }
            
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "Installation successful: $packageName (sessionId=$sessionId)")
                // Complete the pending method channel result
                AndroidPackageManagerPlugin.completeInstallResult(sessionId, mapOf(
                    "isSuccess" to true,
                    "errorMessage" to "",
                    "packageName" to packageName,
                    "isUpdate" to isUpdate
                ))
            }
            
            PackageInstaller.STATUS_FAILURE -> {
                Log.w(TAG, "Installation failed: $packageName - $message (sessionId=$sessionId)")
                AndroidPackageManagerPlugin.completeInstallResult(sessionId, mapOf(
                    "isSuccess" to false,
                    "errorMessage" to (message ?: "Installation failed"),
                    "packageName" to packageName
                ))
            }
            
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.d(TAG, "Installation cancelled: $packageName (sessionId=$sessionId)")
                AndroidPackageManagerPlugin.completeInstallResult(sessionId, mapOf(
                    "isSuccess" to false,
                    "errorMessage" to "Installation was cancelled by user",
                    "packageName" to packageName,
                    "cancelled" to true
                ))
            }
            
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Log.w(TAG, "Installation blocked: $packageName (sessionId=$sessionId)")
                AndroidPackageManagerPlugin.completeInstallResult(sessionId, mapOf(
                    "isSuccess" to false,
                    "errorMessage" to (message ?: "Installation blocked by device policy"),
                    "packageName" to packageName
                ))
            }
            
            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Log.w(TAG, "Installation conflict: $packageName (sessionId=$sessionId)")
                AndroidPackageManagerPlugin.completeInstallResult(sessionId, mapOf(
                    "isSuccess" to false,
                    "errorMessage" to (message ?: "Installation conflicts with existing package"),
                    "packageName" to packageName
                ))
            }
            
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Log.w(TAG, "Installation incompatible: $packageName (sessionId=$sessionId)")
                AndroidPackageManagerPlugin.completeInstallResult(sessionId, mapOf(
                    "isSuccess" to false,
                    "errorMessage" to (message ?: "Package is incompatible with this device"),
                    "packageName" to packageName
                ))
            }
            
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Log.w(TAG, "Installation invalid: $packageName (sessionId=$sessionId)")
                AndroidPackageManagerPlugin.completeInstallResult(sessionId, mapOf(
                    "isSuccess" to false,
                    "errorMessage" to (message ?: "Invalid installation package"),
                    "packageName" to packageName
                ))
            }
            
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.w(TAG, "Installation failed - storage: $packageName (sessionId=$sessionId)")
                AndroidPackageManagerPlugin.completeInstallResult(sessionId, mapOf(
                    "isSuccess" to false,
                    "errorMessage" to (message ?: "Insufficient storage space"),
                    "packageName" to packageName
                ))
            }
            
            else -> {
                Log.w(TAG, "Unknown installation status $status: $packageName (sessionId=$sessionId)")
                AndroidPackageManagerPlugin.completeInstallResult(sessionId, mapOf(
                    "isSuccess" to false,
                    "errorMessage" to (message ?: "Installation failed with status $status"),
                    "packageName" to packageName
                ))
            }
        }
    }
    
    private fun showUserActionNotification(
        context: Context, 
        sessionId: Int, 
        confirmIntent: Intent, 
        packageName: String
    ) {
        // Check notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        
        val notificationManager = context.getSystemService<NotificationManager>()
        if (notificationManager?.areNotificationsEnabled() == true) {
            val pendingIntent = PendingIntent.getActivity(
                context,
                sessionId,
                confirmIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )
            
            val notification = NotificationCompat.Builder(context, "install_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Installation requires confirmation")
                .setContentText("Tap to continue installing $packageName")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            try {
                notificationManager.notify(sessionId, notification)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show notification", e)
            }
        }
    }
}
