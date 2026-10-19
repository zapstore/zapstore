package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
import dev.zapstore.app.ZapActionText
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSize
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapTextPrimary
import dev.zapstore.app.ZapTextSecondary
import dev.zapstore.app.ZapTextTertiary
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle

/** Paste an `nsec` or hex key. */
@Composable
internal fun PasteKeySheet(error: String?, onSubmit: (String) -> Unit, onDismiss: () -> Unit) = PasteSecretSheet(
    title = stringResource(R.string.paste_key_title),
    body = stringResource(R.string.paste_key_body),
    placeholder = "nsec1…",
    tag = "pasteKey",
    error = error,
    onSubmit = onSubmit,
    onDismiss = onDismiss,
)

/** Paste a secret. The value is masked: it is shown on a screen others may see. Tags are [tag]Field, [tag]Error, [tag]Submit. */
@Composable
internal fun PasteSecretSheet(
    title: String,
    body: String,
    placeholder: String,
    tag: String,
    error: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier.fillMaxWidth().padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, color = ZapTextSecondary, style = MaterialTheme.typography.bodyMedium)
            val shape = RoundedCornerShape(ZapRadius.control)
            val style = MaterialTheme.typography.bodyMedium.copy(color = ZapTextPrimary)
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(ZapActionText),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ZapSize.control)
                    .background(ZapSurface1, shape)
                    .border(1.dp, ZapLine, shape)
                    .testTag("${tag}Field"),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = ZapSpacing.space3), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, style = style, color = ZapTextTertiary)
                        inner()
                    }
                },
            )
            error?.let { StatusText(it, Modifier.testTag("${tag}Error")) }
            ZapButton(
                text = stringResource(R.string.continue_action),
                onClick = { onSubmit(value) },
                enabled = value.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(ZapSize.controlLarge).testTag("${tag}Submit"),
            )
            ZapButton(
                text = stringResource(R.string.install_cancel),
                onClick = onDismiss,
                style = ZapButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The last step before the key changes. Danger: the current key's lists and settings stop being shown. */
@Composable
internal fun ReplaceKeySheet(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmSheet(
        title = stringResource(R.string.replace_key_title),
        body = listOf(stringResource(R.string.replace_key_body), stringResource(R.string.replace_key_backup_first)),
        confirm = stringResource(R.string.replace_key_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmStyle = ZapButtonStyle.Danger,
        confirmTag = "replaceKeyConfirm",
    )
}
