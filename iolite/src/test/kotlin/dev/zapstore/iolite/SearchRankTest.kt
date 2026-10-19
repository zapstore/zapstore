package dev.zapstore.iolite

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRankTest {
    @Test
    fun wholeNamePrefixOutranksAWordMatch() {
        val ranker = SearchRanker("notes", null, null)
        ranker.consider(id("com.word"), "com.word", "Trail Notes", null, 0)
        ranker.consider(id("com.prefix"), "com.prefix", "Notes Extra", null, 0)
        assertEquals(listOf("com.prefix", "com.word"), ids(ranker))
    }

    @Test
    fun oneEditIsATextHit() {
        val ranker = SearchRanker("bitwardan", null, null)
        ranker.consider(id("com.edit"), "com.edit", "Bitwarden", null, 0)
        assertEquals(listOf("com.edit"), ids(ranker))
    }

    @Test
    fun oneCharacterIsNotAWordPrefix() {
        val ranker = SearchRanker("a notes", null, null)
        ranker.consider(id("org.example.trail"), "org.example.trail", "Trail Notes", null, 0)
        assertEquals(emptyList<String>(), ids(ranker))
    }

    @Test
    fun keywordCountsATrailingSEitherWayAndAtMostTwoWords() {
        val neighbor = ByteArray(VECTOR_DIMS) { 12 }
        val capped = SearchRanker("alpha beta gamma", neighbor, null)
        capped.consider(id("com.keyword"), "com.keyword", "Other", null, 0, listOf("alpha beta gamma"))
        capped.consider(id("com.close"), "com.close", "Notes", neighbor, 0)
        assertEquals(listOf("com.close", "com.keyword"), ids(capped))

        val plural = SearchRanker("podcasts", null, null)
        plural.consider(id("com.pod"), "com.pod", "Show", null, 0, listOf("podcast"))
        assertEquals(listOf("com.pod"), ids(plural))

        val stem = SearchRanker("podcast", null, null)
        stem.consider(id("com.pod"), "com.pod", "Show", null, 0, listOf("podcasts"))
        assertEquals(listOf("com.pod"), ids(stem))
    }
}

private fun id(name: String): ByteArray = name.encodeToByteArray()

private fun ids(ranker: SearchRanker): List<String> = ranker.ids().map { it.decodeToString() }
