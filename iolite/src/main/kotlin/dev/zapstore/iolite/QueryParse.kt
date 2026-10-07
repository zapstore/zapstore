package dev.zapstore.iolite

/** One explicit yes, stored in `apps.fact_bits`. A no leaves the bit clear. */
internal const val FACT_OPEN_SOURCE = 1

internal const val FACT_GOOGLE = 1 shl 1

internal const val FACT_OFFLINE = 1 shl 2

// 1 shl 3 was accountless. Do not reuse it. Old catalogs may still have that bit set.

// 1 shl 4 was tracking. Do not reuse it. Old catalogs may still have that bit set.

// 1 shl 5 was decentralized. Do not reuse it. Old catalogs may still have that bit set.

// 1 shl 6 was ads. Do not reuse it. Old catalogs may still have that bit set.

/** Residual text is what the encoder sees. Masks use the [FACT_OPEN_SOURCE] bits. */
data class ParsedQuery(
    val residual: String,
    val hard: Int,
    val soft: Int,
    val penalty: Int,
    val boost: Int,
    val facts: List<SearchFact>,
)

/** Bits for yes rows of searchable facts. A missing row, a no, and every other fact leave the bit clear. */
internal fun factBits(facts: String): Int {
    val byKey = searchablePills.associateBy { it.key }
    var bits = 0
    for (row in parseFactRows(facts)) {
        if (!row.yes) continue
        bits = bits or (byKey[row.key]?.bit ?: 0)
    }
    return bits
}

/**
 * Pulls fact phrases out of a free-form query.
 * A matched phrase is removed from [ParsedQuery.residual].
 * A phrase raises that fact. "no google services" lowers a google_services yes.
 */
fun parseSearchQuery(raw: String): ParsedQuery {
    val folded = foldSearchText(raw.replace("'", "").replace('-', ' '))
    if (folded.isEmpty()) return ParsedQuery("", 0, 0, 0, 0, emptyList())
    val tokens = folded.split(' ')
    val consumed = BooleanArray(tokens.size)
    val facts = linkedSetOf<SearchFact>()
    var boost = 0
    var penalty = 0
    var index = 0
    while (index < tokens.size) {
        val hit = matchKeyword(tokens, index)
        if (hit != null) {
            facts += hit.label.fact
            if (hit.label.penalty) penalty = penalty or hit.label.bit else boost = boost or hit.label.bit
            consume(consumed, hit.start, hit.length)
            index = hit.start + hit.length
            continue
        }
        index++
    }
    val residual = tokens.filterIndexed { at, _ -> !consumed[at] }.joinToString(" ")
    return ParsedQuery(residual, 0, 0, penalty, boost, facts.toList())
}

private class Hit(val label: SearchablePill, val start: Int, val length: Int)

private fun matchKeyword(tokens: List<String>, index: Int): Hit? {
    var found: Hit? = null
    for (label in searchablePills) {
        val hit = if (label.phraseOnly) {
            if (!fits(tokens, index, label.words)) continue
            Hit(label, index, label.words.size)
        } else {
            val keyword = label.keywords.firstOrNull { tokenMatches(tokens[index], it) } ?: continue
            expand(tokens, index, label, keyword)
        }
        val longer = hit.length > (found?.length ?: 0)
        if (longer) found = hit
    }
    return found
}

private fun fits(tokens: List<String>, start: Int, phrase: List<String>): Boolean {
    if (start + phrase.size > tokens.size) return false
    for (offset in phrase.indices) {
        if (!tokenMatches(tokens[start + offset], phrase[offset])) return false
    }
    return true
}

/** Grows from the keyword across neighboring words of the same yes label. */
private fun expand(tokens: List<String>, index: Int, label: SearchablePill, keyword: String): Hit {
    val at = label.words.indexOfFirst { tokenMatches(keyword, it) }
    if (at < 0) return Hit(label, index, 1)
    var start = index
    var word = at
    while (word > 0 && start > 0 && tokenMatches(tokens[start - 1], label.words[word - 1])) {
        start--
        word--
    }
    var end = index
    word = at
    while (word + 1 < label.words.size && end + 1 < tokens.size && tokenMatches(tokens[end + 1], label.words[word + 1])) {
        end++
        word++
    }
    return Hit(label, start, end - start + 1)
}

private fun consume(consumed: BooleanArray, start: Int, length: Int) {
    for (offset in 0 until length) consumed[start + offset] = true
}

internal fun tokenMatches(query: String, listed: String): Boolean {
    if (query == listed) return true
    val queryStem = pluralStem(query)
    val listedStem = pluralStem(listed)
    if (queryStem == listed || listedStem == query) return true
    return query.length >= 6 && listed.length >= 6 && levenshteinAtMostOne(query, listed)
}

private fun pluralStem(word: String): String? =
    if (word.length >= 5 && word.endsWith('s')) word.dropLast(1) else null

internal fun levenshteinAtMostOne(left: String, right: String): Boolean {
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
