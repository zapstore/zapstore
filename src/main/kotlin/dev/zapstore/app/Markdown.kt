package dev.zapstore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.node.Text as TextNode

/** One rendered piece of a Markdown document. */
sealed interface MdBlock {
    /** Paragraph, heading (level 1–6), or list item (with [marker]). [indent] counts list nesting; [quote] counts blockquote depth. */
    data class Prose(
        val text: AnnotatedString,
        val heading: Int = 0,
        val marker: String? = null,
        val indent: Int = 0,
        val quote: Int = 0,
    ) : MdBlock

    data class CodeBlock(val code: String, val quote: Int = 0) : MdBlock

    data class Table(val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>, val align: List<TextAlign>) : MdBlock

    data class Picture(val url: String, val alt: String) : MdBlock

    data object Rule : MdBlock
}

/**
 * GitHub-flavored Markdown: CommonMark plus tables, strikethrough, task lists and bare-URL
 * autolinks, with `:shortcode:` emoji. Links open only for http and https. Raw HTML is reduced
 * to its text, `<br>` and `<img>`.
 */
object ZapMarkdown {
    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create(),
            ),
        )
        .build()

    private val cache = object : LinkedHashMap<String, List<MdBlock>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdBlock>>?): Boolean = size > 64
    }
    private val htmlTag = Regex("<[^>]+>")
    private val htmlBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val htmlImage = Regex("<img\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
    private val htmlAlt = Regex("\\balt\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

    /** Blocks for [value]. Link taps go to [onOpenUrl]; results without a callback are cached. */
    fun blocks(value: String, onOpenUrl: ((String) -> Unit)? = null): List<MdBlock> {
        if (onOpenUrl == null) synchronized(cache) { cache[value] }?.let { return it }
        val out = mutableListOf<MdBlock>()
        Walker(onOpenUrl, out).blocks(parser.parse(value), indent = 0, quote = 0)
        if (onOpenUrl == null) synchronized(cache) { cache[value] = out }
        return out
    }

    /** The whole document as one string: blocks separated by a blank line, list items by a line break. */
    fun parse(value: String, onOpenUrl: ((String) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
        var previous: MdBlock? = null
        for (block in blocks(value, onOpenUrl)) {
            if (previous != null) {
                val bothItems = block is MdBlock.Prose && block.marker != null && previous is MdBlock.Prose && previous.marker != null
                append(if (bothItems) "\n" else "\n\n")
            }
            when (block) {
                is MdBlock.Prose -> {
                    block.marker?.let { append(it); append("  ") }
                    if (block.heading > 0) withStyle(headingStyle(block.heading)) { append(block.text) } else append(block.text)
                }
                is MdBlock.CodeBlock -> append(block.code)
                is MdBlock.Table -> append((listOf(block.header) + block.rows).joinToString("\n") { row -> row.joinToString(" · ") })
                is MdBlock.Picture -> append(block.alt)
                MdBlock.Rule -> append("—")
            }
            previous = block
        }
    }

    internal fun headingStyle(level: Int) = SpanStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = headingSize(level),
        letterSpacing = (-0.016).em,
    )

    private fun headingSize(level: Int): TextUnit = when (level) {
        1 -> 24.sp
        2 -> 21.sp
        3 -> 18.sp
        4 -> 16.sp
        else -> 14.sp
    }

    private class Walker(val onOpenUrl: ((String) -> Unit)?, val out: MutableList<MdBlock>) {
        fun blocks(parent: Node, indent: Int, quote: Int) {
            var node = parent.firstChild
            while (node != null) {
                block(node, indent, quote)
                node = node.next
            }
        }

        fun block(node: Node, indent: Int, quote: Int) {
            when (node) {
                is Heading -> out += MdBlock.Prose(inline(node), heading = node.level, indent = indent, quote = quote)
                is Paragraph -> paragraph(node, indent, quote)
                is BlockQuote -> blocks(node, indent, quote + 1)
                is BulletList -> items(node, indent, quote) { _, item -> taskMarker(item) ?: "•" }
                is OrderedList -> {
                    val start = node.markerStartNumber ?: 1
                    items(node, indent, quote) { index, item -> taskMarker(item) ?: "${start + index}." }
                }
                is FencedCodeBlock -> out += MdBlock.CodeBlock(node.literal.trimEnd('\n'), quote)
                is IndentedCodeBlock -> out += MdBlock.CodeBlock(node.literal.trimEnd('\n'), quote)
                is ThematicBreak -> out += MdBlock.Rule
                is TableBlock -> table(node)
                is HtmlBlock -> html(node.literal, indent, quote)
                else -> blocks(node, indent, quote)
            }
        }

        fun paragraph(node: Paragraph, indent: Int, quote: Int, marker: String? = null) {
            val images = generateSequence(node.firstChild) { it.next }.toList()
            val onlyImages = images.isNotEmpty() && images.all { it is Image || it is SoftLineBreak || it is HardLineBreak || (it is TextNode && it.literal.isBlank()) }
            if (onlyImages && marker == null) {
                images.filterIsInstance<Image>().forEach { image ->
                    val alt = plainText(image)
                    if (isHttpUrl(image.destination)) out += MdBlock.Picture(image.destination, alt)
                }
                return
            }
            out += MdBlock.Prose(inline(node), marker = marker, indent = indent, quote = quote)
        }

        fun items(list: Node, indent: Int, quote: Int, marker: (Int, ListItem) -> String) {
            var item = list.firstChild
            var index = 0
            while (item != null) {
                if (item is ListItem) {
                    val label = marker(index, item)
                    var child = item.firstChild
                    var first = true
                    while (child != null) {
                        if (child is TaskListItemMarker) {
                            child = child.next
                            continue
                        }
                        when {
                            first && child is Paragraph -> paragraph(child, indent, quote, label)
                            first -> {
                                out += MdBlock.Prose(AnnotatedString(""), marker = label, indent = indent, quote = quote)
                                block(child, indent + 1, quote)
                            }
                            else -> block(child, indent + 1, quote)
                        }
                        first = false
                        child = child.next
                    }
                    if (first) out += MdBlock.Prose(AnnotatedString(""), marker = label, indent = indent, quote = quote)
                    index++
                }
                item = item.next
            }
        }

        fun taskMarker(item: ListItem): String? {
            val marker = item.firstChild?.firstChild as? TaskListItemMarker ?: item.firstChild as? TaskListItemMarker ?: return null
            return if (marker.isChecked) "☑" else "☐"
        }

        fun table(node: TableBlock) {
            val header = mutableListOf<AnnotatedString>()
            val rows = mutableListOf<List<AnnotatedString>>()
            val align = mutableListOf<TextAlign>()
            var section = node.firstChild
            while (section != null) {
                var row = section.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        val cells = generateSequence(row.firstChild) { it.next }.filterIsInstance<TableCell>().toList()
                        if (section is TableHead) {
                            header += cells.map { inline(it) }
                            align += cells.map {
                                when (it.alignment) {
                                    TableCell.Alignment.CENTER -> TextAlign.Center
                                    TableCell.Alignment.RIGHT -> TextAlign.End
                                    else -> TextAlign.Start
                                }
                            }
                        } else {
                            rows += cells.map { inline(it) }
                        }
                    }
                    row = row.next
                }
                section = section.next
            }
            out += MdBlock.Table(header, rows, align)
        }

        fun html(literal: String, indent: Int, quote: Int) {
            htmlImage.findAll(literal).forEach { match ->
                val url = match.groupValues[1]
                val alt = htmlAlt.find(match.value)?.groupValues?.get(1).orEmpty()
                if (isHttpUrl(url)) out += MdBlock.Picture(url, alt)
            }
            val text = htmlBreak.replace(literal, "\n").replace(htmlTag, "").let(::decodeEntities).trim()
            if (text.isNotEmpty()) out += MdBlock.Prose(AnnotatedString(Emoji.replace(text)), indent = indent, quote = quote)
        }

        fun inline(parent: Node): AnnotatedString = buildAnnotatedString { appendChildren(parent) }

        fun AnnotatedString.Builder.appendChildren(parent: Node) {
            var node = parent.firstChild
            while (node != null) {
                appendInline(node)
                node = node.next
            }
        }

        fun AnnotatedString.Builder.appendInline(node: Node) {
            when (node) {
                is TextNode -> append(Emoji.replace(node.literal))
                is TaskListItemMarker -> Unit
                is SoftLineBreak -> append(' ')
                is HardLineBreak -> append('\n')
                is Code -> withStyle(SpanStyle(background = ZapSurface2, fontFeatureSettings = "tnum")) { append(node.literal) }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendChildren(node) }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendChildren(node) }
                is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendChildren(node) }
                is Link -> link(node.destination) { appendChildren(node) }
                is Image -> link(node.destination) { append(plainText(node).ifBlank { node.destination }) }
                is HtmlInline -> if (htmlBreak.matches(node.literal)) append('\n')
                else -> appendChildren(node)
            }
        }

        fun AnnotatedString.Builder.link(url: String, content: AnnotatedString.Builder.() -> Unit) {
            if (!isHttpUrl(url)) {
                content()
                return
            }
            withLink(
                LinkAnnotation.Url(
                    url = url,
                    styles = TextLinkStyles(style = SpanStyle(color = ZapActionText, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = onOpenUrl?.let { callback ->
                        LinkInteractionListener { link -> (link as? LinkAnnotation.Url)?.url?.takeIf(::isHttpUrl)?.let(callback) }
                    },
                ),
            ) { content() }
        }

        fun plainText(node: Node): String = buildString {
            fun walk(n: Node) {
                if (n is TextNode) append(n.literal)
                var child = n.firstChild
                while (child != null) {
                    walk(child)
                    child = child.next
                }
            }
            walk(node)
        }
    }

    private fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}

