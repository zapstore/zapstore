package dev.zapstore.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.zapstore.app.R
import dev.zapstore.app.components.ZapButtonStyle

@Composable
fun UninstallProfilesDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmSheet(
        title = stringResource(R.string.uninstall_title, name),
        body = listOf(stringResource(R.string.uninstall_other_profiles)),
        confirm = stringResource(R.string.uninstall),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmStyle = ZapButtonStyle.Danger,
        confirmTag = "uninstallConfirm",
    )
}
