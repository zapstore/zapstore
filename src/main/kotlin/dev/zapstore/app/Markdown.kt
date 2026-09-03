package dev.zapstore.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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
        var started = false
        var pendingBlank = false
        var lastWasHeading = false
        var lastWasList = false

        value.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                pendingBlank = true
                return@forEach
            }

            val isHeading = headingPattern.matches(line)
            val isList = unorderedListPattern.matches(line) || orderedListPattern.matches(line)

            if (started) {
                when {
                    isHeading || lastWasHeading -> append("\n\n")
                    isList && lastWasList -> append('\n')
                    pendingBlank -> append("\n\n")
                    else -> append('\n')
                }
            }

            pendingBlank = false
            started = true
            lastWasHeading = isHeading
            lastWasList = isList

            when {
                isHeading -> {
                    val match = requireNotNull(headingPattern.matchEntire(line))
                    val level = match.groupValues[1].length
                    withStyle(
                        SpanStyle(
                            fontFamily = FigtreeFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = headingSize(level),
                        ),
                    ) {
                        appendInline(match.groupValues[2], onOpenUrl)
                    }
                }

                unorderedListPattern.matches(line) -> {
                    val content = requireNotNull(unorderedListPattern.matchEntire(line)).groupValues[1]
                    append("•  ")
                    appendInline(content, onOpenUrl)
                }

                orderedListPattern.matches(line) -> {
                    val match = requireNotNull(orderedListPattern.matchEntire(line))
                    append(match.groupValues[1])
                    append(".  ")
                    appendInline(match.groupValues[2], onOpenUrl)
                }

                else -> appendInline(line, onOpenUrl)
            }
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
                                        color = ZapActionText,
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
                            background = ZapSurface2,
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
    collapsible: Boolean = false,
    collapsedMaxHeight: Dp = 140.dp,
    fadeColor: Color = ZapCanvas,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(value, onOpenUrl) {
        ZapMarkdown.parse(value, onOpenUrl)
    }

    if (!collapsible) {
        Text(
            text = annotated,
            color = color,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier,
        )
        return
    }

    var expanded by remember(value) { mutableStateOf(false) }
    var overflows by remember(value) { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        Text(
            text = annotated,
            color = color,
            style = style,
            overflow = TextOverflow.Clip,
            onTextLayout = { result ->
                if (!expanded) {
                    overflows = result.didOverflowHeight
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (expanded) {
                        Modifier
                    } else {
                        Modifier.heightIn(max = collapsedMaxHeight)
                    },
                ),
        )

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
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 11.sp,
                    ),
                    modifier = Modifier
                        .padding(bottom = 2.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ZapSurface2)
                        .clickable { expanded = true }
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}