@Composable
fun MarkdownText(
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onOpenUrl: ((String) -> Unit)? = null,
    collapsible: Boolean = false,
    collapsedMaxHeight: Dp = 140.dp,
    fadeColor: Color = ZapCanvas,
    modifier: Modifier = Modifier,
) {
    if (maxLines != Int.MAX_VALUE) {
        val annotated = remember(value, onOpenUrl) { ZapMarkdown.parse(value, onOpenUrl) }
        Text(annotated, color = color, style = style, maxLines = maxLines, overflow = overflow, modifier = modifier)
        return
    }
    val blocks = remember(value, onOpenUrl) { ZapMarkdown.blocks(value, onOpenUrl) }
    if (!collapsible) {
        MarkdownBlocks(blocks, color, style, modifier)
        return
    }

    var expanded by remember(value) { mutableStateOf(false) }
    var overflows by remember(value) { mutableStateOf(false) }
    val limitPx = with(LocalDensity.current) { collapsedMaxHeight.roundToPx() }

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (expanded) Modifier else Modifier.heightIn(max = collapsedMaxHeight))
                .clipToBounds(),
        ) {
            MarkdownBlocks(
                blocks,
                color,
                style,
                Modifier
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .onSizeChanged { if (!expanded) overflows = it.height > limitPx },
            )
        }

        if (!expanded && overflows) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to fadeColor.copy(alpha = 0f),
                                0.45f to fadeColor.copy(alpha = 0.85f),
                                1f to fadeColor,
                            ),
                        ),
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = stringResource(R.string.read_more),
                    color = ZapTextPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(ZapRadius.full))
                        .background(ZapSurface2)
                        .clickable { expanded = true }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Prose -> Quoted(block.quote) { ProseBlock(block, color, style) }
                is MdBlock.CodeBlock -> Quoted(block.quote) {
                    Text(
                        block.code,
                        color = ZapTextPrimary,
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(ZapRadius.sm))
                            .background(ZapSurface2)
                            .horizontalScroll(rememberScrollState())
                            .padding(ZapSpacing.space3),
                    )
                }
                is MdBlock.Table -> TableBlockView(block, color, style)
                is MdBlock.Picture -> AsyncImage(
                    model = block.url,
                    contentDescription = block.alt.ifBlank { null },
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(ZapRadius.sm)),
                )
                MdBlock.Rule -> HorizontalDivider(color = ZapLine, modifier = Modifier.padding(vertical = ZapSpacing.space1))
            }
        }
    }
}

