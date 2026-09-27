package dev.zapstore.app.components

import dev.zapstore.app.AppConfig
import dev.zapstore.app.profileCdnUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileImageTest {
    @Test
    fun profileCdnUrlUsesBarePubkeyFilename() {
        val pubkey = "78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d"
        assertEquals("https://cdn.zapstore.dev/$pubkey.profile.webp", profileCdnUrl(pubkey))
    }

    @Test
    fun `profile icon cache key is stable for one cache period`() {
        val url = "https://cdn.zapstore.dev/p/example.webp"
        val period = AppConfig.profileCacheDuration.inWholeMilliseconds
        val start = 10 * period

        assertEquals(profileIconCacheKey(url, start), profileIconCacheKey(url, start + period - 1))
        assertNotEquals(profileIconCacheKey(url, start), profileIconCacheKey(url, start + period))
    }
}
