package dev.zapstore.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.zapstore.app.R
import dev.zapstore.app.components.ZapButtonStyle

/**
 * Before swapping an app for the catalog's build signed by another key: Android has to uninstall the
 * old copy first, and its data goes with it. [otherProfiles] adds that a copy in another profile blocks it.
 */
@Composable
fun ReinstallDialog(
    name: String,
    otherProfiles: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmSheet(
        title = stringResource(R.string.reinstall_title, name),
        body = listOfNotNull(
            stringResource(R.string.reinstall_body),
            stringResource(R.string.reinstall_data, name),
            stringResource(R.string.uninstall_other_profiles).takeIf { otherProfiles },
        ),
        confirm = stringResource(R.string.reinstall_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmStyle = ZapButtonStyle.Danger,
        confirmTag = "reinstallConfirm",
    )
}
