package dev.zapstore.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileIconCacheTest {
    @Test
    fun `profile icon cache key is stable for profile cache period`() {
        val url = "https://cdn.zapstore.dev/p/example.webp"
        val start = 10 * PROFILE_CACHE_DURATION.inWholeMilliseconds

        assertEquals(
            profileIconCacheKey(url, start),
            profileIconCacheKey(url, start + PROFILE_CACHE_DURATION.inWholeMilliseconds - 1),
        )
        assertNotEquals(
            profileIconCacheKey(url, start),
            profileIconCacheKey(url, start + PROFILE_CACHE_DURATION.inWholeMilliseconds),
        )
    }
}
