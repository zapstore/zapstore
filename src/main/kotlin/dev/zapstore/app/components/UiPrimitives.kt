package dev.zapstore.app.components

import dev.zapstore.app.LocalIsTv
import dev.zapstore.app.ZapAction
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapTextPrimary
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.app.ZapTextTertiary

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
 *
 * With a remote on Android TV, focus passes through the field on its way to the
 * buttons and the list. Passing by must not pop the keyboard or trap Left/Right in the text,
 * so in that mode the field is read-only until OK/Enter, and read-only again once the
 * keyboard closes. Touch input is unchanged.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(ZapRadius.full)
    val textStyle = MaterialTheme.typography.bodyMedium.copy(color = ZapTextPrimary)
    val keys = LocalIsTv.current && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Set once the keyboard has been up for this field, so its closing is told apart
    // from the moment between OK and the keyboard showing.
    var keyboardShown by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(focused, imeVisible) {
        when {
            !focused -> { keyboardShown = false; editing = false }
            imeVisible -> keyboardShown = true
            keyboardShown -> { keyboardShown = false; editing = false }
        }
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .height(ZapSize.control)
            .clip(shape)
            .background(ZapSurface1, shape)
            .border(
                if (keys && focused) 2.dp else 1.dp,
                if (keys && focused) ZapActionText else ZapLine,
                shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                if (!keys) return@onPreviewKeyEvent false
                // Up and Down mean nothing in a single line, so they always leave the field,
                // even while editing with no keyboard on screen.
                val vertical = it.key == Key.DirectionUp || it.key == Key.DirectionDown
                if (editing && !vertical) return@onPreviewKeyEvent false
                val ok = it.key == Key.DirectionCenter || it.key == Key.Enter || it.key == Key.NumPadEnter
                // A read-only field still takes the arrows for its cursor; hand them to focus.
                val direction = when (it.key) {
                    Key.DirectionLeft -> FocusDirection.Left
                    Key.DirectionRight -> FocusDirection.Right
                    Key.DirectionUp -> FocusDirection.Up
                    Key.DirectionDown -> FocusDirection.Down
                    else -> null
                }
                when {
                    ok -> {
                        if (it.type == KeyEventType.KeyUp) editing = true
                        true
                    }
                    direction != null -> {
                        if (it.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                        true
                    }
                    else -> false
                }
            },
        readOnly = keys && !editing,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(ZapActionText),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            onSearch()
            keyboardController?.hide()
            editing = false
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
