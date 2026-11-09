package dev.zapstore.app.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import dev.zapstore.app.OnboardingStep
import dev.zapstore.app.R
import dev.zapstore.app.ZapAction
import dev.zapstore.app.ZapCanvas
import dev.zapstore.app.ZapRadius
import dev.zapstore.app.ZapSpacing
import dev.zapstore.app.ZapSurface3
import dev.zapstore.app.ZapText
import dev.zapstore.app.ZapText2
import dev.zapstore.app.components.StatusText
import dev.zapstore.app.components.ZapButton
import dev.zapstore.app.components.ZapSegmented
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
    onBugReportsChange: (Boolean) -> Unit = {},
    onBack: () -> Unit = {},
) {
    // Past the last step the last slide stays up, so finishing never blanks the screen before what follows.
    val step = state.step ?: state.steps.lastOrNull() ?: return
    val animate = animationsEnabled()
    // System back goes to the slide before. Not while signing in: that moves on by itself when it lands.
    BackHandler(enabled = state.index > 0 && !state.signer.busy, onBack = onBack)
    Column(
        modifier
            .fillMaxSize()
            .background(ZapCanvas)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = ZapSpacing.space6, vertical = ZapSpacing.space4)
            .testTag("onboarding"),
    ) {
        // The mark and the steps on the left, Skip all on the right.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Image(
                painterResource(R.drawable.ic_mark),
                contentDescription = null,
                modifier = Modifier.size(width = 20.dp, height = 34.dp).testTag("onboardingLogo"),
            )
            StepIndicator(
                state.index.coerceAtMost(state.steps.lastIndex),
                state.steps.size,
                Modifier.padding(start = ZapSpacing.space4).weight(1f),
            )
            // Not on the first slide: the catalog download waits for the connection choice made there.
            if (step != OnboardingStep.Privacy) {
                ZapTextLink(stringResource(R.string.onboarding_skip_all), onSkipAll, Modifier.testTag("onboardingSkip"))
            }
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
                val returning = shown == OnboardingStep.Privacy && state.upgradedFromFlutter
                Text(
                    stringResource(
                        when {
                            returning -> R.string.onboarding_upgraded_title
                            else -> shown.title()
                        },
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
                StepBody(
                    stringResource(
                        when {
                            returning -> R.string.onboarding_upgraded_body
                            else -> shown.body()
                        },
                    ),
                )
                if (shown == OnboardingStep.Privacy) {
                    NetworkPicker(
                        stringResource(R.string.onboarding_network_title),
                        state.networkMode,
                        onNetworkModeChange,
                        Modifier.padding(top = ZapSpacing.space2),
                    )
                }
                if (shown == OnboardingStep.Signer) {
                    state.signer.message?.let { StatusText(it, Modifier.testTag("signerStatus")) }
                }
            }
        }
        val (label, action) = when (step) {
            OnboardingStep.Privacy, OnboardingStep.Network -> R.string.onboarding_continue to onNext
            OnboardingStep.BugReports -> R.string.onboarding_send_bug_reports to { onBugReportsChange(true); onNext() }
            OnboardingStep.Signer -> R.string.onboarding_connect_signer to onConnectSigner
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
                text = stringResource(R.string.onboarding_not_now),
                onClick = onNext,
                style = ZapButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth().padding(top = ZapSpacing.space1).testTag("onboardingCancelSigner"),
            )
        }
        if (step == OnboardingStep.InstallPermission) {
            ZapButton(
                text = stringResource(R.string.onboarding_not_now),
                onClick = onNext,
                style = ZapButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth().padding(top = ZapSpacing.space1).testTag("onboardingNotNowInstall"),
            )
        }
        if (step == OnboardingStep.Notifications) {
            ZapButton(
                text = stringResource(R.string.onboarding_not_now),
                onClick = onNext,
                style = ZapButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth().padding(top = ZapSpacing.space1).testTag("onboardingNotNow"),
            )
        }
        if (step == OnboardingStep.BugReports) {
            ZapButton(
                text = stringResource(R.string.onboarding_no_bug_reports),
                onClick = { onBugReportsChange(false); onNext() },
                style = ZapButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth().padding(top = ZapSpacing.space1).testTag("onboardingDeclineBugReports"),
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

/** Paragraphs split on blank lines; `<b>` in the string is bold, in the primary text color. */
@Composable
internal fun StepBody(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space4)) {
        text.split("\n\n").forEach { paragraph ->
            Text(
                AnnotatedString.fromHtml(paragraph.trim()).let { html ->
                    buildAnnotatedString {
                        append(html)
                        // Emphasis is the title weight with the title's tracking. DM Sans at 700 and a text opsz sets wide.
                        html.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
                            .forEach { addStyle(SpanStyle(color = ZapText, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em), it.start, it.end) }
                    }
                },
                // 10% under Body: these are short paragraphs on a screen of their own, not running text.
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.3.sp, lineHeight = 23.sp),
                color = ZapText2,
            )
        }
    }
}

/**
 * How Zapstore connects: Tor or Direct in one small control, and one line on what the chosen one means.
 * Small enough to sit under a paragraph, so it never takes a slide or a screen of its own.
 */
@Composable
internal fun NetworkPicker(
    title: String,
    selected: NetworkMode,
    onSelect: (NetworkMode) -> Unit,
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZapSpacing.space2), modifier = modifier.fillMaxWidth()) {
        Text(
            title,
            style = if (quiet) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
            color = if (quiet) ZapText2 else ZapText,
        )
        ZapSegmented(
            options = listOf(NetworkMode.TorOnly, NetworkMode.DirectOnly),
            selected = selected,
            onSelect = onSelect,
            label = { stringResource(if (it == NetworkMode.TorOnly) R.string.network_tor else R.string.network_direct) },
            tag = { if (it == NetworkMode.TorOnly) "onboardingTor" else "onboardingDirect" },
        )
        Text(
            stringResource(if (selected == NetworkMode.TorOnly) R.string.onboarding_tor_body else R.string.onboarding_direct_body),
            style = MaterialTheme.typography.bodySmall,
            color = ZapText2,
            modifier = Modifier.testTag("networkCaption"),
        )
    }
}

private fun OnboardingStep.title(): Int = when (this) {
    OnboardingStep.Privacy, OnboardingStep.Network -> R.string.onboarding_privacy_title
    OnboardingStep.BugReports -> R.string.onboarding_bug_reports_title
    OnboardingStep.Signer -> R.string.onboarding_signer_title
    OnboardingStep.InstallPermission -> R.string.onboarding_install_title
    OnboardingStep.Notifications -> R.string.onboarding_notifications_title
}

private fun OnboardingStep.body(): Int = when (this) {
    OnboardingStep.Privacy, OnboardingStep.Network -> R.string.onboarding_privacy_body
    OnboardingStep.BugReports -> R.string.onboarding_bug_reports_body
    OnboardingStep.Signer -> R.string.onboarding_signer_body
    OnboardingStep.InstallPermission -> R.string.onboarding_install_body
    OnboardingStep.Notifications -> R.string.onboarding_notifications_body
}
