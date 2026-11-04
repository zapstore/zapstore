package dev.zapstore.app

import dev.zapstore.app.screens.ActivityFilter
import dev.zapstore.app.screens.ProfileUiState
import dev.zapstore.app.screens.releaseActivity
import dev.zapstore.app.screens.ProfileScreen
import androidx.compose.ui.test.assertTextContains
import dev.zapstore.app.screens.UpdateGroup
import dev.zapstore.app.screens.SavedScreen
import dev.zapstore.app.screens.SavedApps
import dev.zapstore.app.screens.ActivityItem
import androidx.compose.ui.test.performImeAction
import dev.zapstore.app.catalog.InstalledApp
import dev.zapstore.app.catalog.ManualReason
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextEquals
import dev.zapstore.app.screens.InstallPhase
import androidx.compose.ui.test.performTextClearance
import dev.zapstore.app.screens.SignedOutProfile
import dev.zapstore.app.screens.StackDetailUiState
import dev.zapstore.app.screens.StackDetailScreen
import dev.zapstore.app.screens.AddToStackSheet
import dev.zapstore.app.install.InstallPlan
import dev.zapstore.app.screens.InstallSheet
import androidx.compose.ui.test.onAllNodesWithTag
import dev.zapstore.app.screens.DeviceKeyUi
import dev.zapstore.app.screens.IgnoredApp
import dev.zapstore.app.screens.SocialUi
import dev.zapstore.app.screens.WalletUi
import dev.zapstore.app.screens.ZapSummary
import dev.zapstore.app.screens.ZapRecipient
import dev.zapstore.app.screens.InstallUi
import dev.zapstore.app.ZapDanger
import dev.zapstore.app.ZapVerified
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.zapstore.app.screens.Zapper
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import dev.zapstore.app.catalog.AvailableUpdate
import dev.zapstore.app.catalog.SyncStatus
import dev.zapstore.app.components.AppListState
import dev.zapstore.app.screens.AppDetailScreen
import dev.zapstore.app.screens.AppDetailUiState
import dev.zapstore.app.screens.CommentsError
import dev.zapstore.app.screens.CommentsState
import dev.zapstore.app.screens.threadComments
import dev.zapstore.app.screens.HomeScreen
import dev.zapstore.app.screens.HomeUiState
import dev.zapstore.app.screens.SettingsSection
import dev.zapstore.app.screens.StacksScreen
import dev.zapstore.app.screens.StacksUiState
import dev.zapstore.app.screens.CatalogSheetUi
import dev.zapstore.app.screens.CatalogUi
import dev.zapstore.app.screens.SettingsUiState
import dev.zapstore.app.screens.UpdatesScreen
import dev.zapstore.app.screens.UpdatesUiState
import dev.zapstore.iolite.AppCoordinate
import dev.zapstore.iolite.SearchFact
import dev.zapstore.iolite.AppRecord
import dev.zapstore.iolite.CommentRecord
import dev.zapstore.iolite.ProfileRecord
import dev.zapstore.iolite.StackRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import dev.zapstore.app.screens.AppActionSheet
import dev.zapstore.app.screens.UserStacksUi
import androidx.compose.ui.test.assertIsSelected
import dev.zapstore.app.screens.SignerStepUi
import dev.zapstore.app.screens.OnboardingUiState
import dev.zapstore.app.screens.OnboardingScreen
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeLeft
import dev.zapstore.app.catalog.StoreApp
import dev.zapstore.app.screens.OtherStoreApp
import androidx.compose.ui.Modifier
import dev.zapstore.iolite.ZapRecord
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import dev.zapstore.app.components.AppRow
import dev.zapstore.app.components.AppRowStyle
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.getBoundsInRoot
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.onRoot
import dev.zapstore.app.components.LocalPendingUpdates
import androidx.compose.runtime.CompositionLocalProvider

class ScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeCallbacksAreWired() {
        var cleared = false
        var openedStack: String? = null
        var openedApp: String? = null
        var removedFilter: SearchFact? = null
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
        var results by mutableStateOf<List<AppRecord>?>(listOf(app))

        composeRule.setContent {
            ZapstoreTheme {
                HomeScreen(
                    state = HomeUiState(
                        searchQuery = "zap",
                        submittedQuery = "zap",
                        searchResults = results,
                        searchFacts = listOf(SearchFact.SourceAvailable, SearchFact.WorksOffline),
                        stacks = listOf(stack),
                        stacksLoading = false,
                    ),
                    onSearchQueryChanged = {},
                    onSearchSubmitted = {},
                    onSearchCleared = { cleared = true },
                    onAppClick = { openedApp = it.appId },
                    onStackClick = { openedStack = it.identifier },
                    onRemoveFilter = { removedFilter = it },
                )
            }
        }

        composeRule.onNodeWithTag("searchFacts").assertIsDisplayed()
        composeRule.onNodeWithTag("filter:SourceAvailable").assertIsDisplayed()
        composeRule.onNodeWithTag("filter:WorksOffline").performClick()
        assertEquals(SearchFact.WorksOffline, removedFilter)
        composeRule.onNodeWithTag("clearSearch").performClick()
        composeRule.onNodeWithTag("searchResult:dev.zap").performClick()
        results = null
        composeRule.onNodeWithTag("stack:privacy").performClick()

