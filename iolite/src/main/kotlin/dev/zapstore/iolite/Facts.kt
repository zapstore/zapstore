package dev.zapstore.iolite

/**
 * A fact the query parser took out of the text.
 * The app chooses the chip wording. These names are not display copy.
 */
enum class SearchFact {
    OpenSource,
    GoogleServices,
    WorksOffline,
}

/**
 * Query phrases for the searchable facts.
 * Wording here is the search contract. Pill and permission labels live in the app.
 * "offline" stands alone. "open source" and "no google services" match only as a phrase.
 * A word shared with the opposite phrase is not a keyword, so "source" does not select open source.
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
        key = "open_source",
        bit = FACT_OPEN_SOURCE,
        fact = SearchFact.OpenSource,
        words = listOf("open", "source"),
        keywords = emptyList(),
        phraseOnly = true,
        penalty = false,
    ),
)
