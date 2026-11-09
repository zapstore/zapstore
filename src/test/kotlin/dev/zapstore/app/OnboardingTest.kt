package dev.zapstore.app

import dev.zapstore.app.OnboardingStep.BugReports
import dev.zapstore.app.OnboardingStep.InstallPermission
import dev.zapstore.app.OnboardingStep.Network
import dev.zapstore.app.OnboardingStep.Notifications
import dev.zapstore.app.OnboardingStep.Privacy
import dev.zapstore.app.OnboardingStep.Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OnboardingTest {
    @Test
    fun newPhoneSeesEveryStepItNeeds() {
        assertEquals(
            listOf(Privacy, BugReports, Signer, InstallPermission, Notifications),
            Onboarding.steps(migrated = false, canInstall = false, needsNotificationPermission = true, hasSigner = true),
        )
    }

    @Test
    fun grantedPermissionsDropTheirStepsAndTheSignerStepStays() {
        assertEquals(
            listOf(Privacy, BugReports, Signer),
            Onboarding.steps(migrated = false, canInstall = true, needsNotificationPermission = false, hasSigner = true),
        )
    }

    @Test
    fun flutterPhoneStartsOnPrivacyThenBugReportsAndNotifications() {
        assertEquals(
            listOf(Privacy, BugReports, Notifications),
            Onboarding.steps(migrated = true, canInstall = false, needsNotificationPermission = true, hasSigner = true),
        )
    }

    @Test
    fun theConnectionChoiceHasNoSlideOfItsOwn() {
        // It sits on the first slide, so the plan never lists Network, whatever the phone.
        listOf(false, true).forEach { migrated ->
            assertFalse(
                Network in Onboarding.steps(migrated = migrated, canInstall = false, needsNotificationPermission = true, hasSigner = true),
            )
        }
    }

    @Test
    fun bugReportsStayOffUntilChosen() {
        assertFalse(DeviceState().bugReports)
    }

    @Test
    fun aPhoneWithNoSignerHasNoSignerStep() {
        assertEquals(
            listOf(Privacy, BugReports, InstallPermission, Notifications),
            Onboarding.steps(migrated = false, canInstall = false, needsNotificationPermission = true, hasSigner = false),
        )
    }
}
