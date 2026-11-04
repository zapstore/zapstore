package dev.zapstore.app.screens

import dev.zapstore.app.components.GroupHeader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.zapstore.app.R
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.components.SettingsGroup
import dev.zapstore.app.components.SettingsRow
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapIconButton
import dev.zapstore.app.ZapSpacing

/**
 * The Profile tab before sign-in: what an account adds, and the way in. Without a signer
 * installed it offers Amber from Zapstore instead. A gear opens the device's settings.
 */
@Composable
fun SignedOutProfile(
    hasSigner: Boolean,
    signingIn: Boolean,
    error: String?,
    onSignIn: () -> Unit,
    onGetAmber: () -> Unit,
    modifier: Modifier = Modifier,
    saved: SavedApps = SavedApps(),
    onSavedClick: () -> Unit = {},
    onSettings: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ZapCanvas)
            .verticalScroll(rememberScrollState())
            .padding(start = ZapSpacing.space4, end = ZapSpacing.space4, top = ZapSpacing.space2, bottom = ZapSpacing.space4)
            .testTag("signedOutProfile"),
        verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.profile), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            onSettings?.let {
                ZapIconButton(
                    icon = R.drawable.ic_settings,
                    contentDescription = stringResource(R.string.settings),
                    onClick = it,
                    style = ZapButtonStyle.Ghost,
                    modifier = Modifier.testTag("openSettings"),
                )
            }
        }
        if (saved.ids.isNotEmpty()) {
            GroupHeader(stringResource(R.string.stacks))
            SavedStackCard(saved, onSavedClick)
        }
        SettingsGroup(Modifier.testTag("profileSignIn")) {
            if (hasSigner) {
                SettingsRow(
                    title = stringResource(R.string.sign_in_with_signer),
                    subtitle = stringResource(R.string.account_explainer),
                    onClick = if (signingIn) null else onSignIn,
                    modifier = Modifier.testTag("profileSignInRow"),
                )
            } else {
                SettingsRow(
                    title = stringResource(R.string.get_amber),
                    subtitle = stringResource(R.string.account_explainer) + " " + stringResource(R.string.get_amber_hint),
                    onClick = onGetAmber,
                    modifier = Modifier.testTag("getAmber"),
                )
            }
        }
        error?.let { StatusText(it, Modifier.testTag("profileSignInError")) }
    }
}
