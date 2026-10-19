package dev.zapstore.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun skippingOnboardingLandsAtHome() {
        repeat(MAX_STEPS) {
            if (composeRule.onAllNodesWithTag("onboardingSkip").fetchSemanticsNodes().isEmpty()) return@repeat
            composeRule.onNodeWithTag("onboardingSkip").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("searchField").assertIsDisplayed()
    }

    private companion object {
        const val MAX_STEPS = 5
    }
}
