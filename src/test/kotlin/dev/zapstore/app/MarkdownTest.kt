package dev.zapstore.app

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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

        assertEquals("Heading\n\n•  one\n1.  two\n\nbold italic code", result.text)
        assertTrue(result.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
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

    @Test
    fun `list items stay together and blocks are separated`() {
        val result = ZapMarkdown.parse(
            """
            intro

            # Heading

            - one
            - two


            last
            """.trimIndent(),
        )

        assertEquals("intro\n\nHeading\n\n•  one\n•  two\n\nlast", result.text)
    }

    @Test
    fun `tables keep header rows and alignment`() {
        val table = ZapMarkdown.blocks(
            """
            | Feature | Status |
            | :------ | -----: |
            | Tor     | yes    |
            | Zaps    | soon   |
            """.trimIndent(),
        ).single() as MdBlock.Table

        assertEquals(listOf("Feature", "Status"), table.header.map { it.text })
        assertEquals(listOf(listOf("Tor", "yes"), listOf("Zaps", "soon")), table.rows.map { row -> row.map { it.text } })
        assertEquals(listOf(TextAlign.Start, TextAlign.End), table.align)
    }

    @Test
    fun `strikethrough task lists and bare urls follow GFM`() {
        val blocks = ZapMarkdown.blocks(
            """
            ~~old~~ new, see https://zapstore.dev

            - [x] done
            - [ ] todo
            """.trimIndent(),
        )

        val first = (blocks[0] as MdBlock.Prose).text
        assertTrue(first.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        val links = first.getLinkAnnotations(0, first.length).mapNotNull { it.item as? LinkAnnotation.Url }
        assertEquals(listOf("https://zapstore.dev"), links.map(LinkAnnotation.Url::url))
        assertEquals(listOf("☑", "☐"), blocks.drop(1).map { (it as MdBlock.Prose).marker })
        assertEquals(listOf("done", "todo"), blocks.drop(1).map { (it as MdBlock.Prose).text.text })
    }

    @Test
    fun `code quotes nesting rules and images become blocks`() {
        val blocks = ZapMarkdown.blocks(
            """
            ```kotlin
            val x = 1
            ```

            > quoted **text**

            - parent
              - child

            ---

            ![Screenshot](https://example.com/shot.png)

            <p align="center"><img src="https://example.com/logo.png" alt="Logo"></p>
            """.trimIndent(),
        )

        assertEquals(MdBlock.CodeBlock("val x = 1"), blocks[0])
        assertEquals(1, (blocks[1] as MdBlock.Prose).quote)
        assertEquals("quoted text", (blocks[1] as MdBlock.Prose).text.text)
        assertEquals(0, (blocks[2] as MdBlock.Prose).indent)
        assertEquals(1, (blocks[3] as MdBlock.Prose).indent)
        assertEquals(MdBlock.Rule, blocks[4])
        assertEquals(MdBlock.Picture("https://example.com/shot.png", "Screenshot"), blocks[5])
        assertEquals(MdBlock.Picture("https://example.com/logo.png", "Logo"), blocks[6])
    }

    @Test
    fun `raw html is reduced to text with line breaks`() {
        assertEquals("one\ntwo", ZapMarkdown.parse("one<br>two").text)
        assertEquals("Tom & Jerry", ZapMarkdown.parse("<div>Tom &amp; Jerry</div>").text)
    }
}
