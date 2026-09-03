package dev.zapstore.app

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

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
        color = ZapTextTertiary,
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
        color = ZapTextSecondary,
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
    color: Color = ZapTextPrimary,
) {
    Text(
        text = value,
        color = color,
        style = MaterialTheme.typography.labelMedium.copy(color = color),
        modifier = modifier,
    )
}

@Composable
fun StatusBadge(
    value: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value,
        color = foreground,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(ZapRadius.full))
            .background(background)
            .padding(horizontal = ZapSpacing.space2, vertical = ZapSpacing.space1),
    )
}

/**
 * Pill search field matching the "search" component (design/DESIGN.md): surface-1, hairline
 * border, fully rounded, 44 dp Android control height — not the default 56 dp text field.
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
    val shape = RoundedCornerShape(ZapRadius.full)
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = ZapTextPrimary)

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
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
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
                        Text(text = placeholder, style = textStyle, color = ZapTextTertiary)
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

fun isHttpUrl(value: String): Boolean =
    value.startsWith("https://") || value.startsWith("http://")
