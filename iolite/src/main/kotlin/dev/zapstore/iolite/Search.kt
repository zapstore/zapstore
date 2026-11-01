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

/** About cosine 0.40. Every query word equals or prefixes a word in the name or the app id. */
internal const val WORD_BOOST = 71_684

/** About cosine 0.20. One query word of 5 or more characters differs by one edit. */
internal const val EDIT_BOOST = 35_842

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

private val FORGE_HOSTS = listOf("github.com/", "gitlab.com/", "codeberg.org/")

/** The normalized repository when [query] is one URL-like token, else null. Such a query matches only on the repository. */
fun repositoryQuery(query: String): String? {
    val token = foldSearchText(query)
    if (token.isEmpty() || ' ' in token) return null
    val url = token.startsWith("http://") || token.startsWith("https://") || FORGE_HOSTS.any(token::startsWith)
    return if (url) normalizeRepositoryUrl(token) else null
}

/** Lowercase host and first two path segments, without scheme, `www.`, `.git`, trailing `/`, query, or fragment. */
fun normalizeRepositoryUrl(url: String): String? {
    val bare = url.trim().lowercase()
        .substringBefore('#').substringBefore('?')
        .removePrefix("https://").removePrefix("http://")
        .removePrefix("www.")
    val parts = bare.split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return parts.take(3).joinToString("/").removeSuffix(".git").ifEmpty { null }
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
 * A name, app-id, or keyword hit stays in the list without a vector.
 * Other listings are included only when [queryVector] clears [MIN_VECTOR_DOT].
 * [admitAll] keeps every row, used when the query is only fact phrases. A hard filter alone does not set it: it narrows text results, never widens them.
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

    fun consider(
        id: ByteArray,
        appId: String,
        name: String,
        docVector: ByteArray?,
        factBits: Int,
        keywords: List<String> = emptyList(),
    ) {
        if (limit == 0) return
        val score = score(name, appId, docVector, factBits, keywords) ?: return
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

    private fun score(name: String, appId: String, docVector: ByteArray?, factBits: Int, keywords: List<String>): Int? {
        val text = textBoost(query, name, appId) + keywordScore(query, keywords)
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
    if (query == foldedName || query == foldedId) return EXACT_BOOST
    if (foldedName.startsWith(query) || foldedId.startsWith(query)) return PREFIX_BOOST
    val words = query.split(' ')
    val nameWords = foldedName.split(' ').filter { it.isNotEmpty() }
    val idWords = foldedId.split('.', '-', '_').filter { it.isNotEmpty() }
    if (covers(words, nameWords) || covers(words, idWords)) return WORD_BOOST
    if (coversWithOneEdit(words, nameWords) || coversWithOneEdit(words, idWords)) return EDIT_BOOST
    return 0
}

/** About cosine 0.30 per matched query word, same as [HARD_FACT_SCORE], and at most two words. */
private fun keywordScore(query: String, keywords: List<String>): Int {
    if (query.isEmpty() || keywords.isEmpty()) return 0
    val words = query.split(' ')
    val used = BooleanArray(words.size)
    var matched = 0
    for (keyword in keywords) {
        val parts = keyword.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) continue
        var start = 0
        while (start + parts.size <= words.size) {
            val hit = parts.indices.all { index ->
                !used[start + index] && keywordWord(words[start + index], parts[index])
            }
            if (!hit) {
                start++
                continue
            }
            for (index in parts.indices) used[start + index] = true
            matched += parts.size
            start += parts.size
        }
    }
    return minOf(matched, 2) * HARD_FACT_SCORE
}

private fun covers(queryWords: List<String>, fieldWords: List<String>): Boolean {
    if (queryWords.isEmpty() || fieldWords.isEmpty()) return false
    return queryWords.all { word -> fieldWords.any { field -> wordEqualsOrPrefix(word, field) } }
}

private fun coversWithOneEdit(queryWords: List<String>, fieldWords: List<String>): Boolean {
    if (queryWords.isEmpty() || fieldWords.isEmpty()) return false
    var edits = 0
    for (word in queryWords) {
        if (fieldWords.any { field -> wordEqualsOrPrefix(word, field) }) continue
        if (edits == 1 || fieldWords.none { field -> oneEdit(word, field) }) return false
        edits++
    }
    return edits == 1
}

private fun wordEqualsOrPrefix(query: String, field: String): Boolean {
    if (query == field) return true
    return query.length > 1 && field.startsWith(query)
}

private fun oneEdit(query: String, field: String): Boolean =
    query.length >= 5 && field.length >= 5 && levenshteinOne(query, field)

private fun keywordWord(query: String, listed: String): Boolean {
    if (query == listed) return true
    if (query.length >= 5 && query.endsWith('s') && query.dropLast(1) == listed) return true
    return listed.length >= 5 && listed.endsWith('s') && listed.dropLast(1) == query
}

private fun levenshteinOne(left: String, right: String): Boolean {
    val delta = left.length - right.length
    if (delta > 1 || delta < -1) return false
    if (delta == 0) {
        var mismatches = 0
        for (index in left.indices) {
            if (left[index] != right[index]) {
                mismatches++
                if (mismatches > 1) return false
            }
        }
        return mismatches == 1
    }
    val shorter = if (left.length < right.length) left else right
    val longer = if (left.length < right.length) right else left
    var i = 0
    var j = 0
    var skipped = false
    while (i < shorter.length && j < longer.length) {
        if (shorter[i] == longer[j]) {
            i++
            j++
        } else if (skipped) {
            return false
        } else {
            skipped = true
            j++
        }
    }
    return true
}

private fun vectorDot(left: ByteArray, right: ByteArray): Int {
    var sum = 0
    for (i in 0 until VECTOR_DIMS) {
        sum += left[i].toInt() * right[i].toInt()
    }
    return sum
}
