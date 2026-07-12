package dev.zapstore.app

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun `parses supported block and inline formatting`() {
        val result = ZapMarkdown.parse(
            """
            # Heading
            - one
            1. two
            **bold** _italic_ `code`
            """.trimIndent(),
        )

        assertEquals("Heading\n•  one\n1.  two\nbold italic code", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.ExtraBold })
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `creates links only for http urls`() {
        val result = ZapMarkdown.parse(
            "[site](https://zapstore.dev) [phone](tel:123)",
        )

        val links = result.getLinkAnnotations(0, result.length)
            .mapNotNull { it.item as? LinkAnnotation.Url }
        assertEquals(listOf("https://zapstore.dev"), links.map(LinkAnnotation.Url::url))
        assertTrue(result.text.contains("site"))
        assertTrue(result.text.contains("phone"))
        assertFalse(result.text.contains("tel:123"))
    }
}
