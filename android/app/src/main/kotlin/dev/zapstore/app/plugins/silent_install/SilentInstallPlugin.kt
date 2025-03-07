package dev.zapstore.app.plugins.silent_install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.FileInputStream
import java.io.IOException

private const val TAG = "SilentInstallPlugin"

/** Plugin that handles silent app installation */
class SilentInstallPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var context: Context

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "silent_install_plugin")
        channel.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "install" -> {
                val filePath = call.argument<String>("filePath")
                if (filePath == null) {
                    result.error("MISSING_ARGUMENT", "File path is required", null)
                    return
                }
                installApk(filePath, result)
            }
            "canInstallSilently" -> {
                result.success(canInstallSilently())
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun canInstallSilently(): Boolean {
        // Check if we are running on Android 11 (API 30) or higher
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    private fun installApk(filePath: String, result: Result) {
        val file = File(filePath)
        if (!file.exists()) {
            result.error("FILE_NOT_FOUND", "The APK file was not found", null)
            return
        }

        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            
            // Set user action not required if running on Android 12 (API 31) or higher
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            // For Android 13 (API 33) and above, set package source to STORE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            val inputStream = FileInputStream(file)
            val outputStream = session.openWrite("package", 0, file.length())
            
            val buffer = ByteArray(65536)
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            session.fsync(outputStream)
            outputStream.close()
            inputStream.close()

            // Create a broadcast intent for installation status
            val intent = Intent(context, InstallResultReceiver::class.java)
            intent.action = "dev.zapstore.app.plugins.silent_install.SESSION_API_PACKAGE_INSTALLED"

            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            } else {
                PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            // Commit the session
            session.commit(pendingIntent.intentSender)
            
            // Since we don't know if the installation was successful yet, 
            // we return a success result for now. The actual installation result
            // will be handled by the broadcast receiver.
            result.success(mapOf(
                "isSuccess" to true,
                "errorMessage" to ""
            ))
            
        } catch (e: IOException) {
            Log.e(TAG, "Installation error: ", e)
            result.success(mapOf(
                "isSuccess" to false,
                "errorMessage" to "Installation failed: ${e.message}"
            ))
        } catch (e: SecurityException) {
            Log.e(TAG, "Security error: ", e)
            result.success(mapOf(
                "isSuccess" to false,
                "errorMessage" to "Security exception: ${e.message}"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ", e)
            result.success(mapOf(
                "isSuccess" to false,
                "errorMessage" to "Unexpected error: ${e.message}"
            ))
        }
    }
}