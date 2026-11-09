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
        // The welcome slide has no Skip all: the catalog download waits for the connection choice made on it.
        // A phone that already finished onboarding opens on Home, and there is nothing to click through.
        if (composeRule.onAllNodesWithTag("onboardingPrimary").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("onboardingPrimary").performClick()
            composeRule.waitForIdle()
        }
        if (composeRule.onAllNodesWithTag("onboardingSkip").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("onboardingSkip").performClick()
        }
        // A first install opens on the catalog screen until the catalog is in; Home follows by itself.
        composeRule.waitUntil(CATALOG_WAIT_MILLIS) {
            composeRule.onAllNodesWithTag("searchField").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("searchField").assertIsDisplayed()
    }

    private companion object {
        const val CATALOG_WAIT_MILLIS = 120_000L
    }
}