@Composable
private fun Quoted(depth: Int, content: @Composable () -> Unit) {
    if (depth == 0) {
        content()
        return
    }
    Row(Modifier.height(IntrinsicSize.Min)) {
        repeat(depth) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(ZapLineStrong, RoundedCornerShape(ZapRadius.bar)))
            Box(Modifier.width(ZapSpacing.space3))
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun ProseBlock(block: MdBlock.Prose, color: Color, style: TextStyle) {
    val textColor = if (block.heading > 0) ZapTextPrimary else color
    val textStyle = if (block.heading > 0) style.merge(TextStyle(fontWeight = FontWeight.SemiBold, fontSize = ZapMarkdown.headingStyle(block.heading).fontSize)) else style
    val marker = block.marker
    if (marker == null) {
        Text(block.text, color = textColor, style = textStyle, modifier = Modifier.padding(start = 20.dp * block.indent))
        return
    }
    Row(Modifier.padding(start = 20.dp * block.indent)) {
        Text(marker, color = textColor, style = textStyle, modifier = Modifier.widthIn(min = 20.dp))
        Text(block.text, color = textColor, style = textStyle, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TableBlockView(block: MdBlock.Table, color: Color, style: TextStyle) {
    val columns = block.header.size
    val widths = remember(block) {
        (0 until columns).map { col ->
            val longest = (listOf(block.header) + block.rows).maxOf { row -> row.getOrNull(col)?.length ?: 0 }
            (longest * 8 + 24).coerceIn(64, 240).dp
        }
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(ZapRadius.sm))
            .background(ZapSurface1)
            .horizontalScroll(rememberScrollState()),
    ) {
        (listOf(block.header) + block.rows).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) HorizontalDivider(color = ZapLine, modifier = Modifier.width(widths.fold(0.dp) { a, b -> a + b }))
            Row {
                (0 until columns).forEach { col ->
                    Text(
                        row.getOrNull(col) ?: AnnotatedString(""),
                        color = if (rowIndex == 0) ZapTextPrimary else color,
                        style = if (rowIndex == 0) style.merge(TextStyle(fontWeight = FontWeight.SemiBold)) else style,
                        textAlign = block.align.getOrNull(col) ?: TextAlign.Start,
                        modifier = Modifier.width(widths[col]).padding(horizontal = ZapSpacing.space3, vertical = ZapSpacing.space2),
                    )
                }
            }
        }
    }
}
