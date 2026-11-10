package dev.zapstore.app.screens

import dev.zapstore.app.components.GroupHeader
import dev.zapstore.app.components.LocalSocial
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapText2
import dev.zapstore.app.ZapText3

/**
 * The Profile tab before sign-in: what an account adds, and the way in. Without a signer
 * installed it offers Amber from Zapstore instead. With social off the sign-in row is shown disabled,
 * with a note. A Settings row opens the device's settings.
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
        Text(stringResource(R.string.profile), style = MaterialTheme.typography.headlineSmall)
        if (saved.ids.isNotEmpty()) {
            GroupHeader(stringResource(R.string.stacks))
            // Signed out there is no account to curate other stacks, so this shelf only ever carries
            // the device's own Saved list, shown the same way Home shows anyone's.
            StacksShelf(stacks = emptyList(), apps = emptyMap(), onStackClick = {}, saved = saved, onSavedClick = onSavedClick)
        }
        val social = LocalSocial.current
        SettingsGroup(Modifier.testTag("profileSignIn")) {
            if (!social) {
                // With social off there is no account: the row stays so the way back is visible, but it does nothing.
                SettingsRow(
                    title = stringResource(if (hasSigner) R.string.sign_in_with_signer else R.string.get_amber),
                    subtitle = stringResource(R.string.sign_in_needs_social),
                    titleColor = ZapText3,
                    modifier = Modifier.testTag("profileSignInOff"),
                )
            } else if (hasSigner) {
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
        if (social) error?.let { StatusText(it, Modifier.testTag("profileSignInError")) }
        onSettings?.let { SettingsEntry(it) }
    }
}

/**
 * The way into Settings, as a labelled row on the Profile tab. A lone gear in a corner went unnoticed,
 * so the row says what is behind it.
 */
@Composable
internal fun SettingsEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SettingsGroup(modifier.testTag("openSettings")) {
        SettingsRow(
            title = stringResource(R.string.settings),
            subtitle = stringResource(R.string.settings_entry_hint),
            onClick = onClick,
            leading = { Icon(painterResource(R.drawable.ic_settings), null, tint = ZapText2, modifier = Modifier.size(22.dp)) },
        )
    }
}
