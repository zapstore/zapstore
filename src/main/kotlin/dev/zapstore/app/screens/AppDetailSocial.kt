package dev.zapstore.app.screens

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zapstore.app.R
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
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapChip
import dev.zapstore.app.components.ZapTextLink
import dev.zapstore.iolite.Social
import kotlinx.coroutines.launch
import java.text.NumberFormat

@Composable
internal fun ZapTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 4,
    keyboardType: KeyboardType = KeyboardType.Text,
    minLines: Int = 1,
    /** Takes focus, and so opens the keyboard, when it first shows. */
    autofocus: Boolean = false,
) {
    val shape = RoundedCornerShape(ZapRadius.control)
    val focus = remember { FocusRequester() }
    // Inside a sheet, wait for it to land: the keyboard and the slide-in would otherwise fight over the sheet's height.
    val settled = LocalSheetSettled.current
    if (autofocus) LaunchedEffect(settled) { if (settled) focus.requestFocus() }
    val style = MaterialTheme.typography.bodyMedium.copy(color = ZapText)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = style,
        minLines = minLines,
        maxLines = maxLines,
        singleLine = maxLines == 1,
        cursorBrush = SolidColor(ZapActionText),
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            keyboardType = keyboardType,
        ),
        modifier = modifier
            .focusRequester(focus)
            .heightIn(min = ZapSize.control)
            .background(ZapSurface1, shape)
            .border(1.dp, ZapLine, shape),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = ZapSpacing.space3, vertical = 11.dp)) {
                if (value.isEmpty()) Text(placeholder, style = style, color = ZapText3)
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
                    Text(stringResource(R.string.report_sent), color = ZapText2, style = MaterialTheme.typography.bodyMedium)
                    ZapButton(stringResource(R.string.close), onDismiss, Modifier.fillMaxWidth(), style = ZapButtonStyle.Tonal)
                }
                !social.signedIn -> {
                    Text(stringResource(R.string.report_sign_in), color = ZapText2, style = MaterialTheme.typography.bodyMedium)
                    ZapButton(
                        stringResource(R.string.sign_in_with_signer),
                        onSignIn,
                        Modifier.fillMaxWidth().height(ZapSize.controlLarge).testTag("reportSignIn"),
                    )
                }
                else -> {
                    Text(stringResource(R.string.report_body), color = ZapText2, style = MaterialTheme.typography.bodyMedium)
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
                                    colors = RadioButtonDefaults.colors(selectedColor = ZapAction, unselectedColor = ZapText3),
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
 * Zap the app's C1 owner, or else its catalog: a preset amount, an optional note, then Zap. Signed out,
 * it still works, with a throwaway key like any Nostr client's anonymous zap; it just says so. Paid
 * through a connected wallet when there is one, or else the invoice comes back to pay by hand.
 */
@Composable
internal fun ZapAppSheet(
    appName: String,
    social: SocialUi,
    recipient: ZapRecipient?,
    recipientName: String,
    initialSats: Long,
    onZap: (sats: Long, note: String) -> Unit,
    onSignIn: () -> Unit,
    onConnectWallet: (String) -> Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onPayInvoice: (String) -> Unit = {},
) {
    var sats by rememberSaveable(initialSats) { mutableLongStateOf(initialSats) }
    var note by rememberSaveable { mutableStateOf("") }
    var customAmount by rememberSaveable { mutableStateOf(false) }
    var customText by rememberSaveable { mutableStateOf("") }
    var askSignIn by rememberSaveable { mutableStateOf(false) }
    var pastingWallet by rememberSaveable { mutableStateOf(false) }
    var walletText by rememberSaveable { mutableStateOf("") }
    var walletError by remember { mutableStateOf<String?>(null) }
    val format = remember { NumberFormat.getInstance() }
    val invalidWallet = stringResource(R.string.wallet_invalid)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(social.signedIn) { if (social.signedIn) askSignIn = false }
    LaunchedEffect(social.walletConnected) { if (social.walletConnected) pastingWallet = false }
    ZapSheet(onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ZapSpacing.space5, end = ZapSpacing.space5, bottom = ZapSpacing.space5)
                .testTag("zapSheet"),
        ) {
            Text(stringResource(R.string.zap_title, appName), color = Color.White, style = MaterialTheme.typography.titleLarge)
            when {
                askSignIn -> SignInPanel(social, onSignIn)
                social.zapped != null -> {
                    Text(
                        stringResource(R.string.zap_sent, format.format(social.zapped), recipientName),
                        color = ZapText2,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("zapSent"),
                    )
                    ZapButton(stringResource(R.string.close), onDismiss, Modifier.fillMaxWidth(), style = ZapButtonStyle.Tonal)
                }
                social.invoice != null -> {
                    // No wallet is connected, so Android's own wallet chooser opens on the first tap.
                    LaunchedEffect(social.invoice) { onPayInvoice(social.invoice) }
                    Text(stringResource(R.string.zap_invoice_ready), color = ZapText2, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        social.invoice,
                        color = ZapText2,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ZapSurface1, RoundedCornerShape(ZapRadius.control))
                            .padding(horizontal = ZapSpacing.space3, vertical = 11.dp)
                            .testTag("zapInvoice"),
                    )
                    val invoice = social.invoice
                    ZapButton(
                        text = stringResource(R.string.zap_copy_invoice),
                        onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("invoice", invoice))) } },
                        style = ZapButtonStyle.Tonal,
                        modifier = Modifier.fillMaxWidth().height(ZapSize.controlLarge).testTag("zapCopyInvoice"),
                    )
                    if (pastingWallet) {
                        Text(stringResource(R.string.wallet_paste_body), color = ZapText2, style = MaterialTheme.typography.bodyMedium)
                        ZapTextArea(
                            value = walletText,
                            onValueChange = { walletText = it },
                            placeholder = "nostr+walletconnect://…",
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth().testTag("walletPasteField"),
                        )
                        walletError?.let { StatusText(it, Modifier.testTag("walletPasteError")) }
                        ZapButton(
                            text = stringResource(R.string.zap_open_wallet),
                            onClick = {
                                if (onConnectWallet(walletText)) pastingWallet = false
                                else walletError = invalidWallet
                            },
                            modifier = Modifier.fillMaxWidth().testTag("walletPasteSubmit"),
                        )
                    }
                    ZapButton(
                        text = stringResource(R.string.zap_pay_with_wallet),
                        onClick = { onPayInvoice(invoice) },
                        modifier = Modifier.fillMaxWidth().height(ZapSize.controlLarge).testTag("zapPayInvoice"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space4)) {
                        if (!pastingWallet) {
                            ZapTextLink(stringResource(R.string.zap_open_wallet), { pastingWallet = true }, Modifier.testTag("zapConnectWallet"))
                        }
                        ZapTextLink(stringResource(R.string.try_again), onRetry, Modifier.testTag("zapRetry"))
                    }
                }
                else -> {
                    if (!social.signedIn) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                            Text(
                                stringResource(R.string.zap_anonymous),
                                color = ZapText3,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            ZapTextLink(stringResource(R.string.zap_sign_in), { askSignIn = true }, Modifier.testTag("zapSignIn"))
                        }
                    }
                    Text(
                        if (recipient?.catalog == true) stringResource(R.string.zap_body_catalog, recipientName)
                        else stringResource(R.string.zap_body, recipientName),
                        color = ZapText2,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("zapBody"),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space2), verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2)) {
                        ZAP_AMOUNTS.forEach { option ->
                            ZapChip(
                                text = "${option.emoji} ${option.label}",
                                onClick = { sats = option.sats; customAmount = false },
                                selected = !customAmount && sats == option.sats,
                                modifier = Modifier.testTag("zapAmount:${option.sats}"),
                            )
                        }
                        ZapChip(
                            text = if (customAmount && sats > 0) "\uD83D\uDC8E ${format.format(sats)}" else stringResource(R.string.zap_custom),
                            onClick = { customAmount = true },
                            selected = customAmount,
                            modifier = Modifier.testTag("zapAmountCustom"),
                        )
                    }
                    if (customAmount) {
                        ZapTextArea(
                            value = customText,
                            onValueChange = { input ->
                                val digits = input.filter(Char::isDigit)
                                customText = digits
                                digits.toLongOrNull()?.let { sats = it }
                            },
                            placeholder = stringResource(R.string.zap_custom_placeholder),
                            maxLines = 1,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.fillMaxWidth().testTag("zapCustomAmount"),
                        )
                    }
                    ZapTextArea(note, { note = it }, stringResource(R.string.zap_note), Modifier.fillMaxWidth().testTag("zapNote"))
                    social.zapError?.let { StatusText(it, Modifier.testTag("zapError")) }
                    ZapButton(
                        text = stringResource(R.string.zap_send, format.format(sats)),
                        onClick = { onZap(sats, note) },
                        loading = social.zapping,
                        enabled = !social.zapping && sats > 0,
                        icon = R.drawable.ic_bolt,
                        modifier = Modifier.fillMaxWidth().height(ZapSize.controlLarge).testTag("zapSend"),
                    )
                }
            }
        }
    }
}


@Composable
internal fun SignInPanel(social: SocialUi, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.testTag("signInSheet"), verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3)) {
        Text(stringResource(R.string.sign_in_here), color = ZapText2, style = MaterialTheme.typography.bodyMedium)
        social.signInError?.let { StatusText(it, Modifier.testTag("signInError")) }
        ZapButton(
            text = stringResource(R.string.sign_in_with_signer),
            onClick = onSignIn,
            loading = social.signingIn,
            enabled = !social.signingIn,
            modifier = Modifier.fillMaxWidth().height(ZapSize.controlLarge).testTag("signInConfirm"),
        )
    }
}

private fun String.reportLabel(): Int = when (this) {
    "malware" -> R.string.report_malware
    "spam" -> R.string.report_spam
    "impersonation" -> R.string.report_impersonation
    "illegal" -> R.string.report_illegal
    else -> R.string.report_other
}
