package dev.zapstore.app

import android.content.Context
import android.os.Build
import android.util.Log
import dev.zapstore.iolite.Crypto
import dev.zapstore.iolite.GiftWrap
import dev.zapstore.iolite.Hex
import dev.zapstore.iolite.Iolite
import dev.zapstore.iolite.Kinds
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Anonymous bug reports: crashes and errors the app logs. Each is queued on disk and sent on a
 * later start as a NIP-17 message gift-wrapped to the catalog key, from a one-time key, so the
 * relay sees neither the sender nor the text. Off in device state: nothing is queued, and what
 * is queued is deleted.
 */
class BugReports(
    context: Context,
    private val iolite: Iolite,
    private val enabled: () -> Boolean,
) {
    private val dir = File(context.filesDir, "diagnostics/reports")
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Queues a logged error. The same error is queued once a day, and at most [DAILY_ERRORS] a day. */
    fun record(tag: String, message: String, error: Throwable?) {
        if (!enabled()) return
        val fingerprint = fingerprint(tag, message, error)
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        synchronized(this) {
            val seen = if (prefs.getString(KEY_DAY, null) == today) prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty() else emptySet()
            if (fingerprint in seen || seen.size >= DAILY_ERRORS) return
            prefs.edit().putString(KEY_DAY, today).putStringSet(KEY_SEEN, seen + fingerprint).apply()
        }
        val trace = error?.let(Log::getStackTraceString).orEmpty()
        dir.mkdirs()
        File(dir, "${System.currentTimeMillis()}.txt").writeText("$tag: $message\n$trace")
    }

    /** Sends what is queued, oldest first, and stops at the first failure so the rest waits. */
    suspend fun sendPending() {
        val pending = AppLog.pendingCrashes().map { it to TYPE_CRASH } +
            dir.listFiles().orEmpty().sortedBy { it.name }.map { it to TYPE_ERROR }
        if (pending.isEmpty()) return
        if (!enabled()) {
            pending.forEach { (file, _) -> file.delete() }
            return
        }
        val recipient = iolite.defaultCatalog().manifestPubkey ?: return
        for ((file, type) in pending.take(MAX_PER_START)) {
            val sent = runCatching { send(recipient, type, file.readText().take(MAX_CHARS)) }
                .onFailure { AppLog.w(TAG, "bug report not sent", it) }
                .getOrDefault(false)
            if (!sent) {
                AppLog.w(TAG, "bug report not accepted; kept for the next start")
                return
            }
            file.delete()
        }
    }

    private suspend fun send(recipient: String, type: String, body: String): Boolean {
        val content = buildString {
            appendLine("Zapstore bug report")
            appendLine("type: $type")
            appendLine("app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("android: ${Build.VERSION.SDK_INT}")
            appendLine()
            append(body)
        }
        val wrap = GiftWrap.wrap(
            rumorKind = Kinds.ChatMessage,
            rumorTags = listOf(listOf("p", recipient), listOf("subject", SUBJECT)),
            content = content,
            recipient = recipient,
            sender = Crypto.randomBytes(32),
            now = System.currentTimeMillis() / 1_000,
            difficulty = DIFFICULTY,
        )
        return iolite.sendUnstored(wrap, setOf(AppConfig.catalogRelay))
    }

    private fun fingerprint(tag: String, message: String, error: Throwable?): String {
        val frame = error?.stackTrace?.firstOrNull()?.toString().orEmpty()
        val key = "$tag|${error?.javaClass?.name ?: message}|$frame"
        return Hex.encode(Crypto.sha256(key.toByteArray())).take(16)
    }

    companion object {
        private const val TAG = "BugReports"
        private const val PREFS = "bug_reports"
        private const val KEY_DAY = "day"
        private const val KEY_SEEN = "seen"
        private const val TYPE_CRASH = "crash"
        private const val TYPE_ERROR = "error"
        private const val SUBJECT = "Zapstore bug report"
        private const val DAILY_ERRORS = 5
        private const val MAX_PER_START = 10
        private const val MAX_CHARS = 32_000

        /** NIP-13 bits on the wrap id; the catalog relay requires the same. */
        const val DIFFICULTY = 16
    }
}
