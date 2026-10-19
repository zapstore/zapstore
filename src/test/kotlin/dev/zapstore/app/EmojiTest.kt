package dev.zapstore.app

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiTest {
    @Test
    fun shortcodesBecomeEmoji() {
        assertEquals("✨ New ✅", Emoji.replace(":sparkles: New :white_check_mark:"))
    }

    @Test
    fun unknownCodesAndCodeSpansStayAsWritten() {
        assertEquals(":not_an_emoji: `:sparkles:` 12:30:45", Emoji.replace(":not_an_emoji: `:sparkles:` 12:30:45"))
    }

    @Test
    fun markdownRendersShortcodes() {
        assertEquals("Fixes 🐛", ZapMarkdown.parse("Fixes :bug:").text)
    }
}
