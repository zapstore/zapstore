package dev.zapstore.app

import android.content.Context

enum class OnboardingStep { Privacy, Network, BugReports, Signer, InstallPermission, Notifications }

/**
 * First-launch steps. The plan is fixed when the flow first opens, so the step count stays the
 * same across a restart (restoring a key from the signer restarts the process mid-flow).
 */
class Onboarding(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val done: Boolean get() = prefs.getBoolean(DONE, false)

    /** The planned steps, or a new plan from [plan] the first time. */
    fun plan(plan: () -> List<OnboardingStep>): List<OnboardingStep> {
        prefs.getString(PLAN, null)?.let { saved ->
            return saved.split(',').mapNotNull { name -> OnboardingStep.entries.firstOrNull { it.name == name } }
        }
        return plan().also { steps -> prefs.edit().putString(PLAN, steps.joinToString(",") { it.name }).apply() }
    }

    fun completed(): Set<OnboardingStep> =
        prefs.getStringSet(COMPLETED, emptySet()).orEmpty().mapNotNull { name -> OnboardingStep.entries.firstOrNull { it.name == name } }.toSet()

    /** Committed synchronously: the signer step restarts the process right after. */
    fun complete(step: OnboardingStep) {
        prefs.edit().putStringSet(COMPLETED, completed().map { it.name }.toSet() + step.name).commit()
    }

    fun finish() {
        prefs.edit().putBoolean(DONE, true).apply()
    }

    companion object {
        private const val PREFS = "onboarding"
        private const val DONE = "done"
        private const val PLAN = "plan"
        private const val COMPLETED = "completed"

        /**
         * The steps this phone shows. A phone that carried a Flutter device key still starts on the
         * privacy screen, then sees network, bug reports, and notifications; signer and install
         * permission stay skipped. Without a signer the Signer step offers Amber instead.
         */
        fun steps(
            migrated: Boolean,
            canInstall: Boolean,
            needsNotificationPermission: Boolean,
        ): List<OnboardingStep> = buildList {
            add(OnboardingStep.Privacy)
            add(OnboardingStep.Network)
            add(OnboardingStep.BugReports)
            if (!migrated) add(OnboardingStep.Signer)
            if (!migrated && !canInstall) add(OnboardingStep.InstallPermission)
            if (needsNotificationPermission) add(OnboardingStep.Notifications)
        }
    }
}
