package dev.zapstore.iolite

/**
 * A fact the query parser took out of the text.
 * The app chooses the chip wording. These names are not display copy.
 */
enum class SearchFact {
    SourceAvailable,
    GoogleServices,
    WorksOffline,
}

/**
 * Query phrases for the searchable facts.
 * Wording here is the search contract. Pill and permission labels live in the app.
 * "offline" stands alone. "source available", its alias "open source", and "no google services" match
 * only as a phrase. A word shared with the opposite phrase is not a keyword, so "source" alone selects nothing.
 */
internal class SearchablePill(
    val key: String,
    val bit: Int,
    val fact: SearchFact,
    val words: List<String>,
    val keywords: List<String>,
    val phraseOnly: Boolean,
    val penalty: Boolean,
)

internal val searchablePills: List<SearchablePill> = listOf(
    SearchablePill(
        key = "google_services",
        bit = FACT_GOOGLE,
        fact = SearchFact.GoogleServices,
        words = listOf("no", "google", "services"),
        keywords = emptyList(),
        phraseOnly = true,
        penalty = true,
    ),
    SearchablePill(
        key = "offline_capable",
        bit = FACT_OFFLINE,
        fact = SearchFact.WorksOffline,
        words = listOf("works", "offline"),
        keywords = listOf("offline"),
        phraseOnly = false,
        penalty = false,
    ),
    SearchablePill(
        key = SOURCE_AVAILABLE,
        bit = FACT_SOURCE_AVAILABLE,
        fact = SearchFact.SourceAvailable,
        words = listOf("source", "available"),
        keywords = emptyList(),
        phraseOnly = true,
        penalty = false,
    ),
    SearchablePill(
        key = "$SOURCE_AVAILABLE:alias",
        bit = FACT_SOURCE_AVAILABLE,
        fact = SearchFact.SourceAvailable,
        words = listOf("open", "source"),
        keywords = emptyList(),
        phraseOnly = true,
        penalty = false,
    ),
)

/** The bit this fact must have set ([AppFilter.hard]) and the bit it must have clear ([AppFilter.exclude]). */
val SearchFact.hardBit: Int
    get() = when (this) {
        SearchFact.SourceAvailable -> FACT_SOURCE_AVAILABLE
        SearchFact.WorksOffline -> FACT_OFFLINE
        SearchFact.GoogleServices -> 0
    }

val SearchFact.excludeBit: Int
    get() = if (this == SearchFact.GoogleServices) FACT_GOOGLE else 0
