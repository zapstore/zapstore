package dev.zapstore.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.WorkManager
import java.io.File

/**
 * One-time move from the Flutter app (`zapstore-device-nsec`). Flutter 1.1.x keeps its secrets in
 * flutter_secure_storage 9.x (EncryptedSharedPreferences `FlutterSecureStorage`, default master key).
 * Runs before the device key is read. Returns true when a Flutter device key was carried over.
 */
object FlutterMigration {
    /** Moves the secrets. Small and synchronous: the device key is needed before anything else starts. */
    fun run(context: Context, secrets: SecretStore): Boolean {
        val state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (state.getBoolean(DONE, false)) return state.getBoolean(CARRIED_KEY, false)
        val carried = carrySecrets(context, secrets) ?: return false
        state.edit()
            .putBoolean(DONE, true)
            .putBoolean(CARRIED_KEY, carried)
            .putBoolean(FETCH_PENDING, carried)
            .commit()
        return carried
    }

    /**
     * True once after a Flutter device key was carried: its settings and lists are still on the relay.
     * Later starts return false. Onboarding still sees [run], which stays true.
     */
    fun takeFetchPending(context: Context): Boolean {
        val state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (!state.getBoolean(FETCH_PENDING, false)) return false
        state.edit().remove(FETCH_PENDING).commit()
        return true
    }

    /** Deletes Flutter's files and cancels its WorkManager jobs. Disk work: call off the main thread. */
    fun cleanUp(context: Context) {
        val state = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (state.getBoolean(CLEANED, false)) return
        deleteLeftovers(context)
        runCatching { cancelFlutterWork(context) }.onFailure { AppLog.w(TAG, "WorkManager cancel failed", it) }
        state.edit().putBoolean(CLEANED, true).apply()
    }

    /** Null when Flutter's storage is there but could not be read, so the next start tries again. */
    private fun carrySecrets(context: Context, secrets: SecretStore): Boolean? {
        val file = File(context.dataDir, "shared_prefs/$FLUTTER_PREFS.xml")
        if (!file.isFile) return false
        val values = runCatching {
            @Suppress("DEPRECATION")
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            @Suppress("DEPRECATION")
            val prefs = EncryptedSharedPreferences.create(
                context,
                FLUTTER_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            listOf(SecretStore.DEVICE_KEY, SecretStore.AMBER_PUBKEY, SecretStore.NWC)
                .associateWith { prefs.getString("${KEY_PREFIX}_$it", null) }
        }.getOrElse {
            // Leave the file: a key we could not read is still the user's identity.
            AppLog.e(TAG, "Flutter secure storage unreadable", it)
            return null
        }
        val deviceKey = values[SecretStore.DEVICE_KEY]?.trim()?.lowercase()?.takeIf { HEX_KEY.matches(it) }
        deviceKey?.let { secrets.put(SecretStore.DEVICE_KEY, it) }
        values[SecretStore.AMBER_PUBKEY]?.trim()?.lowercase()?.takeIf { HEX_KEY.matches(it) }
            ?.let { secrets.put(SecretStore.AMBER_PUBKEY, it) }
        values[SecretStore.NWC]?.trim()?.takeIf { it.startsWith("nostr+walletconnect://") }
            ?.let { secrets.put(SecretStore.NWC, it) }
        if (deviceKey == null) {
            AppLog.e(TAG, "Flutter secure storage has no readable device key")
            return false
        }
        context.deleteSharedPreferences(FLUTTER_PREFS)
        return true
    }

    private fun deleteLeftovers(context: Context) {
        val files = context.filesDir
        listOf("zapstore.db", "zapstore.db-wal", "zapstore.db-shm", "zapstore.db-journal",
            "installed_packages_snapshot.json", "background_pending_installs.json", ".clear_on_restart")
            .forEach { File(files, it).delete() }
        listOf(File(files, "logs"), File(files, "background_updates"), File(context.cacheDir, "flutter_background_downloader"))
            .forEach { it.deleteRecursively() }
        context.deleteSharedPreferences("FlutterSharedPreferences")
    }

    private fun cancelFlutterWork(context: Context) {
        val work = WorkManager.getInstance(context)
        FLUTTER_WORK.forEach(work::cancelUniqueWork)
        work.pruneWork()
    }

    private const val TAG = "FlutterMigration"
    private const val STATE_PREFS = "migration"
    private const val DONE = "flutter_done"
    private const val CARRIED_KEY = "flutter_key"
    private const val FETCH_PENDING = "flutter_fetch"
    private const val CLEANED = "flutter_cleaned"
    private const val FLUTTER_PREFS = "FlutterSecureStorage"
    private const val KEY_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg"
    private val HEX_KEY = Regex("^[0-9a-f]{64}$")
    private val FLUTTER_WORK = listOf("backgroundUpdateCheck", "backgroundAutoUpdate", "backgroundAutoUpdateImmediate", "weeklyCleanup")
}