        assertTrue(cleared)
        assertEquals("privacy", openedStack)
        assertEquals("dev.zap", openedApp)
    }

    @Test
    fun searchListsAppsStacksAndDevelopersInOneList() {
        var openedApp: String? = null
        var openedStack: String? = null
        var openedDeveloper: String? = null
        val app = app("dev.zap", "Zap")
        val stack = StackRecord(
            pubkey = AUTHOR,
            identifier = "zap-tools",
            eventId = "e".repeat(64),
            name = "Zap tools",
            description = "",
            apps = listOf(AppCoordinate(AUTHOR, "dev.zap")),
            updatedAt = 1_750_000_000,
        )
        composeRule.setContent {
            ZapstoreTheme {
                HomeScreen(
                    state = HomeUiState(
                        searchQuery = "zap",
                        submittedQuery = "zap",
                        searchResults = listOf(app),
                        searchStacks = listOf(stack),
                        searchStackApps = mapOf("dev.zap" to app),
                        searchDevelopers = listOf(AUTHOR),
                    ),
                    onSearchQueryChanged = {},
                    onSearchSubmitted = {},
                    onSearchCleared = {},
                    onAppClick = { openedApp = it.appId },
                    onStackClick = { openedStack = it.identifier },
                    onDeveloperClick = { openedDeveloper = it },
                )
            }
        }
        composeRule.onNodeWithTag("searchResult:dev.zap").performClick()
        composeRule.onNodeWithTag("searchStack:zap-tools").performClick()
        composeRule.onNodeWithTag("searchDeveloper:$AUTHOR").performClick()
        composeRule.onNodeWithText("No apps match", substring = true).assertDoesNotExist()
        assertEquals("dev.zap", openedApp)
        assertEquals("zap-tools", openedStack)
        assertEquals(AUTHOR, openedDeveloper)
    }

    @Test
    fun searchWithOnlyADeveloperMatchShowsNoEmptyMessage() {
        composeRule.setContent {
            ZapstoreTheme {
                HomeScreen(
                    state = HomeUiState(
                        searchQuery = "tools",
                        submittedQuery = "tools",
                        searchResults = emptyList(),
                        searchDevelopers = listOf(AUTHOR),
                    ),
                    onSearchQueryChanged = {},
                    onSearchSubmitted = {},
                    onSearchCleared = {},
                    onAppClick = {},
                    onStackClick = {},
                )
            }
        }
        composeRule.onNodeWithTag("searchDeveloper:$AUTHOR").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing matches. Try fewer words.").assertDoesNotExist()
    }

    @Test
    fun emptyFilteredSearchOffersToRemoveEachFilter() {
        var removed: SearchFact? = null
        composeRule.setContent {
            ZapstoreTheme {
                HomeScreen(
                    state = HomeUiState(
                        searchQuery = "offline open source chat",
                        searchResults = emptyList(),
                        searchFacts = listOf(SearchFact.WorksOffline, SearchFact.SourceAvailable),
                    ),
                    onSearchQueryChanged = {},
                    onSearchSubmitted = {},
                    onSearchCleared = {},
                    onAppClick = {},
                    onStackClick = {},
                    onRemoveFilter = { removed = it },
                )
            }
        }
        composeRule.onNodeWithTag("filter:WorksOffline").assertIsDisplayed()
        composeRule.onNodeWithText("No apps match with these filters. Remove one to see more.").assertIsDisplayed()
        composeRule.onNodeWithTag("relax:SourceAvailable").performClick()
        assertEquals(SearchFact.SourceAvailable, removed)
    }

    @Test
    fun stacksPageNamesCuratorsAndCountsCatalogApps() {
        var opened: String? = null
        val stack = StackRecord(
            pubkey = AUTHOR,
            identifier = "games",
            eventId = "f".repeat(64),
            name = "Games",
            description = "",
            apps = listOf(AppCoordinate(AUTHOR, "dev.zap"), AppCoordinate(AUTHOR, "not.in.catalog")),
            updatedAt = 1_750_000_000,
        )
        composeRule.setContent {
            ZapstoreTheme {
                StacksScreen(
                    state = StacksUiState(
                        stacks = listOf(stack),
                        apps = mapOf("dev.zap" to app("dev.zap", "Zap")),
                        profiles = mapOf(AUTHOR to ProfileRecord(AUTHOR, 0, "a".repeat(64), "alice", "Alice", null, null, null, null, null)),
                        loading = false,
                    ),
                    onStackClick = { opened = it.identifier },
                )
            }
        }

        composeRule.onNodeWithText("1 curated app · by Alice").assertIsDisplayed()
        composeRule.onNodeWithTag("stack:games").performClick()
        assertEquals("games", opened)
    }

    @Test
    fun homeShowsResultsAboveStacksAndAnActivityFeed() {
        var opened: String? = null
        var submitted = false
        var moreActivity = 0
        val zap = app("dev.zap", "Zap")
        composeRule.setContent {
            ZapstoreTheme {
                HomeScreen(
                    state = HomeUiState(
                        searchQuery = "zap",
                        submittedQuery = "zap",
                        searchResults = listOf(zap),
                        stacksLoading = false,
                        activity = listOf(
                            ActivityItem("c".repeat(64), AUTHOR, 1_750_000_000, zap, comment = "Works great"),
                            ActivityItem("d".repeat(64), AUTHOR, 1_749_000_000, zap, sats = 2100),
                        ),
                        activityLoading = false,
                        activityCanLoadMore = true,
                    ),
                    onSearchQueryChanged = {},
                    onLoadMoreActivity = { moreActivity += 1 },
                    onSearchSubmitted = { submitted = true },
                    onSearchCleared = {},
                    onAppClick = { opened = it.appId },
                    onStackClick = {},
                )
            }
        }
        composeRule.onNodeWithTag("searchResult:dev.zap").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Search results", substring = true).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("homeList").performScrollToNode(hasTestTag("activity:${"d".repeat(64)}"))
        composeRule.onNodeWithText("Works great").assertIsDisplayed()
        composeRule.onNodeWithText("commented on").assertIsDisplayed()
        composeRule.onNodeWithText("2,100 sats").assertIsDisplayed()
        composeRule.onNodeWithTag("activity:${"d".repeat(64)}").performClick()
        assertEquals("dev.zap", opened)
        composeRule.onNodeWithTag("homeList").performScrollToNode(hasTestTag("activityLoadingMore"))
        composeRule.waitUntil { moreActivity > 0 }
        composeRule.onNodeWithTag("searchField").performImeAction()
        assertTrue(submitted)
    }

    @Test
    fun savedAppsArePinnedOnTheSignedOutProfileAndCanBeUnsaved() {
        var openedSaved = false
        var unsaved: String? = null
        val saved = SavedApps(listOf("dev.zap"), mapOf("dev.zap" to app("dev.zap", "Zap")))
        var showList by mutableStateOf(false)
        composeRule.setContent {
            ZapstoreTheme {
                if (showList) {
                    SavedScreen(saved, onAppClick = {}, onUnsave = { unsaved = it.appId })
                } else {
                    SignedOutProfile(
                        hasSigner = true,
                        signingIn = false,
                        error = null,
                        onSignIn = {},
                        onGetAmber = {},
                        saved = saved,
                        onSavedClick = { openedSaved = true },
                    )
                }
            }
        }
        composeRule.onNodeWithTag("savedStack").assertIsDisplayed().performClick()
        assertTrue(openedSaved)
        showList = true
        composeRule.onNodeWithTag("unsave:dev.zap").performClick()
        assertEquals("dev.zap", unsaved)
    }

    @Test
    fun profileActivityLeadsWithTheAppAndLeavesTheirNameOut() {
        var opened: String? = null
        val zap = app("dev.zap", "Zap")
        composeRule.setContent {
            ZapstoreTheme {
                ProfileScreen(
                    state = ProfileUiState(
                        pubkey = AUTHOR,
                        activity = listOf(ActivityItem("c".repeat(64), AUTHOR, 1_750_000_000, zap, comment = "Works great")),
                        activityLoading = false,
                    ),
                    onOpenUrl = {},
                    onAppClick = { opened = it.appId },
                )
            }
        }
        composeRule.onNodeWithTag("activity:${"c".repeat(64)}").assertIsDisplayed()
        composeRule.onNodeWithText("Works great").assertIsDisplayed()
        assertTrue(composeRule.onAllNodes(hasTestTag("profile:$AUTHOR") and hasAnyAncestor(hasTestTag("activity:${"c".repeat(64)}"))).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("activity:${"c".repeat(64)}").performClick()
        assertEquals("dev.zap", opened)
    }

    @Test
    fun stackCommentsAreCardsWithRepliesAndAnAddCommentButton() {
        var openedProfile: String? = null
        var posted: Pair<String, CommentRecord?>? = null
        var signedIn by mutableStateOf(true)
        val stack = StackRecord(AUTHOR, "music", "e1", "Music", "", listOf(AppCoordinate(AUTHOR, "dev.zap")), 1)
        val root = CommentRecord("c".repeat(64), AUTHOR, 1_750_000_000, "Nice stack", null, "30267:$AUTHOR:music", null)
        val reply = CommentRecord("d".repeat(64), AUTHOR, 1_750_000_100, "Thanks", null, "30267:$AUTHOR:music", root.eventId)
        composeRule.setContent {
            ZapstoreTheme {
                StackDetailScreen(
                    state = StackDetailUiState(
                        stack = stack,
                        stackLoading = false,
                        threads = threadComments(listOf(root, reply)),
                        commentsLoading = false,
                        social = SocialUi(signedIn = signedIn),
                    ),
                    onAppClick = {},
                    onProfileClick = { openedProfile = it },
                    onPostComment = { text, parent -> posted = text to parent },
                )
            }
        }
        composeRule.onNodeWithTag("commentDepth:${root.eventId}:0").assertIsDisplayed()
        composeRule.onNodeWithTag("commentDepth:${reply.eventId}:1").assertIsDisplayed()
        composeRule.onNodeWithText("Nice stack").assertIsDisplayed()
        composeRule.onNode(hasTestTag("profile:$AUTHOR") and hasAnyAncestor(hasTestTag("comment:${root.eventId}"))).performClick()
        assertEquals(AUTHOR, openedProfile)

        composeRule.onNodeWithTag("reply:${root.eventId}").performClick()
        composeRule.onNodeWithTag("replyPreview").assertIsDisplayed()
        composeRule.onNodeWithTag("commentField").performTextInput("Agreed")
        composeRule.onNodeWithTag("postComment").performClick()
        assertEquals("Agreed" to root, posted)
    }

    @Test
    fun repliesAreOfferedOnlyWhileSignedIn() {
        val app = app("dev.zap", "Zap")
        val comment = CommentRecord("c".repeat(64), AUTHOR, 1_750_000_000, "Nice release", "dev.zap", null, null)
        var signedIn by mutableStateOf(false)
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app,
                        loaded = true,
                        comments = CommentsState(threads = threadComments(listOf(comment)), loading = false),
                        social = SocialUi(signedIn = signedIn),
                    ),
                    onOpenUrl = {},
                )
            }
        }
        composeRule.onNodeWithTag("appDetail").performScrollToNode(hasTestTag("comment:${comment.eventId}"))
        composeRule.onNodeWithTag("reply:${comment.eventId}").assertDoesNotExist()
        signedIn = true
        composeRule.onNodeWithTag("reply:${comment.eventId}").assertIsDisplayed()
    }

    @Test
    fun zapsAreListedOnlyWhenTheyCarryANote() {
        val app = app("dev.zap", "Zap")
        val plain = ZapRecord("a".repeat(64), AUTHOR, 21, 1_750_000_000, "dev.zap", "")
        val noted = ZapRecord("b".repeat(64), AUTHOR, 2_100, 1_750_000_100, "dev.zap", "Love it")
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app,
                        loaded = true,
                        comments = CommentsState(zaps = listOf(plain, noted), loading = false),
                    ),
                    onOpenUrl = {},
                )
            }
        }
        composeRule.onNodeWithTag("appDetail").performScrollToNode(hasTestTag("zapEntry:${noted.eventId}"))
        composeRule.onNodeWithTag("zapEntry:${noted.eventId}").assertIsDisplayed()
        composeRule.onNodeWithText("Love it").assertIsDisplayed()
        composeRule.onNodeWithTag("zapEntry:${plain.eventId}").assertDoesNotExist()
    }

    @Test
    fun appDetailShowsOtherCommentsOnlyWhenThereAreSome() {
        val app = app("dev.zap", "Zap")
        val elsewhere = CommentRecord("b".repeat(64), AUTHOR, 1_750_000_000, "From another catalog", "dev.zap", "32267:${"b".repeat(64)}:dev.zap", null)
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app,
                        loaded = true,
                        comments = CommentsState(other = threadComments(listOf(elsewhere)), loading = false),
                    ),
                    onOpenUrl = {},
                )
            }
        }
        composeRule.onNodeWithTag("appDetail").performScrollToNode(hasTestTag("otherComments"))
        composeRule.onNodeWithTag("otherComments").assertIsDisplayed()
        composeRule.onNodeWithTag("appDetail").performScrollToNode(hasText("From another catalog"))
        composeRule.onNodeWithText("From another catalog").assertIsDisplayed()
    }

    @Test
    fun profileAppRowsLeaveOutTheirAuthor() {
        composeRule.setContent {
            ZapstoreTheme {
                ProfileScreen(
                    state = ProfileUiState(
                        pubkey = AUTHOR,
                        apps = AppListState(apps = listOf(app("dev.zap", "Zap", proofPubkey = AUTHOR)), loading = false),
                    ),
                    onOpenUrl = {},
                    onAppClick = {},
                )
            }
        }
        composeRule.onNodeWithTag("app:dev.zap").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodes(hasTestTag("profile:$AUTHOR") and hasAnyAncestor(hasTestTag("app:dev.zap")), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun homeActivityListsReleasesAndFiltersThem() {
        var opened: String? = null
        var filter by mutableStateOf(ActivityFilter.All)
        val zap = app("dev.zap", "Zap", version = "1.2.3")
        val indexed = app("dev.indexed", "Indexed", version = "2.0", catalogSigned = true)
        composeRule.setContent {
            ZapstoreTheme {
                HomeScreen(
                    state = HomeUiState(
                        stacksLoading = false,
                        activityLoading = false,
                        activityFilter = filter,
                        activity = listOf(zap.releaseActivity(), indexed.releaseActivity()),
                    ),
                    onSearchQueryChanged = {},
                    onSearchSubmitted = {},
                    onSearchCleared = {},
                    onAppClick = { opened = it.appId },
                    onStackClick = {},
                    onActivityFilter = { filter = it },
                )
            }
        }
        assertTrue(composeRule.onAllNodesWithText("Latest releases").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("activityFilter").assertIsDisplayed()
        composeRule.onNodeWithText("All").assertIsDisplayed()
        composeRule.onNodeWithTag("activityFilter").performClick()
        composeRule.onNodeWithTag("activityFilter:Social").performClick()
        assertEquals(ActivityFilter.Social, filter)
        composeRule.onNodeWithTag("activityFilter").performClick()
        composeRule.onNodeWithTag("activityFilter:Releases").performClick()
        assertEquals(ActivityFilter.Releases, filter)
        composeRule.onNodeWithText("New release").assertDoesNotExist()
        composeRule.onNodeWithText("released").assertIsDisplayed()
        composeRule.onNodeWithText("Someone").assertIsDisplayed()
        composeRule.onNodeWithText("1.2.3").assertIsDisplayed()
        composeRule.onNodeWithText("Catalog").assertIsDisplayed()
        composeRule.onNodeWithText("published").assertIsDisplayed()
        composeRule.onNodeWithText("2.0").assertIsDisplayed()
        composeRule.onNodeWithTag("activity:release:dev.zap:1.2.3").performClick()
        assertEquals("dev.zap", opened)
    }

    @Test
    fun appRowsKeepLongVersionsAndNamesInsideTheRow() {
        val other = "b".repeat(64)
        composeRule.setContent {
            ZapstoreTheme {
                CompositionLocalProvider(LocalPendingUpdates provides mapOf("dev.pair" to "2026.10.01-nightly+build.4411")) {
                    Column(Modifier.background(ZapCanvas).padding(horizontal = 16.dp)) {
                        AppRow(
                            app = app("dev.long", "An application with a remarkably long display name", version = "12.0.0-beta.17+sha.9f3c2d1e", proofPubkey = AUTHOR),
                            onClick = {},
                            style = AppRowStyle.Manage,
                            author = ProfileRecord(AUTHOR, 0, "a".repeat(64), "alice", "Alexandria Montgomery-Featherstonehaugh", null, null, null, null, null),
                        )
                        AppRow(app = app("dev.pair", "Pair", version = "2026.10.17-nightly+build.4520", proofPubkey = other), onClick = {}, style = AppRowStyle.Manage)
                        AppRow(app = app("dev.plain", "Plain"), onClick = {}, style = AppRowStyle.Manage)
                    }
                }
            }
        }
        composeRule.onNodeWithTag("updatePill:dev.pair", useUnmergedTree = true).assertIsDisplayed()
        val root = composeRule.onRoot().getBoundsInRoot()
        composeRule.onAllNodesWithTag("profile:$other", useUnmergedTree = true).onFirst().assertIsDisplayed()
            .getBoundsInRoot().let { assertTrue(it.right <= root.right) }
        composeRule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val file = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "rows.png")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun appDetailFactsFollowTheSheet() {
        val security = """
            "google_services","no",""
            "open_source","no",""
            "offline_capable","yes","No INTERNET"
            ---
            CAMERA,ACCESS_NETWORK_STATE
            ---
        """.trimIndent()

        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(app = app("dev.zap", "Zap", security = security), loaded = true),
                    onOpenUrl = {},
                )
            }
        }

        composeRule.onNodeWithTag("appDetail").performScrollToKey("security")
        composeRule.onNodeWithText("No Google services").assertIsDisplayed()
        composeRule.onNodeWithText("Works offline").assertIsDisplayed()
        composeRule.onNodeWithText("No source").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("E2EE").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Account required").fetchSemanticsNodes().isEmpty())
        assertPillColor("fact_google_services", ZapVerified)
        assertPillColor("fact_offline_capable", ZapVerified)
        assertPillColor("fact_source_available", ZapDanger)

        // Permissions are not on the page; the full report lists them.
        assertTrue(composeRule.onAllNodesWithText("Camera").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("seeFullReport").performClick()
        composeRule.onNodeWithText("Take photos and record video").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Take photos and record video").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("CAMERA").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("otherPermissions").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ACCESS_NETWORK_STATE").assertIsDisplayed()
    }

    @Test
    fun appDetailFullReportHasCopyableIdentifiers() {
        val security = "\"offline_capable\",\"yes\",\"\"\n---\n\n---\nThe prose."
        val apk = "b".repeat(64)
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app(
                            "dev.zap", "Zap", security = security, repository = "https://example.com/zap",
                            extra = { put("apk_hash", apk); put("license", "MIT"); put("download_url", "https://cdn.example.com/zap.apk") },
                        ),
                        loaded = true,
                    ),
                    onOpenUrl = {},
                )
            }
        }
        composeRule.onNodeWithTag("appDetail").performScrollToKey("security")
        // Neither the hash nor the package id is on the page.
        assertTrue(composeRule.onAllNodesWithText(apk).fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("seeFullReport").performClick()
        composeRule.onNodeWithText("a".repeat(64)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(apk).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("dev.zap").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy APK SHA-256").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Copy Package ID").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy Signing certificate").assertIsDisplayed()
        composeRule.onNodeWithText("https://example.com/zap").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun appDetailReleaseRowOpensTheReleaseSheet() {
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app("dev.zap", "Zap", version = "2.3.1", extra = { put("release_notes", "Fixes the crash."); put("size", 1_258_291L) }),
                        loaded = true,
                    ),
                    onOpenUrl = {},
                )
            }
        }
        composeRule.onNodeWithTag("appDetail").performScrollToKey("release")
        composeRule.onNodeWithTag("releaseCard").assertIsDisplayed()
        composeRule.onNodeWithTag("releaseLink").performClick()
        composeRule.onNodeWithTag("releaseSheet").assertIsDisplayed()
        composeRule.onNodeWithText("Fixes the crash.").assertIsDisplayed()
        composeRule.onNodeWithText("Version code").assertIsDisplayed()
    }

    @Test
    fun appDetailZappersStripShowsBiggestFirstWithoutAZapButton() {
        val other = "2".repeat(64)
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app("dev.zap", "Zap"),
                        loaded = true,
                        comments = CommentsState(loading = false),
                        zaps = ZapSummary(
                            loading = false,
                            count = 3,
                            totalSats = 3_400,
                            zappers = listOf(Zapper(AUTHOR, 2_100), Zapper(other, 1_300)),
                        ),
                    ),
                    onOpenUrl = {},
                )
            }
        }
        composeRule.onNodeWithTag("appDetail").performScrollToKey("zaps")
        composeRule.onNodeWithTag("zapTotal").assertIsDisplayed().assertTextContains("3.4k sats")
        composeRule.onNodeWithText("2.1k").assertIsDisplayed()
        composeRule.onNodeWithText("1.3k").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("zapOpen").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun appDetailPrefixesSecurityNotices() {
        val security = "\"offline_capable\",\"yes\",\"\"\n---\n\n---\n" +
            "Contacts leave the phone.\nInstalls other apps.\n---\nThe address book is read on the share screen."
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(app = app("dev.zap", "Zap", security = security), loaded = true),
                    onOpenUrl = {},
                )
            }
        }

        composeRule.onNodeWithTag("appDetail").performScrollToKey("security")
        composeRule.onNodeWithText("Contacts leave the phone.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Installs other apps.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("The address book is read on the share screen.").performScrollTo().assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("---").fetchSemanticsNodes().isEmpty())
    }

    private fun assertPillColor(tag: String, expected: Color) {
        val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
        val hit = (0 until pixels.width).any { x ->
            (0 until pixels.height).any { y -> pixels[x, y].matches(expected) }
        }
        assertTrue(hit)
    }

    @Test
    fun installBarOffersUpdateOpenAndUninstall() {
        var opened = false
        var uninstalls = 0
        var installs = 0
        var install by mutableStateOf(InstallUi(offer = true, updating = true))
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(app = app("dev.zap", "Zap"), loaded = true, install = install),
                    onOpenUrl = {},
                    onInstall = { installs += 1 },
                    onOpenApp = { opened = true },
                    onUninstall = { uninstalls += 1 },
                )
            }
        }
        composeRule.onNodeWithTag("installApp").assertIsDisplayed().assertTextEquals("Update").performClick()
        composeRule.onNodeWithTag("openApp").performClick()
        composeRule.onNodeWithTag("uninstallIcon").performClick()
        composeRule.waitForIdle()
        assertEquals(1, installs)
        assertEquals(1, uninstalls)
        assertTrue(opened)

        install = InstallUi(installed = true)
        composeRule.onNodeWithTag("openApp").assertIsDisplayed().assertTextEquals("Open")
        composeRule.onNodeWithTag("uninstallIcon").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("installApp").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun installBarStaysPinnedWhileScrolling() {
        var installs = 0
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app("dev.zap", "Zap", description = List(40) { "A long description line." }.joinToString("\n\n")),
                        loaded = true,
                        install = InstallUi(offer = true),
                    ),
                    onOpenUrl = {},
                    onInstall = { installs += 1 },
                )
            }
        }
        composeRule.onNodeWithTag("installApp").assertIsDisplayed()
        repeat(3) { composeRule.onNodeWithTag("appDetail").performTouchInput { swipeUp() } }
        composeRule.onNodeWithTag("installApp").assertIsDisplayed().performClick()
        assertEquals(1, installs)
        assertTrue(composeRule.onAllNodesWithTag("uninstallIcon").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun installBarWalksThroughFlutterStates() {
        val calls = mutableListOf<String>()
        var install by mutableStateOf(InstallUi(offer = true, phase = InstallPhase.Downloading, received = 512L * 1024, total = 2L * 1024 * 1024))
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(app = app("dev.zap", "Zap"), loaded = true, install = install),
                    onOpenUrl = {},
                    onPauseInstall = { calls += "pause" },
                    onStartInstall = { calls += "start" },
                    onDismissInstallError = { calls += "dismiss" },
                    onUninstall = { calls += "uninstall" },
                )
            }
        }
        composeRule.onNodeWithText("25% of 2.0 MB").assertIsDisplayed()
        composeRule.onNodeWithTag("pauseDownload").performClick()
        install = install.copy(phase = InstallPhase.Paused)
        composeRule.onNodeWithText("Paused · 25% of 2.0 MB").assertIsDisplayed()
        composeRule.onNodeWithTag("resumeDownload").performClick()
        install = install.copy(phase = InstallPhase.Verifying)
        composeRule.onNodeWithText("Verifying…").assertIsDisplayed()
        install = install.copy(phase = InstallPhase.Installing, silent = false)
        composeRule.onNodeWithText("Requesting installation").assertIsDisplayed()
        install = install.copy(phase = InstallPhase.Confirm)
        composeRule.onNodeWithTag("confirmInstall").performClick()
        install = install.copy(phase = InstallPhase.Failed, error = "Not enough free space")
        composeRule.onNodeWithText("Error (tap for details)").performClick()
        install = InstallUi(offer = true, phase = InstallPhase.Installing, silent = true)
        composeRule.onNodeWithText("Installing…").assertIsDisplayed().assertHasNoClickAction()
        composeRule.waitForIdle()
        assertEquals(listOf("pause", "start", "start", "dismiss"), calls)
    }

    @Test
    fun onboardingCountsStepsAndPicksNetwork() {
        var state by mutableStateOf(
            OnboardingUiState(
                steps = listOf(OnboardingStep.Privacy, OnboardingStep.Network, OnboardingStep.Signer),
                index = 1,
            ),
        )
        var next = 0
        var connected = false
        var skippedAll = false
        composeRule.setContent {
            ZapstoreTheme {
                OnboardingScreen(
                    state = state,
                    onNext = { next += 1 },
                    onSkipAll = { skippedAll = true },
                    onNetworkModeChange = { state = state.copy(networkMode = it) },
                    onConnectSigner = { connected = true },
                    onOpenInstallSettings = {},
                    onRequestNotifications = {},
                )
            }
        }
        composeRule.onNodeWithText("Step 2 of 3").assertIsDisplayed()
        composeRule.onNodeWithTag("onboardingTor").assertIsSelected()
        composeRule.onNodeWithTag("onboardingDirect").performClick()
        composeRule.onNodeWithTag("onboardingDirect").assertIsSelected()
        composeRule.onNodeWithTag("onboardingPrimary").performClick()
        assertEquals(1, next)
        // Skip all bypasses every remaining step; it does not just advance the current one.
        composeRule.onNodeWithTag("onboardingSkip").performClick()
        assertTrue(skippedAll)
        assertEquals(1, next)

        state = state.copy(index = 2)
        composeRule.onNodeWithTag("onboarding:Signer").assertIsDisplayed()
        composeRule.onNodeWithTag("onboardingPrimary").performClick()
        assertTrue(connected)
        state = state.copy(signer = SignerStepUi(message = "No signer answered"))
        composeRule.onNodeWithText("No signer answered").assertIsDisplayed()
        // The Signer step keeps its own way to move on, since Skip all would otherwise skip the rest too.
        composeRule.onNodeWithTag("onboardingCancelSigner").performClick()
        assertEquals(2, next)
    }

    @Test
    fun actionSheetSavesAndIgnores() {
        var saved by mutableStateOf(false)
        var ignored = false
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(app = app("dev.zap", "Zap"), loaded = true, install = InstallUi(installed = true)),
                    onOpenUrl = {},
                    saved = saved,
                    onSave = { saved = it },
                    onIgnore = { ignored = it },
                )
            }
        }
        composeRule.onNodeWithTag("appMore").performClick()
        composeRule.onNodeWithTag("actionSave").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Saved").assertIsDisplayed()
        composeRule.onNodeWithTag("actionIgnore").performClick()
        composeRule.waitForIdle()
        assertTrue(saved && ignored)
        assertTrue(composeRule.onAllNodesWithTag("actionShare").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun stackSharesAndCommentsOfferCopyAndShareOnLongPress() {
        var shared = false
        val stack = StackRecord(AUTHOR, "music", "e1", "Music", "", listOf(AppCoordinate(AUTHOR, "dev.zap")), 1)
        val root = CommentRecord("c".repeat(64), AUTHOR, 1_750_000_000, "Nice stack", null, "30267:$AUTHOR:music", null)
        composeRule.setContent {
            ZapstoreTheme {
                StackDetailScreen(
                    state = StackDetailUiState(stack = stack, stackLoading = false, threads = threadComments(listOf(root)), commentsLoading = false),
                    onAppClick = {},
                    onShare = { shared = true },
                )
            }
        }
        composeRule.onNodeWithTag("share").assertIsDisplayed().performClick()
        assertTrue(shared)
        composeRule.onNodeWithTag("comment:${root.eventId}").performTouchInput { longClick() }
        composeRule.onNodeWithTag("commentCopyText").assertIsDisplayed()
        composeRule.onNodeWithTag("commentCopyLink").assertIsDisplayed()
        composeRule.onNodeWithTag("commentShare").assertIsDisplayed()
    }

    @Test
    fun appHeadSharesThePage() {
        var shared = false
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(app = app("dev.zap", "Zap"), loaded = true),
                    onOpenUrl = {},
                    onShare = { shared = true },
                )
            }
        }
        composeRule.onNodeWithTag("share").assertIsDisplayed().performClick()
        assertTrue(shared)
    }

    @Test
    fun myAppsSplitsNewUpdatesFromInstalledApps() {
        var resumed: String? = null
        var opened: String? = null
        val updatedAll = mutableListOf<UpdateGroup>()
        val updatedOne = mutableListOf<String>()
        val play = app("dev.play", "Played")
        val fdroid = app("dev.fdroid", "Droided")
        composeRule.setContent {
            ZapstoreTheme {
                UpdatesScreen(
                    state = UpdatesUiState(
                        updates = listOf(
                            AvailableUpdate(app("dev.silent", "Silent", version = "2.0"), "1.0", 1),
                            AvailableUpdate(app("dev.silent2", "Silent Two", version = "2.0"), "1.0", 1),
                        ),
                        manualUpdates = listOf(AvailableUpdate(app("dev.manual", "Manual", version = "3.0"), "2.0", 2)),
                        manualReasons = mapOf("dev.manual" to ManualReason.Sideloaded),
                        installedApps = listOf(app("dev.current", "Current")),
                        otherStores = listOf(
                            OtherStoreApp(
                                StoreApp(play, "com.android.vending", "1.0", AvailableUpdate(play, "1.0", 1), signedDifferently = false),
                                storeLabel = "Google Play",
                            ),
                            OtherStoreApp(StoreApp(fdroid, "org.fdroid.fdroid", "1.0", null, signedDifferently = false), storeLabel = "F-Droid"),
                        ),
                        ignored = listOf(IgnoredApp(app("dev.quiet", "Quiet"), installer = "F-Droid")),
                        otherInstalled = listOf(InstalledApp("dev.side", 1, "1.0", emptySet(), label = "Sideloaded")),
                        loaded = true,
                    ),
                    onSync = {},
                    onAppClick = { opened = it.appId },
                    onResumeUpdates = { resumed = it },
                    onUpdateAll = { updatedAll += it },
                    onUpdateOne = { updatedOne += it.app.appId },
                )
            }
        }
        composeRule.onNodeWithTag("updateAll").performClick()
        composeRule.onNodeWithTag("updateOne:dev.silent").performClick()
        composeRule.onNodeWithTag("updateAllManual").performClick()
        composeRule.onNodeWithTag("updateOne:dev.manual").performClick()
        composeRule.onNodeWithTag("why:dev.manual").performClick()
        composeRule.onNodeWithTag("whySheet").assertIsDisplayed()
        composeRule.onNodeWithText("Installed from a file").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm this one", substring = true).assertIsDisplayed()
        pressBack()
        assertEquals(listOf(UpdateGroup.Automatic, UpdateGroup.Manual), updatedAll)
        assertEquals(listOf("dev.silent", "dev.manual"), updatedOne)
        composeRule.onNodeWithTag("myAppsList").performScrollToNode(hasTestTag("updateOne:dev.play"))
        composeRule.onNodeWithTag("why:dev.play").performClick()
        composeRule.onNodeWithText("Google Play updates this app now", substring = true).assertIsDisplayed()
        pressBack()
        composeRule.onNodeWithTag("update:dev.play").assertIsDisplayed()
        composeRule.onNodeWithTag("myAppsList").performScrollToNode(hasTestTag("resume:dev.quiet"))
        composeRule.onNodeWithTag("ignored:dev.quiet").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals("dev.quiet", resumed)

        composeRule.onNodeWithTag("myAppsList").performScrollToNode(hasTestTag("installed:dev.current"))
        composeRule.onNodeWithTag("installed:dev.current").assertIsDisplayed()
        composeRule.onNodeWithTag("store:dev.fdroid").assertIsDisplayed()
        composeRule.onNodeWithTag("store:dev.play").assertDoesNotExist()
        composeRule.onNodeWithTag("myAppsList").performScrollToNode(hasText("Sideloaded"))
        composeRule.onNodeWithText("Sideloaded").assertIsDisplayed()
    }

    @Test
    fun myAppsSearchReplacesTheGroupsWithMatches() {
        var typed = ""
        var state by mutableStateOf(UpdatesUiState(ignored = listOf(IgnoredApp(app("dev.quiet", "Quiet"), installer = null)), loaded = true))
        composeRule.setContent {
            ZapstoreTheme {
                UpdatesScreen(state = state, onSync = {}, onAppClick = {}, onSearchQueryChanged = { typed = it })
            }
        }
        composeRule.onNodeWithText("All up to date").assertIsDisplayed()
        composeRule.onNodeWithTag("sectionIgnored").assertIsDisplayed()
        composeRule.onNodeWithTag("myAppsSearch").performTextInput("music")
        assertEquals("", typed)
        composeRule.onNodeWithTag("myAppsSearch").performImeAction()
        assertEquals("music", typed)
        state = state.copy(searchResults = listOf(app("dev.music", "Music Player")))
        composeRule.onNodeWithTag("mine:dev.music").assertIsDisplayed()
        composeRule.onNodeWithTag("sectionIgnored").assertDoesNotExist()
        composeRule.onNodeWithTag("sectionUpdates").assertDoesNotExist()
        state = state.copy(
            searchResults = emptyList(),
            otherSearchResults = listOf(InstalledApp("dev.tune", 1, "1.0", emptySet(), label = "Tune Box")),
        )
        composeRule.onNodeWithText("Tune Box").assertIsDisplayed()
        state = state.copy(otherSearchResults = emptyList())
        composeRule.onNodeWithTag("myAppsNoMatches").assertIsDisplayed()
    }

    @Test
    fun installSheetNamesTheSourceOfACatalogSignedApp() {
        var confirmed = false
        composeRule.setContent {
            ZapstoreTheme {
                InstallSheet(
                    app = app(
                        "dev.zap", "Zap", catalogSigned = true,
                        extra = { put("download_url", "https://github.com/zap/zap/releases/download/v1/zap.apk") },
                    ),
                    author = null,
                    plan = InstallPlan.FirstInstall,
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag("installCatalogSigned").assertExists()
        composeRule.onNodeWithText("github.com/zap/zap/releases/download/v1/zap.apk").assertExists()
        composeRule.onNodeWithTag("alwaysTrust").assertDoesNotExist()
        composeRule.onNodeWithTag("installConfirm").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun addToStackSheetTogglesAndCreatesStacks() {
        val app = app("dev.zap", "Zap")
        var toggled: String? = null
        var created: String? = null
        var stacks by mutableStateOf(UserStacksUi(
            stacks = listOf(
                StackRecord("aa", "music", "e1", "Music", "", listOf(AppCoordinate("bb", "dev.zap")), 1),
                StackRecord("aa", "tools", "e2", "Tools", "", listOf(AppCoordinate("bb", "dev.other")), 1),
            ),
        ))
        composeRule.setContent {
            ZapstoreTheme {
                AddToStackSheet(
                    app = app,
                    state = stacks,
                    onToggle = { toggled = it.identifier },
                    onCreate = { created = it },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("Tools").performClick()
        assertEquals("tools", toggled)
        composeRule.onNodeWithText("New stack").performClick()
        composeRule.onNodeWithTag("newStackName").performTextInput("Daily")
        composeRule.onNodeWithTag("createStack").assertIsEnabled().assertIsDisplayed().performClick()
        assertEquals("Daily", created)
        stacks = stacks.copy(created = 1)
        composeRule.onNodeWithTag("newStack").assertIsDisplayed()
        composeRule.onNodeWithTag("newStackName").assertDoesNotExist()
    }

    @Test
    fun ownStackRemovesAppsAndRenames() {
        var removed: String? = null
        var renamed: String? = null
        val stack = StackRecord(AUTHOR, "music", "e1", "Music", "", listOf(AppCoordinate(AUTHOR, "dev.zap")), 1)
        val zap = app("dev.zap", "Zap")
        composeRule.setContent {
            ZapstoreTheme {
                StackDetailScreen(
                    state = StackDetailUiState(
                        stack = stack,
                        stackLoading = false,
                        apps = AppListState(apps = listOf(zap), loading = false),
                        appsById = mapOf(zap.appId to zap),
                        own = true,
                    ),
                    onAppClick = {},
                    onRemoveApp = { removed = it },
                    onRename = { renamed = it },
                )
            }
        }
        composeRule.onNodeWithTag("removeFromStack:dev.zap").performClick()
        assertEquals("dev.zap", removed)
        composeRule.onNodeWithTag("renameStack").performClick()
        composeRule.onNodeWithTag("stackName").performTextClearance()
        composeRule.onNodeWithTag("stackName").performTextInput("Tunes")
        composeRule.onNodeWithTag("saveStackName").performClick()
        assertEquals("Tunes", renamed)
    }

    @Test
    fun someoneElsesStackHasNoEditControls() {
        val stack = StackRecord(AUTHOR, "music", "e1", "Music", "", listOf(AppCoordinate(AUTHOR, "dev.zap")), 1)
        val zap = app("dev.zap", "Zap")
        composeRule.setContent {
            ZapstoreTheme {
                StackDetailScreen(
                    state = StackDetailUiState(stack = stack, stackLoading = false, apps = AppListState(apps = listOf(zap), loading = false)),
                    onAppClick = {},
                )
            }
        }
        composeRule.onNodeWithTag("app:dev.zap").assertExists()
        composeRule.onNodeWithTag("removeFromStack:dev.zap").assertDoesNotExist()
        composeRule.onNodeWithTag("renameStack").assertDoesNotExist()
    }

    @Test
    fun signedOutProfileOffersSignInOrAmberAndEndsInSettings() {
        var hasSigner by mutableStateOf(true)
        var signIn = false
        var amber = false
        composeRule.setContent {
            ZapstoreTheme {
                SignedOutProfile(
                    hasSigner = hasSigner,
                    signingIn = false,
                    error = null,
                    onSignIn = { signIn = true },
                    onGetAmber = { amber = true },
                    onSettings = { },
                )
            }
        }
        composeRule.onNodeWithTag("profileSignInRow").performClick()
        composeRule.onNodeWithTag("openSettings").assertExists()
        composeRule.onNodeWithTag("settingsSlot").assertDoesNotExist()
        hasSigner = false
        composeRule.onNodeWithTag("getAmber").performClick()
        composeRule.runOnIdle {
            assertTrue(signIn)
            assertTrue(amber)
        }
    }

    @Test
    fun appDetailPostsCommentsAndReportsWhenSignedIn() {
        var posted: Pair<String, CommentRecord?>? = null
        var report: Pair<String, String>? = null
        var signedIn by mutableStateOf(false)
        var signInAsked = false
        var settingsOpened = false
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app("dev.zap", "Zap"),
                        loaded = true,
                        comments = CommentsState(loading = false),
                        social = SocialUi(signedIn = signedIn),
                    ),
                    onOpenUrl = {},
                    onSettingsClick = { settingsOpened = true },
                    onSignIn = { signInAsked = true },
                    onPostComment = { text, parent -> posted = text to parent },
                    onReport = { type, note -> report = type to note },
                )
            }
        }
        // Signed out, the sheet offers sign-in in place of the field, and Post waits.
        composeRule.onNodeWithTag("appDetail").performScrollToKey("commentComposer")
        composeRule.onNodeWithTag("addComment").performClick()
        composeRule.onNodeWithTag("signInPrompt").assertIsDisplayed()
        composeRule.onNodeWithTag("commentField").assertDoesNotExist()
        composeRule.onNodeWithTag("postComment").assertIsNotEnabled()
        composeRule.onNodeWithTag("signInPromptRow").performClick()
        assertTrue(signInAsked)
        assertFalse(settingsOpened)

        signedIn = true
        composeRule.onNodeWithTag("postComment").assertIsNotEnabled()
        composeRule.onNodeWithTag("commentField").performTextInput("Works on my phone")
        composeRule.onNodeWithTag("postComment").performClick()
        assertEquals("Works on my phone" to null, posted)
        composeRule.onNodeWithTag("closeCommentSheet").performClick()

        composeRule.onNodeWithTag("appDetail").performScrollToKey("head")
        composeRule.onNodeWithTag("appMore").performClick()
        composeRule.onNodeWithTag("actionReport").performClick()
        composeRule.onNodeWithTag("reportType:spam").performClick()
        composeRule.onNodeWithTag("reportSend").performClick()
        assertEquals("spam" to "", report)
    }

    @Test
    fun appDetailQuickZapsGoToTheCatalogWithoutADeveloper() {
        var zap: Pair<Long, String>? = null
        var social by mutableStateOf(SocialUi(signedIn = false, zappable = true))
        var settingsOpened = false
        composeRule.setContent {
            ZapstoreTheme {
                AppDetailScreen(
                    state = AppDetailUiState(
                        app = app("dev.zap", "Zap"),
                        loaded = true,
                        comments = CommentsState(loading = false),
                        zaps = ZapSummary(
                            loading = false,
                            recipient = ZapRecipient(AUTHOR, catalog = true),
                            profiles = mapOf(AUTHOR to ProfileRecord(AUTHOR, 0, "a".repeat(64), "Some Catalog", null, null, null, null, null, null)),
                        ),
                        social = social,
                    ),
                    onOpenUrl = {},
                    onSettingsClick = { settingsOpened = true },
                    onZap = { sats, note -> zap = sats to note },
                    onZapClosed = { social = social.copy(zapError = null, zapped = null, invoice = null) },
                )
            }
        }
        composeRule.onNodeWithTag("appDetail").performScrollToKey("zaps")
        composeRule.onNodeWithTag("zapOpen").performClick()

        // Amounts live in the sheet. Sign in stays here; it does not open Settings.
        composeRule.onNodeWithText("Some Catalog, the catalog that brought it to you", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("zapAmount:10000").assertIsDisplayed()
        composeRule.onNodeWithTag("zapSignIn").performClick()
        composeRule.onNodeWithTag("signInSheet").assertIsDisplayed()
        assertFalse(settingsOpened)

        social = social.copy(signedIn = true)
        composeRule.onNodeWithTag("zapAmount:21000").performClick()
        composeRule.onNodeWithTag("zapNote").performTextInput("Great app")
        composeRule.onNodeWithTag("zapSend").performClick()
        assertEquals(21_000L to "Great app", zap)

        // No wallet connected: the invoice comes back instead of a hard error, and Try again reopens the form.
        social = social.copy(invoice = "lnbc1examplepaymentrequest")
        composeRule.onNodeWithTag("zapInvoice").assertIsDisplayed()
        composeRule.onNodeWithTag("zapRetry").performClick()
        composeRule.onNodeWithTag("zapSend").assertIsDisplayed()

        social = social.copy(zapped = 21_000)
        composeRule.onNodeWithTag("zapSent").assertIsDisplayed()
    }

    @Test
    fun settingsWalletConnectsAndDisconnects() {
        var wallet by mutableStateOf(WalletUi())
        var submitted: String? = null
        var disconnected = false
        composeRule.setContent {
            ZapstoreTheme {
                SettingsSection(modifier = Modifier.verticalScroll(rememberScrollState()), 
                    state = SettingsUiState(wallet = wallet),
                    onNetworkModeChange = {},
                    onSync = {},
                    onWipe = {},
                    onOpenWallet = { wallet = wallet.copy(pasting = true) },
                    onConnectWallet = { submitted = it; wallet = wallet.copy(pasteError = "That is not a Nostr Wallet Connect string.") },
                    onDisconnectWallet = { disconnected = true },
                )
            }
        }
        composeRule.onNodeWithTag("connectWallet").performClick()
        composeRule.onNodeWithTag("walletPasteField").performTextInput("nope")
        composeRule.onNodeWithTag("walletPasteSubmit").performClick()
        assertEquals("nope", submitted)
        composeRule.onNodeWithTag("walletPasteError").assertIsDisplayed()

        wallet = WalletUi(connected = true)
        composeRule.onNodeWithTag("walletConnected").assertIsDisplayed()
        composeRule.onNodeWithTag("disconnectWallet").performClick()
        assertTrue(disconnected)
    }

    @Test
    fun settingsDeviceKeyCopiesPastesAndConfirms() {
        var copied = false
        var submitted: String? = null
        var confirmed = false
        var state by mutableStateOf(SettingsUiState(key = DeviceKeyUi(npub = "npub1example0000000000000000000000000000000000000000000000000")))
        composeRule.setContent {
            ZapstoreTheme {
                SettingsSection(modifier = Modifier.verticalScroll(rememberScrollState()), 
                    state = state,
                    onNetworkModeChange = {},
                    onSync = {},
                    onWipe = {},
                    onCopyKey = { copied = true },
                    onOpenPaste = { state = state.copy(key = state.key.copy(pasting = true)) },
                    onSubmitPaste = { submitted = it },
                    onConfirmReplace = { confirmed = true },
                )
            }
        }
        assertTrue(composeRule.onAllNodesWithTag("backUpKey").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("openKeys").performScrollTo().performClick()
        composeRule.onNodeWithTag("copyKey").performScrollTo().performClick()
        assertTrue(copied)
        composeRule.onNodeWithTag("pasteKey").performScrollTo().performClick()
        composeRule.onNodeWithTag("pasteKeyField").performTextInput("nsec1abc")
        composeRule.onNodeWithTag("pasteKeySubmit").performClick()
        assertEquals("nsec1abc", submitted)
        state = state.copy(key = state.key.copy(pasting = false, confirmReplace = true))
        composeRule.onNodeWithTag("replaceKeyConfirm").performClick()
        assertTrue(confirmed)
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

        // No security data, so the page offers the report through a plain card.
        composeRule.onNodeWithTag("appDetail").performScrollToKey("security")
        composeRule.onNodeWithTag("seeFullReport").performClick()
        composeRule.onNodeWithText("https://example.com/repo").performScrollTo().performClick()

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
            root = null,
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
        composeRule.onNode(hasTestTag("profile:$AUTHOR") and hasAnyAncestor(hasTestTag("comment:${comment.eventId}"))).performClick()
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
            root = null,
            parentEventId = null,
        )
        val reply = CommentRecord(
            eventId = "b".repeat(64),
            pubkey = AUTHOR,
            createdAt = 1_750_000_100,
            content = "Nested reply",
            appId = "dev.zap",
            root = null,
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

        composeRule.onNodeWithTag("appDetail").performScrollToNode(hasText("Nested reply"))
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
        composeRule.onNodeWithText("1.0").assertIsDisplayed()
        composeRule.onNodeWithText("2.0").assertIsDisplayed()
        composeRule.onNodeWithTag("syncCatalog").performClick()
        assertTrue(synced)
    }

    @Test
    fun settingsShowsLastSyncDuration() {
        composeRule.setContent {
            ZapstoreTheme {
                SettingsSection(modifier = Modifier.verticalScroll(rememberScrollState()), 
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
        composeRule.onNodeWithTag("openCatalog").performScrollTo().performClick()
        composeRule.onNodeWithTag("lastSyncAgo").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Last sync 3 minutes ago").assertIsDisplayed()
        composeRule.onNodeWithTag("lastSyncSize").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("transferred", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("lastSyncDuration").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Last sync took", substring = true).assertIsDisplayed()
    }

    @Test
    fun settingsShowsLastSyncNotModified() {
        composeRule.setContent {
            ZapstoreTheme {
                SettingsSection(modifier = Modifier.verticalScroll(rememberScrollState()), 
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
        composeRule.onNodeWithTag("openCatalog").performScrollTo().performClick()
        composeRule.onNodeWithTag("lastSyncAgo").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Last sync just now").assertIsDisplayed()
        composeRule.onNodeWithTag("lastSyncNotModified").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Already current (HTTP 304)").assertIsDisplayed()
    }

    @Test
    fun settingsListsCatalogsInOrderAndWiresTheirActions() {
        var moved: Pair<Long, Int>? = null
        var asked: CatalogUi? = null
        val first = CatalogUi(1, "npub1first0000000000000000000000000000000000000000000000000", listOf("wss://relay.zapstore.dev"), 7, isDefault = true, canMoveUp = false, canMoveDown = true)
        val second = CatalogUi(2, "npub1second000000000000000000000000000000000000000000000000", emptyList(), 0, isDefault = false, canMoveUp = true, canMoveDown = false)
        composeRule.setContent {
            ZapstoreTheme {
                SettingsSection(modifier = Modifier.verticalScroll(rememberScrollState()), 
                    state = SettingsUiState(catalogs = listOf(first, second)),
                    onNetworkModeChange = {},
                    onSync = {},
                    onWipe = {},
                    onMoveCatalog = { id, delta -> moved = id to delta },
                    onAskRemoveCatalog = { asked = it },
                )
            }
        }
        composeRule.onNodeWithTag("openCatalog").performScrollTo().performClick()
        composeRule.onNodeWithTag("catalog:1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("catalog:2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("catalogsEmpty").assertDoesNotExist()
        composeRule.onNodeWithTag("catalogUp:1").assertIsNotEnabled()
        composeRule.onNodeWithTag("catalogDown:2").assertIsNotEnabled()
        composeRule.onNodeWithTag("catalogDown:1").performScrollTo().performClick()
        assertEquals(1L to 1, moved)
        composeRule.onNodeWithTag("catalogRemove:2").performScrollTo().performClick()
        assertEquals(second, asked)
    }

    @Test
    fun settingsWithNoCatalogSaysSoAndOffersToAddOne() {
        var opened = false
        composeRule.setContent {
            ZapstoreTheme {
                SettingsSection(modifier = Modifier.verticalScroll(rememberScrollState()), 
                    state = SettingsUiState(),
                    onNetworkModeChange = {},
                    onSync = {},
                    onWipe = {},
                    onOpenAddCatalog = { opened = true },
                )
            }
        }
        composeRule.onNodeWithTag("openCatalog").performScrollTo().performClick()
        composeRule.onNodeWithTag("catalogsEmpty").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("addCatalog").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun settingsAddCatalogSheetTakesAVisibleKeyAndShowsItsError() {
        var submitted: String? = null
        composeRule.setContent {
            ZapstoreTheme {
                SettingsSection(modifier = Modifier.verticalScroll(rememberScrollState()), 
                    state = SettingsUiState(catalogSheet = CatalogSheetUi(adding = true, error = "No catalog was found")),
                    onNetworkModeChange = {},
                    onSync = {},
                    onWipe = {},
                    onAddCatalog = { submitted = it },
                )
            }
        }
        composeRule.onNodeWithTag("catalogAddError").assertIsDisplayed()
        composeRule.onNodeWithTag("catalogAddField").performTextInput("npub1abc")
        composeRule.onNodeWithTag("catalogAddField").assertTextContains("npub1abc")
        composeRule.onNodeWithTag("catalogAddSubmit").performClick()
        assertEquals("npub1abc", submitted)
    }

    @Test
    fun settingsWipeButtonIsWired() {
        var wiped = false
        composeRule.setContent {
            ZapstoreTheme {
                SettingsSection(modifier = Modifier.verticalScroll(rememberScrollState()), state = SettingsUiState(), onNetworkModeChange = {}, onSync = {}, onWipe = { wiped = true })
            }
        }
        composeRule.onNodeWithTag("openCatalog").performScrollTo().performClick()
        composeRule.onNodeWithTag("clearLocalStorage").performScrollTo().performClick()
        assertTrue(wiped)
    }

    @Test
    fun settingsShowsAllNetworkModes() {
        composeRule.setContent {
            ZapstoreTheme {
                SettingsSection(modifier = Modifier.verticalScroll(rememberScrollState()), state = SettingsUiState(), onNetworkModeChange = {}, onSync = {}, onWipe = {})
            }
        }
        composeRule.onNodeWithTag("networkTor").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("networkDirect").assertIsDisplayed()
        composeRule.onNodeWithTag("networkTorFallback").assertDoesNotExist()
        composeRule.onNodeWithText("Tor").assertIsDisplayed()
        composeRule.onNodeWithText("Direct").assertIsDisplayed()
    }
}

private val AUTHOR = "1".repeat(64)

private fun pressBack() {
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
}

private fun Color.matches(expected: Color): Boolean {
    fun near(left: Float, right: Float) = kotlin.math.abs(left - right) < 0.08f
    return alpha > 0.9f && near(red, expected.red) && near(green, expected.green) && near(blue, expected.blue)
}

private fun app(
    appId: String,
    name: String,
    version: String = "1.0",
    repository: String? = null,
    proofPubkey: String? = null,
    facts: String = "",
    security: String = "",
    description: String? = null,
    catalogSigned: Boolean = false,
    extra: JSONObject.() -> Unit = {},
): AppRecord = AppRecord(
    id = appId.toByteArray(),
    catalogId = 1,
    appId = appId,
    certificateHash = "a".repeat(64),
    createdAt = 1_750_000_000,
    proofPubkey = proofPubkey,
    eventPubkey = AUTHOR,
    catalogManifestPubkey = if (catalogSigned) AUTHOR else null,
    name = name,
    summary = "$name summary",
    repository = repository,
    version = version,
    versionCode = 1,
    channel = null,
    metadata = JSONObject().apply { description?.let { put("description", it) }; extra() },
    facts = facts,
    security = security,
)
