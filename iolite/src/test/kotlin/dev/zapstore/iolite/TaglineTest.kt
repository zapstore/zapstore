package dev.zapstore.iolite

import org.junit.Test
import org.junit.Assert.assertEquals

class TaglineTest {
    @Test
    fun firstProseParagraphSkipsHeadingsImagesAndUrls() {
        val markdown = """
            # My App

            ![logo](https://example.com/logo.png)

            https://example.com

            A **fast** notes app with [sync](https://example.com/sync)
            and snake_case tags.

            Second paragraph.
        """.trimIndent()
        assertEquals("A fast notes app with sync and snake_case tags.", plainParagraph(markdown))
    }

    @Test
    fun emptyWhenThereIsNoProse() {
        assertEquals("", plainParagraph("# Title\n\n![x](y)"))
    }
}
