package dev.zapstore.app

import dev.zapstore.app.OnboardingStep.InstallPermission
import dev.zapstore.app.OnboardingStep.Network
import dev.zapstore.app.OnboardingStep.Notifications
import dev.zapstore.app.OnboardingStep.Privacy
import dev.zapstore.app.OnboardingStep.Signer
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingTest {
    @Test
    fun newPhoneSeesEveryStepItNeeds() {
        assertEquals(
            listOf(Privacy, Network, Signer, InstallPermission, Notifications),
            Onboarding.steps(migrated = false, canInstall = false, needsNotificationPermission = true),
        )
    }

    @Test
    fun grantedPermissionsDropTheirStepsAndTheSignerStepStays() {
        assertEquals(
            listOf(Privacy, Network, Signer),
            Onboarding.steps(migrated = false, canInstall = true, needsNotificationPermission = false),
        )
    }

    @Test
    fun flutterPhoneSeesOnlyNetworkAndNotifications() {
        assertEquals(
            listOf(Network, Notifications),
            Onboarding.steps(migrated = true, canInstall = false, needsNotificationPermission = true),
        )
    }
}
