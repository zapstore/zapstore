package dev.zapstore.iolite

/** Shortest query that looks for stacks and developers, as for apps. */
private const val MIN_MATCH_QUERY = 2

/** An npub fragment shorter than this is a word, not an address; it would match every developer. */
private const val MIN_NPUB_FRAGMENT = 8

/** Lowercase runs of letters and digits. Apostrophes drop out: "Bob's" is "bobs". */
internal fun searchWords(value: String): List<String> =
    WORD.findAll(foldSearchText(value.replace("'", "").replace("\u2019", ""))).map { it.value }.toList()

private val WORD = Regex("[\\p{L}\\p{N}]+")

/**
 * The stacks of [stacks] whose name or description holds every word of [query] as a whole word ("priv" does
 * not find "privacy"). Best match first: the whole name, the name starting with the words, the words in a
 * row in the name, the words anywhere in the name, then the description. Ties keep the order of [stacks].
 * Curators and the apps inside are not searched. The webapp ranks stacks the same way.
 */
fun searchStacks(stacks: List<StackRecord>, query: String, limit: Int): List<StackRecord> {
    if (foldSearchText(query).length < MIN_MATCH_QUERY) return emptyList()
    val words = searchWords(query).distinct()
    if (words.isEmpty()) return emptyList()
    return stacks.mapNotNull { stack ->
        val name = searchWords(stack.name)
        val rank = stackRank(name, searchWords(stack.description), words)
        if (rank < 0) null else rank to stack
    }.sortedBy { it.first }.take(limit).map { it.second }
}

private fun stackRank(name: List<String>, description: List<String>, words: List<String>): Int = when {
    name.size == words.size && inARow(name, words) -> 0
    words.indices.all { name.getOrNull(it) == words[it] } -> 1
    inARow(name, words) -> 2
    name.containsAll(words) -> 3
    (name + description).containsAll(words) -> 4
    else -> -1
}

private fun inARow(text: List<String>, words: List<String>): Boolean =
    (0..text.size - words.size).any { at -> words.indices.all { text[at + it] == words[it] } }

/**
 * How well a developer matches [needle], lower first; -1 is no match. The whole name, the start of the name,
 * the start of a word in it, anywhere in it, then the npub. [names] are the profile name and display name.
 */
internal fun developerRank(names: List<String>, npub: String, needle: String): Int {
    val folded = names.map(::foldSearchText).filter { it.isNotEmpty() }
    return when {
        folded.any { it == needle } -> 0
        folded.any { it.startsWith(needle) } -> 1
        folded.any { name -> name.split(NOT_WORD).any { it.startsWith(needle) } } -> 2
        folded.any { it.contains(needle) } -> 3
        needle.length >= MIN_NPUB_FRAGMENT && npub.contains(needle) -> 4
        else -> -1
    }
}

private val NOT_WORD = Regex("[\\s._-]+")
