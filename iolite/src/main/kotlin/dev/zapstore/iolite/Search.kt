package dev.zapstore.iolite

import java.util.PriorityQueue

/** leaf-ir-v1 document and query width. */
const val VECTOR_DIMS = 768

/** Queries are bounded to the same size as `POST /search`. */
const val MAX_QUERY_BYTES = 256

/**
 * int8 dot of two leaf-ir-v1 vectors. Cosine is about `dot * (0.3/127)^2`.
 * A maps query is about 100000 against a maps listing and about 33000 against a password manager.
 */
internal const val MIN_VECTOR_DOT = 57_348

/** About cosine 0.45, so a close vector can outrank a name that merely shares a prefix. */
internal const val PREFIX_BOOST = 80_645

/** Larger than any int8 dot, so an exact name or app ID stays above semantic hits. */
internal const val EXACT_BOOST = 20_000_000

/** About cosine 0.10. Reorders neighbors and stays under [PREFIX_BOOST]. */
internal const val FACT_SCORE = 18_000

/** About cosine 0.30. Stronger than [FACT_SCORE] and still under [PREFIX_BOOST]. */
internal const val HARD_FACT_SCORE = 54_000

/** Trimmed, whitespace-collapsed, lowercased, and cut to [MAX_QUERY_BYTES]. */
fun normalizeSearchQuery(query: String): String {
    val folded = foldSearchText(query)
    val bytes = folded.encodeToByteArray()
    if (bytes.size <= MAX_QUERY_BYTES) return folded
    var end = MAX_QUERY_BYTES
    while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
    return bytes.decodeToString(0, end).trimEnd()
}

internal fun foldSearchText(value: String): String {
    val out = StringBuilder(value.length)
    var pendingSpace = false
    for (ch in value.trim()) {
        if (ch.isWhitespace()) {
            if (out.isNotEmpty()) pendingSpace = true
            continue
        }
        if (pendingSpace) {
            out.append(' ')
            pendingSpace = false
        }
        out.append(ch.lowercaseChar())
    }
    return out.toString()
}

/**
 * Exact and prefix name or app-ID hits stay in the list without an vector.
 * Other listings are included only when [queryVector] clears [MIN_VECTOR_DOT].
 * [admitAll] keeps every row, used when a hard fact filter already chose the set.
 * Keeps ids only, and at most [limit] of them, so the caller can load full rows for the survivors.
 */
internal class SearchRanker(
    private val query: String,
    queryVector: ByteArray?,
    private val limit: Int?,
    private val boost: Int = 0,
    private val soft: Int = 0,
    private val penalty: Int = 0,
    private val admitAll: Boolean = false,
) {
    private val vector = queryVector?.takeIf { it.size == VECTOR_DIMS }
    private val kept = ArrayList<Ranked>()
    private val worstFirst = limit?.takeIf { it > 0 }?.let { PriorityQueue(it, dropFirst) }

    fun consider(id: ByteArray, appId: String, name: String, docVector: ByteArray?, factBits: Int) {
        if (limit == 0) return
        val score = score(name, appId, docVector, factBits) ?: return
        val heap = worstFirst
        if (heap == null) {
            kept += Ranked(id, appId, score)
            return
        }
        if (heap.size < limit!!) {
            heap += Ranked(id, appId, score)
            return
        }
        val worst = checkNotNull(heap.peek())
        if (score > worst.score || (score == worst.score && appId < worst.appId)) {
            heap.poll()
            heap += Ranked(id, appId, score)
        }
    }

    fun ids(): List<ByteArray> {
        val ranked = worstFirst?.toMutableList() ?: kept
        ranked.sortWith(bestFirst)
        return ranked.map { it.id }
    }

    private fun score(name: String, appId: String, docVector: ByteArray?, factBits: Int): Int? {
        val text = textBoost(query, name, appId)
        val dot = vector?.let { left ->
            docVector?.takeIf { it.size == VECTOR_DIMS }?.let { vectorDot(left, it) }
        }
        if (!admitAll && text == 0 && (dot == null || dot < MIN_VECTOR_DOT)) return null
        return text + (dot ?: 0) + factScore(factBits, boost, soft, penalty)
    }
}

internal fun factScore(bits: Int, boost: Int, soft: Int, penalty: Int): Int =
    (bits and boost).countOneBits() * HARD_FACT_SCORE +
        (bits and soft).countOneBits() * FACT_SCORE -
        (bits and penalty).countOneBits() * FACT_SCORE

private class Ranked(val id: ByteArray, val appId: String, val score: Int)

private val bestFirst = compareByDescending<Ranked> { it.score }.thenBy { it.appId }

/** Lowest score first, and the larger app id first when scores tie, so the queue head is the first to drop. */
private val dropFirst = compareBy<Ranked> { it.score }.thenByDescending { it.appId }

private fun textBoost(query: String, name: String, appId: String): Int {
    if (query.isEmpty()) return 0
    val foldedName = foldSearchText(name)
    val foldedId = appId.lowercase()
    return when {
        query == foldedName || query == foldedId -> EXACT_BOOST
        foldedName.startsWith(query) || foldedId.startsWith(query) -> PREFIX_BOOST
        else -> 0
    }
}

private fun vectorDot(left: ByteArray, right: ByteArray): Int {
    var sum = 0
    for (i in 0 until VECTOR_DIMS) {
        sum += left[i].toInt() * right[i].toInt()
    }
    return sum
}
