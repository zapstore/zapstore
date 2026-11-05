package dev.zapstore.app.components

import dev.zapstore.app.ZapAction
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionTitle(
    value: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier,
    )
}

@Composable
fun SectionLabel(
    value: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value.uppercase(),
        color = ZapText3,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}

@Composable
fun StatusText(
    value: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value,
        color = ZapText2,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier,
    )
}

/**
 * For verifiable values (versions, hashes, package IDs): same face as everything else,
 * tabular figures instead of a monospace face (design/DESIGN.md "One face for evidence").
 */
@Composable
fun EvidenceText(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = ZapText,
) {
    Text(
        text = value,
        color = color,
        style = MaterialTheme.typography.labelMedium.copy(color = color),
        modifier = modifier,
    )
}

/**
 * The "search" component (design/DESIGN.md): surface-1, hairline border, 12 dp corners,
 * 44 dp tall — not the default 56 dp text field.
 */
@Composable
fun ZapSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onSearch: () -> Unit = {},
    leadingIcon: @Composable () -> Unit = {},
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(ZapRadius.control)
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = ZapText)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(ZapSize.control)
            .clip(shape)
            .background(ZapSurface1, shape)
            .border(1.dp, ZapLine, shape),
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(ZapActionText),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            onSearch()
            keyboardController?.hide()
        }),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = ZapSpacing.space3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2),
            ) {
                leadingIcon()
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(text = placeholder, style = textStyle, color = ZapText3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    innerTextField()
                }
                trailingIcon?.invoke()
            }
        },
    )
}

@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            color = ZapAction,
            strokeWidth = 2.dp,
        )
    }
}

/**
 * A heading that shrinks, down to 70% of [style], until no word is split across lines, so a long name is never
 * cut in the middle of a word. At most [maxLines]; past that it ends with an ellipsis.
 */
@Composable
fun FitTitle(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = 2) {
    var scale by remember(text, style) { mutableFloatStateOf(1f) }
    Text(
        text = text,
        style = style.copy(fontSize = (style.fontSize.value * scale).sp),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            val split = (0 until layout.lineCount - 1).any { line ->
                val end = layout.getLineEnd(line)
                end in 1 until text.length && text[end - 1].isLetterOrDigit() && text[end].isLetterOrDigit()
            }
            if (split && scale > 0.7f) scale = (scale - 0.05f).coerceAtLeast(0.7f)
        },
        modifier = modifier,
    )
}
