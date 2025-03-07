package dev.zapstore.app.plugins.silent_install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

private const val TAG = "InstallResultReceiver"

/**
 * BroadcastReceiver to handle APK installation results
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "dev.zapstore.app.plugins.silent_install.SESSION_API_PACKAGE_INSTALLED" -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

                // Log installation results
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // This should not happen with USER_ACTION_NOT_REQUIRED, but handle just in case
                        Log.d(TAG, "User action required: $message")
                        
                        // Try to handle the user confirmation dialog if needed
                        val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirmIntent != null) {
                            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                context.startActivity(confirmIntent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start confirmation activity", e)
                            }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        Log.d(TAG, "Installation succeeded for $packageName")
                    }
                    else -> {
                        Log.e(TAG, "Installation failed: $status - $message")
                    }
                }
            }
        }
    }
}