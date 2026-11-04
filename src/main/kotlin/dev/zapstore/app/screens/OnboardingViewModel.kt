package dev.zapstore.app.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.zapstore.app.AppLog
import dev.zapstore.app.Onboarding
import dev.zapstore.app.OnboardingStep
import dev.zapstore.app.R
import dev.zapstore.app.ZapstoreApplication
import dev.zapstore.app.transport.NetworkMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignerStepUi(val busy: Boolean = false, val message: String? = null)

data class OnboardingUiState(
    val steps: List<OnboardingStep> = emptyList(),
    val index: Int = 0,
    val networkMode: NetworkMode = NetworkMode.TorOnly,
    val signer: SignerStepUi = SignerStepUi(),
    /** False when no NIP-55 signer is installed; the Signer step then offers Amber. */
    val hasSigner: Boolean = true,
    val finished: Boolean = false,
) {
    val step: OnboardingStep? get() = steps.getOrNull(index)
}

class OnboardingViewModel(private val zapstore: ZapstoreApplication) : ViewModel() {
    private val onboarding = zapstore.onboarding
    private val progress = MutableStateFlow(initial())
    private val signer = MutableStateFlow(SignerStepUi())

    val uiState: StateFlow<OnboardingUiState> = combine(progress, signer, zapstore.settings.state) { progress, signer, settings ->
        progress.copy(networkMode = settings.networkMode, signer = signer)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, progress.value)

    private fun initial(): OnboardingUiState {
        val steps = onboarding.plan {
            Onboarding.steps(
                migrated = zapstore.migratedFromFlutter,
                canInstall = zapstore.packageManager.canRequestPackageInstalls(),
                needsNotificationPermission = needsNotificationPermission(),
            )
        }
        val completed = onboarding.completed()
        val index = steps.indexOfFirst { it !in completed }
        if (index < 0) onboarding.finish()
        return OnboardingUiState(
            steps = steps,
            index = index.coerceAtLeast(0),
            hasSigner = zapstore.account.bridge.installedSigners().isNotEmpty(),
            finished = index < 0,
        )
    }

    /** Continue and the Signer step's Cancel both move on; neither changes any setting already made. */
    fun next() {
        val state = progress.value
        state.step?.let(onboarding::complete)
        val index = state.index + 1
        if (index >= state.steps.size) onboarding.finish()
        progress.update { it.copy(index = index, finished = index >= it.steps.size) }
        signer.value = SignerStepUi()
    }

    /** Ends onboarding right away, leaving any remaining steps (and their settings) untouched. */
    fun skipAll() {
        onboarding.finish()
        progress.update { it.copy(finished = true) }
    }

    fun setNetworkMode(mode: NetworkMode) = zapstore.settings.update { it.copy(networkMode = mode) }

    /**
     * Signs in, then uses the newest key backup for this account. With none, backs the phone's
     * key up. Using a backup restarts the app on that key.
     */
    fun connectSigner() {
        if (signer.value.busy) return
        signer.value = SignerStepUi(busy = true)
        viewModelScope.launch {
            runCatching {
                zapstore.account.signIn()
                val backup = zapstore.keyBackup
                val found = backup.fetchBackup()
                when {
                    found == null -> backup.backUp()
                    !backup.isCurrent(found) -> {
                        onboarding.complete(OnboardingStep.Signer)
                        backup.restore(found)
                    }
                }
            }.onSuccess {
                next()
            }.onFailure { error ->
                AppLog.w(TAG, "signer step failed", error)
                signer.value = SignerStepUi(message = error.message ?: zapstore.getString(R.string.key_action_failed))
            }
        }
    }

    /** Moves past a permission step once Android reports it granted. */
    fun refreshPermissions() {
        when (progress.value.step) {
            OnboardingStep.InstallPermission -> if (zapstore.packageManager.canRequestPackageInstalls()) next()
            OnboardingStep.Notifications -> if (!needsNotificationPermission()) next()
            else -> Unit
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            zapstore.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "Onboarding"

        fun factory(app: ZapstoreApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { OnboardingViewModel(app) }
        }
    }
}
