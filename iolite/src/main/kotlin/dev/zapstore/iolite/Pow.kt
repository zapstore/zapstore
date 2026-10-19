package dev.zapstore.iolite

import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** NIP-13 proof of work for device-private events (`device-event-pow`). */
object Pow {
    const val DEVICE_DIFFICULTY = 20

    /** Work the catalog relay requires: every kind 30078, and a kind 30267 with content. */
    fun required(kind: Int, content: String): Int = when {
        kind == Kinds.Preferences -> DEVICE_DIFFICULTY
        kind == Kinds.AppStack && content.isNotEmpty() -> DEVICE_DIFFICULTY
        else -> 0
    }

    fun leadingZeroBits(idHex: String): Int {
        var bits = 0
        for (ch in idHex) {
            val nibble = Character.digit(ch, 16)
            if (nibble == 0) {
                bits += 4
                continue
            }
            return bits + Integer.numberOfLeadingZeros(nibble) - 28
        }
        return bits
    }

    fun meets(event: Event, difficulty: Int): Boolean = leadingZeroBits(event.id) >= difficulty

    /**
     * Returns [tags] with a trailing `["nonce", n, difficulty]` whose event id has at least [difficulty]
     * leading zero bits. An existing nonce tag is replaced. Cancellable.
     */
    suspend fun mine(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        difficulty: Int,
    ): List<List<String>> {
        val base = tags.filter { it.firstOrNull() != "nonce" }
        val marker = "\u0000"
        val template = Event.canonicalBytes(pubkey, createdAt, kind, base + listOf(listOf("nonce", marker, difficulty.toString())), content)
        val needle = "[\"nonce\",\"\\u0000\"".toByteArray()
        val start = lastIndexOf(template, needle).also { check(it >= 0) }
        val valueStart = start + needle.size - "\\u0000\"".length
        val prefix = template.copyOfRange(0, valueStart)
        val suffix = template.copyOfRange(start + needle.size - 1, template.size)
        val head = MessageDigest.getInstance("SHA-256").apply { update(prefix) }
        var nonce = 0L
        while (true) {
            if (nonce and 0xFFFF == 0L) coroutineContext.ensureActive()
            val digest = head.clone() as MessageDigest
            digest.update(nonce.toString().toByteArray())
            digest.update(suffix)
            if (leadingZeroBits(digest.digest()) >= difficulty) {
                return base + listOf(listOf("nonce", nonce.toString(), difficulty.toString()))
            }
            nonce++
        }
    }

    private fun leadingZeroBits(hash: ByteArray): Int {
        var bits = 0
        for (byte in hash) {
            val value = byte.toInt() and 0xFF
            if (value == 0) {
                bits += 8
                continue
            }
            return bits + Integer.numberOfLeadingZeros(value) - 24
        }
        return bits
    }

    private fun lastIndexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in haystack.size - needle.size downTo 0) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
