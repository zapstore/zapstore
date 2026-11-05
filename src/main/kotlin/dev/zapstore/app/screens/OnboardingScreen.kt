package dev.zapstore.app.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.zapstore.app.OnboardingStep
import dev.zapstore.app.R
import dev.zapstore.app.ZapAction
import dev.zapstore.app.ZapActionTint
import dev.zapstore.app.ZapBrand
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapLine
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface1
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapButtonStyle
import dev.zapstore.app.components.ZapMotion
import dev.zapstore.app.components.ZapTextLink
import dev.zapstore.app.components.animationsEnabled
import dev.zapstore.app.transport.NetworkMode

/**
 * First-launch steps, one per screen. Skip all ends onboarding right away. The Signer step has
 * its own Cancel, since it is the only step Skip all cannot otherwise bypass individually.
 * Permission steps hand off to Android and move on by themselves once the permission is granted.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onNext: () -> Unit,
    onSkipAll: () -> Unit,
    onNetworkModeChange: (NetworkMode) -> Unit,
    onConnectSigner: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    modifier: Modifier = Modifier,
    onGetAmber: () -> Unit = onNext,
) {
    val step = state.step ?: return
    val animate = animationsEnabled()
    Column(
        modifier
            .fillMaxSize()
            .background(ZapCanvas)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = ZapSpacing.space6, vertical = ZapSpacing.space4)
            .testTag("onboarding"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            StepIndicator(state.index, state.steps.size, Modifier.weight(1f))
            ZapTextLink(stringResource(R.string.onboarding_skip_all), onSkipAll, Modifier.testTag("onboardingSkip"))
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (animate) {
                    (slideInHorizontally(ZapMotion.base()) { it / 6 } + fadeIn(ZapMotion.base()))
                        .togetherWith(slideOutHorizontally(ZapMotion.fast()) { -it / 6 } + fadeOut(ZapMotion.fast()))
                } else {
                    fadeIn(snap()).togetherWith(fadeOut(snap()))
                }
            },
            label = "onboardingStep",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { shown ->
            Column(
                verticalArrangement = Arrangement.spacedBy(ZapSpacing.space4),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = ZapSpacing.space8, bottom = ZapSpacing.space4)
                    .testTag("onboarding:${shown.name}"),
            ) {
                val noSigner = shown == OnboardingStep.Signer && !state.hasSigner
                StepIcon(shown.icon(), logo = shown == OnboardingStep.Privacy)
                Text(
                    stringResource(if (noSigner) R.string.onboarding_get_signer_title else shown.title()),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(if (noSigner) R.string.onboarding_get_signer_body else shown.body()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = ZapText2,
                )
                if (shown == OnboardingStep.Network) {
                    NetworkChoice(state.networkMode, onNetworkModeChange)
                }
                if (shown == OnboardingStep.Signer) {
                    state.signer.message?.let { StatusText(it, Modifier.testTag("signerStatus")) }
                }
            }
        }
        val (label, action) = when (step) {
            OnboardingStep.Privacy, OnboardingStep.Network -> R.string.onboarding_continue to onNext
            OnboardingStep.Signer -> if (state.hasSigner) R.string.onboarding_connect_signer to onConnectSigner else R.string.onboarding_get_amber to onGetAmber
            OnboardingStep.InstallPermission -> R.string.onboarding_open_settings to onOpenInstallSettings
            OnboardingStep.Notifications -> R.string.onboarding_allow_notifications to onRequestNotifications
        }
        ZapButton(
            text = stringResource(label),
            onClick = action,
            loading = step == OnboardingStep.Signer && state.signer.busy,
            modifier = Modifier.fillMaxWidth().testTag("onboardingPrimary"),
        )
        if (step == OnboardingStep.Signer) {
            ZapButton(
                text = stringResource(R.string.onboarding_cancel),
                onClick = onNext,
                style = ZapButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth().padding(top = ZapSpacing.space1).testTag("onboardingCancelSigner"),
            )
        }
    }
}

/** One short bar per step; done and current bars are filled. */
@Composable
private fun StepIndicator(index: Int, count: Int, modifier: Modifier = Modifier) {
    val animate = animationsEnabled()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1)) {
        Text(
            stringResource(R.string.onboarding_step, index + 1, count),
            style = MaterialTheme.typography.bodySmall,
            color = ZapText2,
            modifier = Modifier.testTag("onboardingCount"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space1)) {
            repeat(count) { position ->
                val color by animateColorAsState(
                    if (position <= index) ZapAction else ZapSurface3,
                    if (animate) ZapMotion.base() else snap(),
                    label = "stepBar",
                )
                Box(
                    Modifier
                        .size(width = 20.dp, height = 4.dp)
                        .clip(RoundedCornerShape(ZapRadius.bar))
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun StepIcon(icon: Int, logo: Boolean = false) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(ZapRadius.md))
            .background(if (logo) ZapBrand else ZapActionTint),
    ) {
        if (logo) {
            Image(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(width = 20.dp, height = 34.dp),
            )
        } else {
            Icon(painterResource(icon), contentDescription = null, tint = ZapAction, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun NetworkChoice(selected: NetworkMode, onSelect: (NetworkMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space3), modifier = Modifier.selectableGroup()) {
        NetworkOption(
            title = stringResource(R.string.onboarding_tor_title),
            body = stringResource(R.string.onboarding_tor_body),
            selected = selected == NetworkMode.TorOnly,
            onClick = { onSelect(NetworkMode.TorOnly) },
            tag = "onboardingTor",
        )
        NetworkOption(
            title = stringResource(R.string.network_direct),
            body = stringResource(R.string.onboarding_direct_body),
            selected = selected == NetworkMode.DirectOnly,
            onClick = { onSelect(NetworkMode.DirectOnly) },
            tag = "onboardingDirect",
        )
    }
}

@Composable
private fun NetworkOption(title: String, body: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    val animate = animationsEnabled()
    val border by animateColorAsState(if (selected) ZapAction else ZapLine, if (animate) ZapMotion.base() else snap(), label = "optionBorder")
    val shape = RoundedCornerShape(ZapRadius.md)
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZapSpacing.space3),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) ZapActionTint else ZapSurface1)
            .border(1.dp, border, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(ZapSpacing.space4)
            .testTag(tag),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
                .clip(RoundedCornerShape(ZapRadius.full))
                .border(2.dp, if (selected) ZapAction else ZapText2, RoundedCornerShape(ZapRadius.full)),
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(RoundedCornerShape(ZapRadius.full)).background(ZapAction))
        }
        Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space1), modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ZapText)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = ZapText2)
        }
    }
}

private fun OnboardingStep.icon(): Int = when (this) {
    OnboardingStep.Privacy -> R.drawable.ic_mark
    OnboardingStep.Network -> R.drawable.ic_shield
    OnboardingStep.Signer -> R.drawable.ic_key
    OnboardingStep.InstallPermission -> R.drawable.ic_download
    OnboardingStep.Notifications -> R.drawable.ic_update
}

private fun OnboardingStep.title(): Int = when (this) {
    OnboardingStep.Privacy -> R.string.onboarding_privacy_title
    OnboardingStep.Network -> R.string.onboarding_network_title
    OnboardingStep.Signer -> R.string.onboarding_signer_title
    OnboardingStep.InstallPermission -> R.string.onboarding_install_title
    OnboardingStep.Notifications -> R.string.onboarding_notifications_title
}

private fun OnboardingStep.body(): Int = when (this) {
    OnboardingStep.Privacy -> R.string.onboarding_privacy_body
    OnboardingStep.Network -> R.string.onboarding_network_body
    OnboardingStep.Signer -> R.string.onboarding_signer_body
    OnboardingStep.InstallPermission -> R.string.onboarding_install_body
    OnboardingStep.Notifications -> R.string.onboarding_notifications_body
}
