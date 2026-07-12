package dev.zapstore.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

object ZapMarkdown {
    private val headingPattern = Regex("^(#{1,6})\\s+(.+)$")
    private val unorderedListPattern = Regex("^[-*+]\\s+(.+)$")
    private val orderedListPattern = Regex("^(\\d+)[.)]\\s+(.+)$")
    private val inlinePattern = Regex(
        """\[([^\]]+)]\(([^)\s]+)(?:\s+"[^"]*")?\)|`([^`]+)`|(\*\*|__)(.+?)\4|(?<!\w)(\*|_)([^*_]+)\6""",
    )

    fun parse(
        value: String,
        onOpenUrl: ((String) -> Unit)? = null,
    ): AnnotatedString = buildAnnotatedString {
        value.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                headingPattern.matches(line) -> {
                    val match = requireNotNull(headingPattern.matchEntire(line))
                    val level = match.groupValues[1].length
                    withStyle(
                        SpanStyle(
                            fontFamily = InterDisplayFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = headingSize(level),
                        ),
                    ) {
                        appendInline(match.groupValues[2], onOpenUrl)
                    }
                }

                unorderedListPattern.matches(line) -> {
                    val content = requireNotNull(unorderedListPattern.matchEntire(line)).groupValues[1]
                    withStyle(ParagraphStyle(textIndent = TextIndent(restLine = 16.sp))) {
                        append("•  ")
                        appendInline(content, onOpenUrl)
                    }
                }

                orderedListPattern.matches(line) -> {
                    val match = requireNotNull(orderedListPattern.matchEntire(line))
                    withStyle(ParagraphStyle(textIndent = TextIndent(restLine = 20.sp))) {
                        append(match.groupValues[1])
                        append(".  ")
                        appendInline(match.groupValues[2], onOpenUrl)
                    }
                }

                else -> appendInline(line, onOpenUrl)
            }

            if (index != value.lines().lastIndex) append("\n")
        }
    }

    private fun AnnotatedString.Builder.appendInline(
        value: String,
        onOpenUrl: ((String) -> Unit)?,
    ) {
        var cursor = 0
        inlinePattern.findAll(value).forEach { match ->
            append(value.substring(cursor, match.range.first))
            when {
                match.groupValues[1].isNotEmpty() -> {
                    val label = match.groupValues[1]
                    val url = match.groupValues[2]
                    if (isHttpUrl(url)) {
                        withLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = ZapPrimary,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                                linkInteractionListener = onOpenUrl?.let { callback ->
                                    LinkInteractionListener { link ->
                                        (link as? LinkAnnotation.Url)
                                            ?.url
                                            ?.takeIf(::isHttpUrl)
                                            ?.let(callback)
                                    }
                                },
                            ),
                        ) {
                            append(label)
                        }
                    } else {
                        append(label)
                    }
                }

                match.groupValues[3].isNotEmpty() -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = ZapSurfaceVariant,
                        ),
                    ) {
                        append(match.groupValues[3])
                    }
                }

                match.groupValues[4].isNotEmpty() -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(match.groupValues[5])
                    }
                }

                match.groupValues[6].isNotEmpty() -> {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(match.groupValues[7])
                    }
                }
            }
            cursor = match.range.last + 1
        }
        append(value.substring(cursor))
    }

    private fun headingSize(level: Int): TextUnit = when (level) {
        1 -> 27.sp
        2 -> 24.sp
        3 -> 21.sp
        4 -> 18.sp
        5 -> 16.sp
        else -> 14.sp
    }
}

@Composable
fun MarkdownText(
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val annotated = remember(value, onOpenUrl) {
        ZapMarkdown.parse(value, onOpenUrl)
    }
    Text(
        text = annotated,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
    )
}
