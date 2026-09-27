package dev.zapstore.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import dev.zapstore.app.catalog.AvailableUpdate
import dev.zapstore.app.catalog.SyncStatus
import dev.zapstore.app.components.AppCard
import dev.zapstore.app.components.AppListState
import dev.zapstore.app.screens.AppDetailScreen
import dev.zapstore.app.screens.AppDetailUiState
import dev.zapstore.app.screens.CommentsError
import dev.zapstore.app.screens.CommentsState
import dev.zapstore.app.screens.threadComments
import dev.zapstore.app.screens.HomeScreen
import dev.zapstore.app.screens.HomeUiState
import dev.zapstore.app.screens.SettingsScreen
import dev.zapstore.app.screens.SettingsUiState
import dev.zapstore.app.screens.UpdatesScreen
import dev.zapstore.app.screens.UpdatesUiState
import dev.zapstore.iolite.AppCoordinate
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.StackRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeCallbacksAreWired() {
        var cleared = false
        var updatesOpened = false
        var openedStack: String? = null
        var openedApp: String? = null
        val stack = StackRecord(
            pubkey = AUTHOR,
            identifier = "privacy",
            eventId = "e".repeat(64),
            name = "Privacy",
            description = "",
            apps = listOf(AppCoordinate(AUTHOR, "dev.zap")),
            updatedAt = 1_750_000_000,
        )
        val app = app("dev.zap", "Zap")

        composeRule.setContent {
            ZapstoreTheme {
                HomeScreen(
                    state = HomeUiState(
                        searchQuery = "zap",
                        submittedQuery = "zap",
                        searchResults = listOf(app),
                        stacks = listOf(stack),
                        stacksLoading = false,
                        feed = AppListState(loading = false),
                    ),
                    onSearchQueryChanged = {},
                    onSearchSubmitted = {},
                    onSearchCleared = { cleared = true },
                    onAppClick = { openedApp = it.appId },
                    onStackClick = { openedStack = it.identifier },
                    onUpdatesClick = { updatesOpened = true },
                )
            }
        }

        composeRule.onNodeWithTag("clearSearch").performClick()
        composeRule.onNodeWithTag("updatesButton").performClick()
        composeRule.onNodeWithTag("stack:privacy").performClick()
        // The card centre lands on the author byline, so trigger the card's own click action.
        composeRule.onNodeWithTag("searchResult:dev.zap").performSemanticsAction(SemanticsActions.OnClick)

        assertTrue(cleared)
        assertTrue(updatesOpened)
        assertEquals("privacy", openedStack)
        assertEquals("dev.zap", openedApp)
    }

    @Test
    fun homeFeedShowsItsLoadingFooter() {
        composeRule.setContent {
            ZapstoreTheme {
                HomeScreen(
                    state = HomeUiState(
                        stacksLoading = false,
                        feed = AppListState(apps = listOf(app("dev.zap", "Zap")), loading = false, loadingMore = true),
                    ),
                    onSearchQueryChanged = {},
                    onSearchSubmitted = {},
                    onSearchCleared = {},
                    onAppClick = {},
                    onStackClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("app:dev.zap").assertIsDisplayed()
        composeRule.onNodeWithTag("appListLoading").assertIsDisplayed()
    }

    @Test
    fun homeFeedRequestsMoreWhenScrolledNearEnd() {
        var loadMoreCount = 0
        val apps = (0 until 20).map { app("app.$it", "App $it") }
        composeRule.setContent {
            ZapstoreTheme {
                HomeScreen(
                    state = HomeUiState(
                        stacksLoading = false,
                        feed = AppListState(apps = apps, loading = false, canLoadMore = true),
                    ),
                    onSearchQueryChanged = {},
                    onSearchSubmitted = {},
                    onSearchCleared = {},
                    onAppClick = {},
                    onStackClick = {},
                    onLoadMore = { loadMoreCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("homeList").performScrollToKey("app:app.19")
        composeRule.waitUntil { loadMoreCount > 0 }
        assertTrue(loadMoreCount > 0)
    }

    @Test
    fun appDetailExternalLinkCallbackIsWired() {
        var openedUrl: String? = null
        val app = app("dev.zap", "Zap", repository = "https://example.com/repo")

        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(app = app, loaded = true),
                    onOpenUrl = { openedUrl = it },
                )
            }
        }

        composeRule.onNodeWithText("https://example.com/repo").performClick()

        assertEquals("https://example.com/repo", openedUrl)
    }

    @Test
    fun appDetailCommentsAreListed() {
        var openedProfile: String? = null
        val app = app("dev.zap", "Zap")
        val comment = CommentRecord(
            eventId = "c".repeat(64),
            pubkey = AUTHOR,
            createdAt = 1_750_000_000,
            content = "Nice release",
            appId = "dev.zap",
            stack = null,
            parentEventId = null,
        )

        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app,
                        loaded = true,
                        comments = CommentsState(threads = threadComments(listOf(comment)), loading = false),
                    ),
                    onOpenUrl = {},
                    onProfileClick = { openedProfile = it },
                )
            }
        }

        composeRule.onNodeWithTag("comment:${comment.eventId}").assertIsDisplayed()
        composeRule.onNodeWithText("Nice release").assertIsDisplayed()
        composeRule.onNodeWithTag("profile:$AUTHOR").performClick()
        assertEquals(AUTHOR, openedProfile)
    }

    @Test
    fun appDetailCommentsAreThreaded() {
        val app = app("dev.zap", "Zap")
        val root = CommentRecord(
            eventId = "a".repeat(64),
            pubkey = AUTHOR,
            createdAt = 1_750_000_000,
            content = "Root comment",
            appId = "dev.zap",
            stack = null,
            parentEventId = null,
        )
        val reply = CommentRecord(
            eventId = "b".repeat(64),
            pubkey = AUTHOR,
            createdAt = 1_750_000_100,
            content = "Nested reply",
            appId = "dev.zap",
            stack = null,
            parentEventId = root.eventId,
        )

        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app,
                        loaded = true,
                        comments = CommentsState(threads = threadComments(listOf(reply, root)), loading = false),
                    ),
                    onOpenUrl = {},
                )
            }
        }

        composeRule.onNodeWithTag("commentDepth:${root.eventId}:0").assertIsDisplayed()
        composeRule.onNodeWithTag("commentDepth:${reply.eventId}:1").assertIsDisplayed()
        composeRule.onNodeWithText("Root comment").assertIsDisplayed()
        composeRule.onNodeWithText("Nested reply").assertIsDisplayed()
    }

    @Test
    fun appDetailCommentErrorOffersRetryAndSettings() {
        var retried = false
        var settingsOpened = false
        val app = app("dev.zap", "Zap")

        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app,
                        loaded = true,
                        comments = CommentsState(loading = false, error = CommentsError.RelaysUnreachable),
                    ),
                    onOpenUrl = {},
                    onRetryComments = { retried = true },
                    onSettingsClick = { settingsOpened = true },
                )
            }
        }

        composeRule.onNodeWithTag("commentsEmpty").assertIsDisplayed()
        composeRule.onNodeWithTag("commentsRetry").performClick()
        composeRule.onNodeWithTag("commentsNetworkSettings").performClick()
        assertTrue(retried)
        assertTrue(settingsOpened)
    }

    @Test
    fun appCardAuthorCallbackIsWired() {
        var openedProfile: String? = null
        val app = app("dev.zap", "Zap", proofPubkey = AUTHOR)

        composeRule.setContent {
            ZapstoreTheme {
                AppCard(app = app, author = null, onClick = {}, onAuthorClick = { openedProfile = it })
            }
        }

        composeRule.onNodeWithTag("profile:$AUTHOR").performClick()

        assertEquals(AUTHOR, openedProfile)
    }

    @Test
    fun updatesListsAvailableUpdatesAndSyncs() {
        var synced = false
        val app = app("dev.zap", "Zap", version = "2.0")

        composeRule.setContent {
            ZapstoreTheme {
                UpdatesScreen(
                    state = UpdatesUiState(
                        status = SyncStatus(),
                        updates = listOf(AvailableUpdate(app, installedVersion = "1.0", installedVersionCode = 1)),
                        loaded = true,
                    ),
                    onSync = { synced = true },
                    onAppClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("update:dev.zap").assertIsDisplayed()
        composeRule.onNodeWithText("1.0 → 2.0").assertIsDisplayed()
        composeRule.onNodeWithTag("syncCatalog").performClick()
        assertTrue(synced)
    }

    @Test
    fun settingsShowsLastSyncDuration() {
        composeRule.setContent {
            ZapstoreTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        status = SyncStatus(
                            lastSyncedAtMillis = System.currentTimeMillis() - 3 * 60_000,
                            lastSyncDurationMillis = 2430,
                            lastSyncBytes = 1_234_567,
                        ),
                    ),
                    onNetworkModeChange = {},
                    onSync = {},
                    onWipe = {},
                )
            }
        }
        composeRule.onNodeWithTag("lastSyncAgo").assertIsDisplayed()
        composeRule.onNodeWithText("Last sync 3 minutes ago").assertIsDisplayed()
        composeRule.onNodeWithTag("lastSyncSize").assertIsDisplayed()
        composeRule.onNodeWithText("transferred", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("lastSyncDuration").assertIsDisplayed()
        composeRule.onNodeWithText("Last sync took", substring = true).assertIsDisplayed()
    }

    @Test
    fun settingsShowsLastSyncNotModified() {
        composeRule.setContent {
            ZapstoreTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        status = SyncStatus(
                            lastSyncedAtMillis = System.currentTimeMillis(),
                            lastSyncDurationMillis = 120,
                            lastSyncBytes = 0,
                            lastSyncNotModified = true,
                        ),
                    ),
                    onNetworkModeChange = {},
                    onSync = {},
                    onWipe = {},
                )
            }
        }
        composeRule.onNodeWithTag("lastSyncAgo").assertIsDisplayed()
        composeRule.onNodeWithText("Last sync just now").assertIsDisplayed()
        composeRule.onNodeWithTag("lastSyncNotModified").assertIsDisplayed()
        composeRule.onNodeWithText("Already current (HTTP 304)").assertIsDisplayed()
    }

    @Test
    fun settingsWipeButtonIsWired() {
        var wiped = false
        composeRule.setContent {
            ZapstoreTheme {
                SettingsScreen(state = SettingsUiState(), onNetworkModeChange = {}, onSync = {}, onWipe = { wiped = true })
            }
        }
        composeRule.onNodeWithTag("wipeLocalDb").performClick()
        assertTrue(wiped)
    }

    @Test
    fun settingsShowsAllNetworkModes() {
        composeRule.setContent {
            ZapstoreTheme {
                SettingsScreen(state = SettingsUiState(), onNetworkModeChange = {}, onSync = {}, onWipe = {})
            }
        }
        composeRule.onNodeWithTag("networkTor").assertIsDisplayed()
        composeRule.onNodeWithTag("networkDirect").assertIsDisplayed()
        composeRule.onNodeWithTag("networkTorFallback").assertIsDisplayed()
        composeRule.onNodeWithText("Tor only").assertIsDisplayed()
        composeRule.onNodeWithText("Direct only").assertIsDisplayed()
        composeRule.onNodeWithText("Tor with Direct fallback").assertIsDisplayed()
    }
}

private val AUTHOR = "1".repeat(64)

private fun app(
    appId: String,
    name: String,
    version: String = "1.0",
    repository: String? = null,
    proofPubkey: String? = null,
): AppRecord = AppRecord(
    id = appId.toByteArray(),
    catalogId = 1,
    appId = appId,
    certificateHash = "a".repeat(64),
    createdAt = 1_750_000_000,
    proofPubkey = proofPubkey,
    eventPubkey = AUTHOR,
    catalogManifestPubkey = null,
    name = name,
    summary = "$name summary",
    repository = repository,
    version = version,
    versionCode = 1,
    channel = null,
    metadata = JSONObject(),
)
