package dev.zapstore.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    private val pubkey = "6d04b3ef16e4b6a4357433ce64e93d66268c1e3c95a243f6de442412938aa537"
    private val npub = "npub1d5zt8mckujm2gdt5x08xf6favcngc83ujk3y8ak7gsjp9yu255ms6lkcd6"

    @Test
    fun appLinks() {
        assertEquals(DeepLink.App("dev.zapstore.app"), DeepLink.parse("https://zapstore.dev/apps/dev.zapstore.app"))
        assertEquals(DeepLink.App("dev.zapstore.app"), DeepLink.parse("https://www.zapstore.dev/apps/dev.zapstore.app/"))
        assertEquals(
            DeepLink.App("dev.zapstore.app"),
            DeepLink.parse("https://zapstore.dev/apps/naddr1qqgxgetk9eaxzurnw3hhyefwv9c8qq3qd5zt8mckujm2gdt5x08xf6favcngc83ujk3y8ak7gsjp9yu255msxpqqqplqkkcgk2x"),
        )
        assertEquals(DeepLink.App("org.example"), DeepLink.parse("market://details?id=org.example&referrer=x"))
    }

    @Test
    fun stackAndProfileLinks() {
        assertEquals(
            DeepLink.Stack(pubkey, "nostr-clients"),
            DeepLink.parse("https://zapstore.dev/stacks/naddr1qqxkummnw3ez6cmvd9jkuarnqgsx6p9nautwfd4yx46r8nnyay7kvf5vrc7ftgjr7m0ygfqjjw922dcrqsqqqa3mrfzd9k"),
        )
        assertEquals(DeepLink.Stack(pubkey, "nostr-clients"), DeepLink.parse("https://zapstore.dev/stacks/$npub/nostr-clients"))
        assertEquals(DeepLink.Profile(pubkey), DeepLink.parse("https://zapstore.dev/profile/$npub"))
        assertEquals(
            DeepLink.Profile(pubkey),
            DeepLink.parse("https://zapstore.dev/profile/nprofile1qqsx6p9nautwfd4yx46r8nnyay7kvf5vrc7ftgjr7m0ygfqjjw922dcpzpmhxue69uhhyetvv9ujuar9wd6q24xlq8"),
        )
        assertEquals(DeepLink.Profile(pubkey), DeepLink.parse("https://zapstore.dev/profile/$pubkey"))
    }

    @Test
    fun ignoresOtherLinks() {
        assertNull(DeepLink.parse("https://evil.dev/apps/dev.zapstore.app"))
        assertNull(DeepLink.parse("http://zapstore.dev/apps/dev.zapstore.app"))
        assertNull(DeepLink.parse("https://zapstore.dev/apps/"))
        assertNull(DeepLink.parse("https://zapstore.dev/stacks/naddr1qqgxgetk9eaxzurnw3hhyefwv9c8qq3qd5zt8mckujm2gdt5x08xf6favcngc83ujk3y8ak7gsjp9yu255msxpqqqplqkkcgk2x"))
        assertNull(DeepLink.parse("https://zapstore.dev/profile/npub1bad"))
        assertNull(DeepLink.parse("market://details"))
        assertNull(DeepLink.parse("not a link"))
    }
}
