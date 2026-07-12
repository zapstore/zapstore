package dev.zapstore.purplequartz

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration

internal fun interface EpochMillisClock {
    fun now(): Long
}

internal interface QueryRefreshCache {
    fun lastRefresh(fingerprint: String): Long?

    fun recordRefresh(fingerprint: String, epochMillis: Long): Boolean
}

internal data class QueryFreshness(
    val isFresh: Boolean,
    val remainingMillis: Long,
)

internal fun QueryRefreshCache.freshness(
    fingerprint: String,
    maxAge: Duration,
    now: Long,
): QueryFreshness {
    val refreshedAt = lastRefresh(fingerprint)
        ?: return QueryFreshness(isFresh = false, remainingMillis = 0)
    if (refreshedAt <= 0 || now < refreshedAt) {
        return QueryFreshness(isFresh = false, remainingMillis = 0)
    }

    val maxAgeMillis = maxOf(1, maxAge.inWholeMilliseconds)
    val age = now - refreshedAt
    val remaining = maxAgeMillis - age
    return QueryFreshness(
        isFresh = remaining > 0,
        remainingMillis = remaining.coerceAtLeast(0),
    )
}

internal class InMemoryQueryRefreshCache : QueryRefreshCache {
    private val entries = mutableMapOf<String, Long>()

    override fun lastRefresh(fingerprint: String): Long? = synchronized(entries) {
        entries[fingerprint]
    }

    override fun recordRefresh(fingerprint: String, epochMillis: Long): Boolean = synchronized(entries) {
        if (epochMillis <= 0) return@synchronized false
        entries[fingerprint] = epochMillis
        true
    }
}

internal class SharedPreferencesQueryRefreshCache private constructor(
    private val preferences: SharedPreferences,
    private val entryPrefix: String,
) : QueryRefreshCache {
    private val lock = Any()

    override fun lastRefresh(fingerprint: String): Long? = runCatching {
        preferences.takeIf { it.contains(entryPrefix + fingerprint) }
            ?.getLong(entryPrefix + fingerprint, 0L)
            ?.takeIf { it > 0 }
    }.getOrNull()

    override fun recordRefresh(fingerprint: String, epochMillis: Long): Boolean {
        if (epochMillis <= 0) return false
        synchronized(lock) {
            val key = entryPrefix + fingerprint
            val currentEntries = preferences.all.asSequence()
                .filter { (entryKey, value) -> entryKey.startsWith(entryPrefix) && value is Long }
                .sortedBy { (_, value) -> value as Long }
                .toList()
            val removals = if (preferences.contains(key)) {
                0
            } else {
                (currentEntries.size - MAX_ENTRIES + 1).coerceAtLeast(0)
            }
            val editor = preferences.edit()
            currentEntries.take(removals).forEach { (entryKey, _) -> editor.remove(entryKey) }
            val committed = editor.putLong(key, epochMillis).commit()
            if (!committed) {
                // commit updates SharedPreferences memory before attempting disk I/O.
                // Remove the optimistic value so this process also fails open.
                preferences.edit().remove(key).apply()
            }
            return committed
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "dev.zapstore.purplequartz.query-refresh-v1"
        private const val MAX_ENTRIES = 1_024

        fun create(
            context: Context,
            databasePath: String,
            databaseExisted: Boolean = File(databasePath).exists(),
        ): SharedPreferencesQueryRefreshCache {
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            val pathHash = sha256(databasePath)
            val generationKey = "database:$pathHash:generation"
            val identityKey = "database:$pathHash:identity"
            val previousGeneration = runCatching { preferences.getString(generationKey, null) }.getOrNull()
            val previousIdentity = runCatching { preferences.getString(identityKey, null) }.getOrNull()
            val currentIdentity = databaseIdentity(databasePath)
            val canReuseGeneration =
                databaseExisted &&
                    currentIdentity != null &&
                    currentIdentity == previousIdentity &&
                    previousGeneration != null
            val generation = previousGeneration?.takeIf { canReuseGeneration } ?: UUID.randomUUID().toString()

            if (!canReuseGeneration) {
                val oldPrefix = "refresh:$pathHash:"
                val editor = preferences.edit()
                    .putString(generationKey, generation)
                    .putString(identityKey, currentIdentity)
                preferences.all.keys.filter { it.startsWith(oldPrefix) }.forEach(editor::remove)
                editor.commit()
            }

            return SharedPreferencesQueryRefreshCache(
                preferences = preferences,
                entryPrefix = "refresh:$pathHash:$generation:",
            )
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private fun databaseIdentity(databasePath: String): String? = runCatching {
            val attributes = Files.readAttributes(File(databasePath).toPath(), BasicFileAttributes::class.java)
            val fileKey = attributes.fileKey()?.toString().orEmpty()
            val createdAt = attributes.creationTime().toMillis()
            if (fileKey.isEmpty() && createdAt <= 0) return@runCatching null
            sha256("$fileKey:$createdAt")
        }.getOrNull()
    }
}
