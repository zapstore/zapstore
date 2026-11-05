package dev.zapstore.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import dev.zapstore.iolite.neventOf
import dev.zapstore.iolite.toNote
import dev.zapstore.iolite.toNpub
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

    @Test
    fun `nostr entities and hashtags become links`() {
        val pubkey = "6d04b3ef16e4b6a4357433ce64e93d66268c1e3c95a243f6de442412938aa537"
        val npub = pubkey.toNpub()
        val note = "ab".repeat(32).toNote()
        val nevent = neventOf("cd".repeat(32), pubkey)!!
        val nprofile = "nprofile1qqsx6p9nautwfd4yx46r8nnyay7kvf5vrc7ftgjr7m0ygfqjjw922dcpzpmhxue69uhhyetvv9ujuar9wd6q24xlq8"
        val app = "naddr1qqgxgetk9eaxzurnw3hhyefwv9c8qq3qd5zt8mckujm2gdt5x08xf6favcngc83ujk3y8ak7gsjp9yu255msxpqqqplqkkcgk2x"
        val stack = "naddr1qqxkummnw3ez6cmvd9jkuarnqgsx6p9nautwfd4yx46r8nnyay7kvf5vrc7ftgjr7m0ygfqjjw922dcrqsqqqa3mrfzd9k"
        val nsec = "nsec1jax67srg8a5j4rej3spldsduryn6pdhtfcegcztuhdexyx2v42aq0t4sv6"
        val result = ZapMarkdown.parse(
            "see nostr:$npub $nprofile $app $stack $note $nevent #Nostr and `$npub` but not $nsec",
        )

        assertEquals(
            listOf(
                "https://zapstore.dev/profile/$npub",
                "https://zapstore.dev/profile/$nprofile",
                "https://zapstore.dev/apps/$app",
                "https://zapstore.dev/stacks/$stack",
                "https://njump.me/$note",
                "https://njump.me/$nevent",
                "https://zapstore.dev/apps?q=Nostr",
            ),
            result.urls(),
        )
        assertTrue(result.text.contains("nostr:$npub"))
        assertTrue(result.text.contains("#Nostr"))
        assertTrue(result.text.contains(nsec))
    }

    @Test
    fun `a markdown link does not link the nostr token inside it`() {
        val npub = "6d04b3ef16e4b6a4357433ce64e93d66268c1e3c95a243f6de442412938aa537".toNpub()
        val result = ZapMarkdown.parse("[$npub](https://example.com)")
        assertEquals(listOf("https://example.com"), result.urls())
    }

    @Test
    fun `a mentioned profile reads as its name, or a short npub until it is known`() {
        val pubkey = "6d04b3ef16e4b6a4357433ce64e93d66268c1e3c95a243f6de442412938aa537"
        val npub = pubkey.toNpub()
        val bio = "thanks nostr:$npub"

        assertEquals(setOf(pubkey), ZapMarkdown.mentionedPubkeys(bio))
        val named = ZapMarkdown.parse(bio, mentions = mapOf(pubkey to "Alice"))
        assertEquals("thanks @Alice", named.text)
        assertEquals(listOf("https://zapstore.dev/profile/$npub"), named.urls())
        assertEquals("thanks @${npub.take(12)}…${npub.takeLast(4)}", ZapMarkdown.parse(bio, mentions = emptyMap()).text)
        assertEquals(bio, ZapMarkdown.parse(bio).text)
    }

    private fun AnnotatedString.urls(): List<String> =
        getLinkAnnotations(0, length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
}
