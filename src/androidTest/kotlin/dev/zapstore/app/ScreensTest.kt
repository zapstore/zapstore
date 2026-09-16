package dev.zapstore.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeSearchAndStackCallbacksAreWired() {
        var submitted = false
        var cleared = false
        var updatesOpened = false
        var openedStack: String? = null
        val stack = StackInfo(
            event(
                id = "stack-id",
                kind = Catalog.appStackKind,
                tags = arrayOf(arrayOf("name", "Privacy")),
            ),
        )

        composeRule.setContent {
            ZapstoreTheme {
                HomeScreen(
                    state = HomeUiState(
                        searchQuery = "zap",
                        stacks = listOf(stack),
                        stacksLoading = false,
                    ),
                    onSearchQueryChanged = {},
                    onSearchSubmitted = { submitted = true },
                    onSearchCleared = { cleared = true },
                    onUpdatesClick = { updatesOpened = true },
                    onStackClick = { openedStack = it },
                    onAppClick = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("searchField").performImeAction()
        composeRule.onNodeWithText("×").performClick()
        composeRule.onNodeWithTag("searchField").assertIsFocused()
        composeRule.onNodeWithTag("updatesButton").performClick()
        composeRule.onNodeWithTag("stack:stack-id").performClick()

        assertTrue(submitted)
        assertTrue(cleared)
        assertTrue(updatesOpened)
        assertEquals("stack-id", openedStack)
    }

    @Test
    fun appDetailExternalLinkCallbackIsWired() {
        var openedUrl: String? = null
        val app = AppInfo(
            event(
                kind = Catalog.appKind,
                tags = arrayOf(
                    arrayOf("d", "zap"),
                    arrayOf("name", "Zap"),
                    arrayOf("repository", "https://example.com/repo"),
                ),
            ),
        )

        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app,
                        appLoading = false,
                        releaseLoading = false,
                    ),
                    onOpenUrl = { openedUrl = it },
                )
            }
        }

        composeRule.onNodeWithText("https://example.com/repo").performClick()

        assertEquals("https://example.com/repo", openedUrl)
    }

    @Test
    fun appProfileCallbackIsWired() {
        var openedProfile: String? = null
        val app = AppInfo(event(kind = Catalog.appKind), hasVerifiedC1 = true)

        composeRule.setContent {
            ZapstoreTheme {
                AppCard(
                    app = app,
                    repository = null,
                    onClick = {},
                    onProfileClick = { openedProfile = app.event.pubKey },
                )
            }
        }

        composeRule.onNodeWithTag("profile:${app.event.pubKey}").performClick()

        assertEquals(app.event.pubKey, openedProfile)
    }

    @Test
    fun releaseFeedShowsItsLoadingFooter() {
        val app = AppInfo(
            event(
                id = "release-id",
                kind = Catalog.appKind,
            ),
        )
        val release = ReleaseInfo(
            event(
                id = "release-id",
                kind = Catalog.releaseKind,
                tags = arrayOf(arrayOf("i", "zap")),
            ),
        )

        composeRule.setContent {
            ZapstoreTheme {
                HomeScreen(
                    state = HomeUiState(
                        releaseFeed = ReleaseFeedUiState(
                            entries = listOf(ReleaseFeedEntry(app, release)),
                            initialLoading = false,
                            loadingMore = true,
                        ),
                    ),
                    onSearchQueryChanged = {},
                    onSearchSubmitted = {},
                    onSearchCleared = {},
                    onStackClick = {},
                    onAppClick = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("app:release-id").assertIsDisplayed()
        composeRule.onNodeWithTag("releaseLoading").assertIsDisplayed()
    }
}

private fun event(
    id: String = "a".repeat(64),
    kind: Int,
    tags: Array<Array<String>> = emptyArray(),
): Event = Event(
    id = id,
    pubKey = "1".repeat(64),
    createdAt = 1_750_000_000,
    kind = kind,
    tags = tags,
    content = "",
    sig = "2".repeat(128),
)
