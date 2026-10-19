package dev.zapstore.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
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
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapChip
import dev.zapstore.app.components.ZapTextLink
import dev.zapstore.iolite.Social
import java.text.NumberFormat

/** Multiline field that grows to four lines, with Post beside it. Signed out, a link to sign in. */
@Composable
internal fun CommentComposer(social: SocialUi, onPost: (String) -> Unit, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    if (!social.signedIn) {
        ZapTextLink(stringResource(R.string.sign_in_to_comment), onSignIn, modifier.testTag("signInToComment"))
        return
    }
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(social.posted) { if (social.posted > 0) text = "" }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
            ZapTextArea(
                value = text,
                onValueChange = { text = it },
                placeholder = stringResource(R.string.comment_placeholder),
                modifier = Modifier.weight(1f).testTag("commentField"),
            )
            ZapButton(
                text = stringResource(R.string.post),
                onClick = { onPost(text) },
                enabled = text.isNotBlank() && !social.posting,
                loading = social.posting,
                modifier = Modifier.height(ZapSize.control).testTag("postComment"),
            )
        }
        social.error?.let { StatusText(it, Modifier.testTag("commentError")) }
    }
}

@Composable
internal fun ZapTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 4,
) {
    val shape = RoundedCornerShape(ZapRadius.control)
    val style = MaterialTheme.typography.bodyMedium.copy(color = ZapTextPrimary)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = style,
        maxLines = maxLines,
        singleLine = maxLines == 1,
        cursorBrush = SolidColor(ZapActionText),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier
            .heightIn(min = ZapSize.control)
            .background(ZapSurface1, shape)
            .border(1.dp, ZapLine, shape),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = ZapSpacing.space3, vertical = 11.dp)) {
                if (value.isEmpty()) Text(placeholder, style = style, color = ZapTextTertiary)
                inner()
            }
        },
    )
}

/**
 * NIP-56 report: one reason, an optional note, then Send in danger. Signed out, it explains why
 * and offers Sign in instead; once sent, it says so and closes on dismiss.
 */
@Composable
internal fun ReportSheet(
    appName: String,
    social: SocialUi,
    onSend: (type: String, note: String) -> Unit,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    var type by rememberSaveable { mutableStateOf(Social.reportTypes.first()) }
    var note by rememberSaveable { mutableStateOf("") }
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5)
                .testTag("reportSheet"),
        ) {
            Text(stringResource(R.string.report_title, appName), style = MaterialTheme.typography.titleLarge)
            when {
                social.reported -> {
                    Text(stringResource(R.string.report_sent), color = ZapTextSecondary, style = MaterialTheme.typography.bodyMedium)
                    ZapButton(stringResource(R.string.close), onDismiss, Modifier.fillMaxWidth(), style = ZapButtonStyle.Tonal)
                }
                !social.signedIn -> {
                    Text(stringResource(R.string.report_sign_in), color = ZapTextSecondary, style = MaterialTheme.typography.bodyMedium)
                    ZapButton(
                        stringResource(R.string.sign_in_with_signer),
                        onSignIn,
                        Modifier.fillMaxWidth().height(ZapSize.controlLarge).testTag("reportSignIn"),
                    )
                }
                else -> {
                    Text(stringResource(R.string.report_body), color = ZapTextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Column {
                        Social.reportTypes.forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .selectable(selected = option == type, role = Role.RadioButton, onClick = { type = option })
                                    .testTag("reportType:$option"),
                            ) {
                                RadioButton(
                                    selected = option == type,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = ZapAction, unselectedColor = ZapTextTertiary),
                                )
                                Text(
                                    stringResource(option.reportLabel()),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(start = ZapSpacing.space2),
                                )
                            }
                        }
                    }
                    ZapTextArea(note, { note = it }, stringResource(R.string.report_note), Modifier.fillMaxWidth().testTag("reportNote"))
                    social.error?.let { StatusText(it) }
                    ZapButton(
                        text = stringResource(R.string.report_send),
                        onClick = { onSend(type, note) },
                        style = ZapButtonStyle.Danger,
                        loading = social.posting,
                        enabled = !social.posting,
                        modifier = Modifier.fillMaxWidth().height(ZapSize.controlLarge).testTag("reportSend"),
                    )
                }
            }
        }
    }
}

/**
 * Zap the app's C1 owner, or else its catalog: a preset amount, an optional note, then Zap. Without an account or a
 * wallet it says what is missing and links to Settings; once paid, it says so.
 */
@Composable
internal fun ZapAppSheet(
    appName: String,
    social: SocialUi,
    recipient: ZapRecipient?,
    recipientName: String,
    initialSats: Long,
    onZap: (sats: Long, note: String) -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var sats by rememberSaveable(initialSats) { mutableLongStateOf(initialSats) }
    var note by rememberSaveable { mutableStateOf("") }
    val format = remember { NumberFormat.getInstance() }
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5)
                .testTag("zapSheet"),
        ) {
            Text(stringResource(R.string.zap_title, appName), style = MaterialTheme.typography.titleLarge)
            when {
                social.zapped != null -> {
                    Text(
                        stringResource(R.string.zap_sent, format.format(social.zapped), recipientName),
                        color = ZapTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("zapSent"),
                    )
                    ZapButton(stringResource(R.string.close), onDismiss, Modifier.fillMaxWidth(), style = ZapButtonStyle.Tonal)
                }
                !social.signedIn || !social.walletConnected -> {
                    Text(stringResource(R.string.zap_needs), color = ZapTextSecondary, style = MaterialTheme.typography.bodyMedium)
                    ZapButton(
                        stringResource(if (!social.signedIn) R.string.zap_sign_in else R.string.zap_open_wallet),
                        onSettings,
                        Modifier.fillMaxWidth().height(ZapSize.controlLarge).testTag("zapSettings"),
                    )
                }
                else -> {
                    Text(
                        if (recipient?.catalog == true) stringResource(R.string.zap_body_catalog, recipientName)
                        else stringResource(R.string.zap_body, recipientName),
                        color = ZapTextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("zapBody"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                        ZAP_AMOUNTS.forEach { amount ->
                            ZapChip(
                                text = format.format(amount),
                                onClick = { sats = amount },
                                selected = amount == sats,
                                modifier = Modifier.testTag("zapAmount:$amount"),
                            )
                        }
                    }
                    ZapTextArea(note, { note = it }, stringResource(R.string.zap_note), Modifier.fillMaxWidth().testTag("zapNote"))
                    social.zapError?.let { StatusText(it, Modifier.testTag("zapError")) }
                    ZapButton(
                        text = stringResource(R.string.zap_send, format.format(sats)),
                        onClick = { onZap(sats, note) },
                        loading = social.zapping,
                        enabled = !social.zapping,
                        icon = R.drawable.ic_bolt,
                        modifier = Modifier.fillMaxWidth().height(ZapSize.controlLarge).testTag("zapSend"),
                    )
                }
            }
        }
    }
}


private fun String.reportLabel(): Int = when (this) {
    "malware" -> R.string.report_malware
    "spam" -> R.string.report_spam
    "impersonation" -> R.string.report_impersonation
    "illegal" -> R.string.report_illegal
    else -> R.string.report_other
}
