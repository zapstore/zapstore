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

    @Test
    fun aCloseVectorOutranksAGenericKeyword() {
        val query = ByteArray(VECTOR_DIMS).also { it.fill(20, 0, 64) }
        val close = ByteArray(VECTOR_DIMS).also { it.fill(100, 0, 64) }
        val loose = ByteArray(VECTOR_DIMS).also { it.fill(60, 0, 64) }
        val ranker = SearchRanker("offline hiking maps", query, null)
        ranker.consider(id("com.workout"), "com.workout", "Workout", loose, 0, listOf("offline"))
        ranker.consider(id("org.maps"), "org.maps", "Atlas", close, 0)
        assertEquals(listOf("org.maps", "com.workout"), ids(ranker))
    }

    @Test
    fun aCuratedAppOutranksAnEqualNeighborButAdmitsNothing() {
        val neighbor = ByteArray(VECTOR_DIMS) { 12 }
        val ranker = SearchRanker("notes", neighbor, null, curated = setOf("org.curated", "org.absent"))
        ranker.consider(id("com.plain"), "com.plain", "Jotter", neighbor, 0)
        ranker.consider(id("org.curated"), "org.curated", "Scribble", neighbor, 0)
        ranker.consider(id("org.absent"), "org.absent", "Wallet", null, 0)
        assertEquals(listOf("org.curated", "com.plain"), ids(ranker))
    }

    @Test
    fun aNameSeveralAppsShareStopsBeingAnExactMatch() {
        val close = ByteArray(VECTOR_DIMS) { 12 }
        val far = ByteArray(VECTOR_DIMS) { 8 }
        // Shared, "Notes" ranks as a prefix, level with "Notes Tech", so the closer vector decides.
        val shared = SearchRanker("notes", close, null)
        shared.consider(id("com.a"), "com.a", "Notes", far, 0)
        shared.consider(id("com.b"), "com.b", "Notes", far, 0)
        shared.consider(id("com.tech"), "com.tech", "Notes Tech", close, 0)
        assertEquals("com.tech", ids(shared).first())

        val unique = SearchRanker("notes", close, null)
        unique.consider(id("com.a"), "com.a", "Notes", far, 0)
        unique.consider(id("com.tech"), "com.tech", "Notes Tech", close, 0)
        assertEquals("com.a", ids(unique).first())

        val limited = SearchRanker("notes", close, 1)
        limited.consider(id("com.a"), "com.a", "Notes", far, 0)
        limited.consider(id("com.b"), "com.b", "Notes", far, 0)
        limited.consider(id("com.tech"), "com.tech", "Notes Tech", close, 0)
        assertEquals(listOf("com.tech"), ids(limited))

        val byId = SearchRanker("com.a", close, 5)
        byId.consider(id("com.a"), "com.a", "com.a", far, 0)
        byId.consider(id("com.b"), "com.b", "com.a", far, 0)
        byId.consider(id("com.other"), "com.other", "com.a plus", close, 0)
        assertEquals("com.a", ids(byId).first())
    }
}

private fun id(name: String): ByteArray = name.encodeToByteArray()

private fun ids(ranker: SearchRanker): List<String> = ranker.ids().map { it.decodeToString() }
