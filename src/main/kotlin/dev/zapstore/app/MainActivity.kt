package dev.zapstore.app

import dev.zapstore.app.screens.rememberSavedApps
import dev.zapstore.app.screens.SavedScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import dev.zapstore.iolite.toNpub
import dev.zapstore.iolite.Query
import dev.zapstore.app.components.NavAvatar
import dev.zapstore.app.components.ToastHost
import dev.zapstore.app.screens.SignedOutProfile
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import dev.zapstore.app.account.Nip55Bridge
import dev.zapstore.app.install.UpdateNotifier
import android.content.ClipData
import dev.zapstore.app.screens.OnboardingViewModel
import dev.zapstore.app.screens.OnboardingScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.os.Build
import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import dev.zapstore.app.components.LocalInstalledVersions
import dev.zapstore.app.components.LocalPendingUpdates
import dev.zapstore.app.components.ZapNavigationBar
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.net.toUri
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.zapstore.app.install.hasOtherProfiles
import dev.zapstore.app.install.uninstallIntent
import dev.zapstore.app.screens.AppDetailScreen
import dev.zapstore.app.screens.AppDetailViewModel
import dev.zapstore.app.screens.HomeScreen
import dev.zapstore.app.screens.HomeViewModel
import dev.zapstore.app.screens.ProfileScreen
import dev.zapstore.app.screens.ProfileViewModel
import dev.zapstore.app.screens.Routes
import dev.zapstore.app.screens.SettingsSection
import dev.zapstore.app.screens.SettingsViewModel
import dev.zapstore.app.screens.StackDetailScreen
import dev.zapstore.app.screens.DiagnosticsScreen
import dev.zapstore.app.screens.DiagnosticsViewModel
import dev.zapstore.app.screens.StackDetailViewModel
import dev.zapstore.app.screens.StacksScreen
import dev.zapstore.app.screens.StacksViewModel
import dev.zapstore.app.screens.ReinstallDialog
import dev.zapstore.app.screens.UninstallProfilesDialog
import dev.zapstore.app.screens.UpdatesScreen
import dev.zapstore.app.screens.UpdatesViewModel
import dev.zapstore.iolite.AppRecord

class MainActivity : ComponentActivity() {
    private val zapstore: ZapstoreApplication get() = application as ZapstoreApplication

    /** Emits a tab route when its tab is tapped while already showing; the screen scrolls to top. */
    private val reselected = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** A link the app was opened with, until the nav host has shown it. */
    private val pendingLink = MutableStateFlow<DeepLink?>(null)

    /** Home has been composed with all of its local data. Read by [LaunchHold]. */
    private var homeShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        zapstore.account.bridge.attach(this)
        if (savedInstanceState == null) pendingLink.value = intent.deepLink()
        setContent {
            ZapstoreTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    var onboarding by remember { mutableStateOf(!zapstore.onboarding.done) }
                    if (onboarding) OnboardingFlow(onFinished = { onboarding = false })
                    else ZapstoreNavHost(rememberNavController())
                }
            }
        }
        holdForFirstScreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.deepLink()?.let { pendingLink.value = it }
    }

    /**
     * Home's stacks, apps and activity come from SQLite a moment after launch, so its first frame is empty. Hold
     * the first frame, and with it the system splash, until Home has them. Onboarding has nothing to wait for.
     */
    private fun holdForFirstScreen() {
        if (zapstore.onboarding.done) LaunchHold(findViewById(android.R.id.content), LAUNCH_HOLD_MILLIS) { homeShown }.attach()
    }

    private fun Intent.deepLink(): DeepLink? =
        if (action == UpdateNotifier.ACTION_SHOW_UPDATES) DeepLink.MyApps
        else dataString?.takeIf { action == Intent.ACTION_VIEW }?.let(DeepLink::parse)

    override fun onDestroy() {
        zapstore.account.bridge.detach(this)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        zapstore.installer.setVisible(true)
    }

    override fun onStop() {
        zapstore.installer.setVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        zapstore.installer.setResumed(true)
        zapstore.iolite.refreshConnections()
        UpdateNotifier.clear(this)
    }

    override fun onPause() {
        zapstore.installer.setResumed(false)
        super.onPause()
    }

    @androidx.compose.runtime.Composable
    private fun OnboardingFlow(onFinished: () -> Unit) {
        val viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(zapstore))
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.next() }
        LaunchedEffect(state.finished) { if (state.finished) onFinished() }
        LifecycleResumeEffect(viewModel) {
            viewModel.refreshPermissions()
            onPauseOrDispose {}
        }
        OnboardingScreen(
            state = state,
            onNext = viewModel::next,
            onSkipAll = viewModel::skipAll,
            onNetworkModeChange = viewModel::setNetworkMode,
            onBugReportsChange = viewModel::setBugReports,
            onConnectSigner = viewModel::connectSigner,
            onGetAmber = {
                pendingLink.value = DeepLink.App(Nip55Bridge.AMBER)
                viewModel.next()
            },
            onOpenInstallSettings = ::openUnknownSourcesSettings,
            onRequestNotifications = {
                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.next()
            },
        )
    }

    private fun openUnknownSourcesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())
        runCatching { startActivity(intent) }
    }

    @androidx.compose.runtime.Composable
    private fun ZapstoreNavHost(navController: NavHostController) {
        val iolite = zapstore.iolite
        val openApp = { app: AppRecord -> navController.navigate(Routes.app(app.appId)) }
        val openComment = { app: AppRecord, commentId: String -> navController.navigate(Routes.app(app.appId, commentId)) }
        val openProfile = { pubkey: String -> navController.navigate(Routes.profile(pubkey)) }
        var uninstallPrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
        var reinstallPrompt by remember { mutableStateOf<AppRecord?>(null) }
        // Uninstalled for a reinstall: once Android's dialog is gone and so is the package, install the listed build.
        var reinstalling by remember { mutableStateOf<AppRecord?>(null) }
        val uninstallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            zapstore.catalogSync.refreshInstalled()
            val app = reinstalling ?: return@rememberLauncherForActivityResult
            reinstalling = null
            val gone = runCatching { packageManager.getPackageInfo(app.appId, 0) }.isFailure
            if (gone) zapstore.installer.install(app)
        }
        val requestUninstall = { packageId: String, name: String ->
            if (hasOtherProfiles()) uninstallPrompt = packageId to name
            else uninstallLauncher.launch(uninstallIntent(packageId))
        }
        val requestReinstall = { app: AppRecord ->
            if (packageManager.canRequestPackageInstalls()) reinstallPrompt = app else openUnknownSourcesSettings()
        }

        val link by pendingLink.collectAsStateWithLifecycle()
        LaunchedEffect(link) {
            val target = link ?: return@LaunchedEffect
            pendingLink.value = null
            when (target) {
                is DeepLink.App -> navController.navigate(Routes.app(target.appId))
                is DeepLink.Stack -> navController.navigate(Routes.stack(target.pubkey, target.identifier))
                is DeepLink.Profile -> navController.navigate(Routes.profile(target.pubkey))
                is DeepLink.Search -> {
                    showTab(navController, Routes.HOME)
                    if (target.query.isNotEmpty()) openHomeSearch(navController, target.query)
                }
                DeepLink.Stacks -> {
                    showTab(navController, Routes.HOME)
                    navController.navigate(Routes.STACKS) { launchSingleTop = true }
                }
                DeepLink.MyApps -> showTab(navController, Routes.UPDATES)
                is DeepLink.External -> openExternal(target.url)
            }
        }
        val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
        val currentTab = backStack.lastOrNull { it.destination.route in Routes.TABS }?.destination?.route ?: Routes.HOME
        val updates by zapstore.catalogSync.updates.collectAsStateWithLifecycle(emptyList())
        val updateCount = updates.size
        val pendingUpdates = remember(updates) { updates.associate { it.app.appId to it.installedVersion } }
        val installedApps by zapstore.catalogSync.installedApps.collectAsStateWithLifecycle()
        val installedVersions = remember(installedApps) { installedApps.associate { it.packageId to it.versionName } }
        val selectTab: (String) -> Unit = { route ->
            if (route == currentTab && backStack.lastOrNull()?.destination?.route == route) {
                reselected.tryEmit(route)
            } else if (route == currentTab) {
                navController.popBackStack(route, inclusive = false)
            } else {
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }

        // Loaded from launch, so My apps opens on finished content.
        val updatesModel: UpdatesViewModel = viewModel(factory = UpdatesViewModel.factory(zapstore))
        val updatesState = updatesModel.uiState.collectAsStateWithLifecycle()

        val signedIn by zapstore.account.account.collectAsStateWithLifecycle()
        val navAvatar by remember(signedIn?.pubkey) {
            val pubkey = signedIn?.pubkey
            if (pubkey == null) flowOf(null)
            else zapstore.iolite.query(Query.profile(pubkey), AppConfig.profileQuery)
                .map { NavAvatar(pubkey, it.items?.picture, it.items?.eventId) }
        }.collectAsStateWithLifecycle(null)

        ToastHost()
        CompositionLocalProvider(LocalPendingUpdates provides pendingUpdates, LocalInstalledVersions provides installedVersions) {
        Scaffold(
            containerColor = ZapCanvas,
            bottomBar = { ZapNavigationBar(currentTab, updateCount, selectTab, avatar = navAvatar) },
        ) { inner ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(inner).consumeWindowInsets(inner),
            enterTransition = { if (switchingTabs()) fadeIn(tween(NAVIGATION_TRANSITION_DURATION)) else slide(AnimatedContentTransitionScope.SlideDirection.Left) },
            exitTransition = { if (switchingTabs()) fadeOut(tween(NAVIGATION_TRANSITION_DURATION)) else slideOut(AnimatedContentTransitionScope.SlideDirection.Left) },
            popEnterTransition = { if (switchingTabs()) fadeIn(tween(NAVIGATION_TRANSITION_DURATION)) else slide(AnimatedContentTransitionScope.SlideDirection.Right) },
            popExitTransition = { if (switchingTabs()) fadeOut(tween(NAVIGATION_TRANSITION_DURATION)) else slideOut(AnimatedContentTransitionScope.SlideDirection.Right) },
        ) {
            composable(Routes.HOME) {
                val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(iolite, zapstore.settings, zapstore.queryEncoder::encode))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                SideEffect { homeShown = state.ready }
                HomeScreen(
                    state = state,
                    onSearchQueryChanged = viewModel::onSearchQueryChanged,
                    onSearchSubmitted = viewModel::submitSearch,
                    onSearchCleared = viewModel::clearSearch,
                    onAppClick = openApp,
                    onCommentClick = openComment,
                    onStackClick = { navController.navigate(Routes.stack(it.pubkey, it.identifier)) },
                    onDeveloperClick = openProfile,
                    onSeeAllStacks = { navController.navigate(Routes.STACKS) },
                    onRemoveFilter = viewModel::removeFilter,
                    onToggleFilter = viewModel::toggleFilter,
                    onActivityFilter = viewModel::setActivityFilter,
                    onLoadMoreStacks = viewModel::loadMoreStacks,
                    onLoadMoreActivity = viewModel::loadMoreActivity,
                    reselected = reselected,
                )
            }

            composable(Routes.STACKS) {
                val viewModel: StacksViewModel = viewModel(factory = StacksViewModel.factory(iolite))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                StacksScreen(
                    state = state,
                    onStackClick = { navController.navigate(Routes.stack(it.pubkey, it.identifier)) },
                    onProfileClick = openProfile,
                    onLoadMore = viewModel::loadMore,
                )
            }

            composable(Routes.UPDATES) {
                val viewModel = updatesModel
                UpdatesScreen(
                    state = updatesState.value,
                    onSync = viewModel::sync,
                    onAppClick = openApp,
                    onUninstall = { requestUninstall(it.appId, it.name) },
                    onUninstallInstalled = { packageId, name -> requestUninstall(packageId, name) },
                    onUpdateAll = { group ->
                        if (packageManager.canRequestPackageInstalls()) viewModel.prepareUpdateAll(group)
                        else openUnknownSourcesSettings()
                    },
                    onUpdateOne = { update ->
                        if (packageManager.canRequestPackageInstalls()) viewModel.updateOne(update)
                        else openUnknownSourcesSettings()
                    },
                    onConfirmUpdateAll = viewModel::confirmUpdateAll,
                    onDismissUpdateAll = viewModel::dismissUpdateAll,
                    onResumeUpdates = viewModel::resumeUpdates,
                    onIgnoreUpdates = viewModel::ignoreUpdates,
                    onPause = viewModel::pause,
                    onInstall = { app ->
                        if (packageManager.canRequestPackageInstalls()) viewModel.install(app)
                        else openUnknownSourcesSettings()
                    },
                    onCancelInstall = viewModel::cancelInstall,
                    onReinstall = requestReinstall,
                    onSearchQueryChanged = viewModel::setSearchQuery,
                )
            }

            composable(Routes.SETTINGS) {
                val settingsModel: SettingsViewModel =
                    viewModel(
                        factory = SettingsViewModel.factory(
                            iolite,
                            zapstore.catalogSync,
                            zapstore.network,
                            zapstore.settings,
                            zapstore.account,
                            applicationContext::localStorageBytes,
                            zapstore.keyBackup,
                            ::getString,
                            zapstore.deviceSigner.publicKey,
                            zapstore.wallet,
                        ),
                    )
                val settingsState by settingsModel.uiState.collectAsStateWithLifecycle()
                SettingsSection(
                    state = settingsState,
                    onNetworkModeChange = settingsModel::setNetworkMode,
                    onSync = settingsModel::sync,
                    onWipe = settingsModel::wipe,
                    onSignOut = settingsModel::signOut,
                    onUpdateIntervalChange = settingsModel::setUpdateInterval,
                    onSyncIntervalChange = settingsModel::setSyncInterval,
                    onAnyNetworkChange = settingsModel::setAnyNetwork,
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onBugReportsChange = settingsModel::setBugReports,
                    onBackUpKey = settingsModel::backUpKey,
                    onRestoreFromSigner = settingsModel::restoreFromSigner,
                    onCopyKey = {
                        copySecret(getString(R.string.device_key), settingsModel.keyNsec())
                        settingsModel.keyCopied()
                    },
                    onOpenPaste = settingsModel::openPaste,
                    onClosePaste = settingsModel::closePaste,
                    onSubmitPaste = settingsModel::submitPaste,
                    onConfirmReplace = settingsModel::confirmReplace,
                    onDismissReplace = settingsModel::dismissReplace,
                    onOpenWallet = settingsModel::openWallet,
                    onCloseWallet = settingsModel::closeWallet,
                    onConnectWallet = settingsModel::connectWallet,
                    onDisconnectWallet = { settingsModel.disconnectWallet() },
                    onOpenAddCatalog = settingsModel::openAddCatalog,
                    onCloseAddCatalog = settingsModel::closeAddCatalog,
                    onAddCatalog = settingsModel::addCatalog,
                    onAskRemoveCatalog = settingsModel::askRemoveCatalog,
                    onCancelRemoveCatalog = settingsModel::cancelRemoveCatalog,
                    onConfirmRemoveCatalog = settingsModel::confirmRemoveCatalog,
                    onOpenUrl = ::openUrl,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ZapCanvas)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(rememberScrollState())
                        .padding(top = ZapSpacing.space2, bottom = ZapSpacing.space8),
                )
            }

            composable(Routes.ME) {
                val account by zapstore.account.account.collectAsStateWithLifecycle()
                val openSettings = { navController.navigate(Routes.SETTINGS) }
                val saved by rememberSavedApps(iolite, zapstore.lists.saved)
                val openSaved = { navController.navigate(Routes.SAVED) }
                val pubkey = account?.pubkey
                if (pubkey != null) {
                    val viewModel: ProfileViewModel = viewModel(key = "me:$pubkey", factory = ProfileViewModel.factory(iolite, pubkey))
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    ProfileScreen(
                        state = state,
                        onOpenUrl = ::openUrl,
                        onAppClick = openApp,
                        onCommentClick = openComment,
                        onStackClick = { navController.navigate(Routes.stack(it.pubkey, it.identifier)) },
                        onSettings = openSettings,
                        saved = saved,
                        onSavedClick = openSaved,
                        onLoadMoreActivity = viewModel::loadMoreActivity,
                        onShare = { shareProfile(pubkey) },
                    )
                } else {
                    var resumed by remember { mutableIntStateOf(0) }
                    LifecycleResumeEffect(Unit) {
                        resumed++
                        onPauseOrDispose {}
                    }
                    val hasSigner = remember(resumed) { zapstore.account.bridge.installedSigners().isNotEmpty() }
                    var signingIn by remember { mutableStateOf(false) }
                    var signInError by remember { mutableStateOf<String?>(null) }
                    val scope = rememberCoroutineScope()
                    SignedOutProfile(
                        hasSigner = hasSigner,
                        signingIn = signingIn,
                        error = signInError,
                        onSignIn = {
                            signingIn = true
                            signInError = null
                            scope.launch {
                                signInError = runCatching { zapstore.account.signIn() }.exceptionOrNull()?.let { it.message ?: getString(R.string.key_action_failed) }
                                signingIn = false
                            }
                        },
                        onGetAmber = { navController.navigate(Routes.app(Nip55Bridge.AMBER)) },
                        saved = saved,
                        onSavedClick = openSaved,
                        onSettings = openSettings,
                    )
                }
            }

            composable(Routes.SAVED) {
                val saved by rememberSavedApps(iolite, zapstore.lists.saved)
                SavedScreen(
                    saved = saved,
                    onAppClick = openApp,
                    onUnsave = { app ->
                        zapstore.lists.setSaved(app.appId, false)
                        Toasts.show(getString(R.string.toast_unsaved))
                    },
                )
            }

            composable(Routes.DIAGNOSTICS) {
                val viewModel: DiagnosticsViewModel = viewModel()
                val log by viewModel.log.collectAsStateWithLifecycle()
                DiagnosticsScreen(
                    log = log,
                    onShare = { shareText(it) },
                    onCopy = { text ->
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostics), text))
                    },
                    onClear = viewModel::clear,
                )
            }

            composable(
                route = Routes.STACK,
                arguments = listOf(
                    navArgument(Routes.STACK_AUTHOR_ARG) { type = NavType.StringType },
                    navArgument(Routes.STACK_ID_ARG) { type = NavType.StringType },
                ),
            ) {
                val viewModel: StackDetailViewModel = viewModel(factory = StackDetailViewModel.factory(zapstore))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                StackDetailScreen(
                    state = state,
                    onAppClick = openApp,
                    onProfileClick = openProfile,
                    onRemoveApp = viewModel::removeApp,
                    onRename = viewModel::rename,
                    onLoadMoreComments = viewModel::loadMoreComments,
                    onPostComment = viewModel::postComment,
                    onSignIn = viewModel::signIn,
                    onOpenUrl = ::openUrl,
                    onShare = state.stack?.let { stack ->
                        { shareText("${WEB_URL}stacks/${stack.pubkey.toNpub()}/${stack.identifier}", stack.name) }
                    },
                )
            }

            composable(
                route = Routes.APP,
                arguments = listOf(
                    navArgument(Routes.APP_ID_ARG) { type = NavType.StringType },
                    navArgument(Routes.COMMENT_ARG) { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val viewModel: AppDetailViewModel = viewModel(factory = AppDetailViewModel.factory(zapstore))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val saved by zapstore.lists.saved.collectAsStateWithLifecycle()
                val appId = state.app?.appId
                AppDetailScreen(
                    state = state,
                    focusComment = entry.arguments?.getString(Routes.COMMENT_ARG),
                    onStackClick = { navController.navigate(Routes.stack(it.pubkey, it.identifier)) },
                    saved = appId != null && appId in saved,
                    onSave = { on ->
                        appId?.let {
                            zapstore.lists.setSaved(it, on)
                            Toasts.show(getString(if (on) R.string.toast_saved else R.string.toast_unsaved))
                        }
                    },
                    onShare = {
                        state.app?.let { app ->
                            // The page, then the source when the listing names one.
                            val source = app.repository?.takeIf { it.isNotBlank() }
                            shareText(listOfNotNull("${app.name}\n${WEB_URL}apps/${app.appId}", source).joinToString("\n"), app.name)
                        }
                    },
                    onPostComment = viewModel::postComment,
                    onReport = viewModel::report,
                    onReportClosed = viewModel::clearReport,
                    onZap = viewModel::zap,
                    onZapClosed = viewModel::clearZap,
                    onSignIn = viewModel::signIn,
                    onConnectWallet = viewModel::connectWallet,
                    onPayInvoice = ::payInvoice,
                    onToggleStack = viewModel::toggleInStack,
                    onCreateStack = viewModel::createStack,
                    onOpenUrl = ::openUrl,
                    onProfileClick = openProfile,
                    onRetryComments = viewModel::retryComments,
                    onSettingsClick = { selectTab(Routes.ME) },
                    onInstall = {
                        if (packageManager.canRequestPackageInstalls()) viewModel.prepareInstall()
                        else openUnknownSourcesSettings()
                    },
                    onPauseInstall = viewModel::pauseInstall,
                    onCancelInstall = viewModel::cancelInstall,
                    onStartInstall = viewModel::startInstall,
                    onDismissInstallError = viewModel::dismissError,
                    onConfirmInstall = viewModel::confirmInstall,
                    onDismissInstall = viewModel::dismissInstall,
                    onPersonalizeTrust = viewModel::checkTrustPersonally,
                    onUninstall = { state.app?.let { requestUninstall(it.appId, it.name) } },
                    onReinstall = { state.app?.let(requestReinstall) },
                    onOpenApp = { state.app?.let { app -> packageManager.getLaunchIntentForPackage(app.appId)?.let(::startActivity) } },
                )
            }

            composable(
                route = Routes.PROFILE,
                arguments = listOf(navArgument(Routes.PUBKEY_ARG) { type = NavType.StringType }),
            ) {
                val viewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.factory(iolite))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ProfileScreen(
                    state = state,
                    onOpenUrl = ::openUrl,
                    onAppClick = openApp,
                    onCommentClick = openComment,
                    onStackClick = { navController.navigate(Routes.stack(it.pubkey, it.identifier)) },
                    onLoadMoreActivity = viewModel::loadMoreActivity,
                    onShare = { shareProfile(state.pubkey) },
                )
            }
        }
        }
        }

        uninstallPrompt?.let { (packageId, name) ->
            UninstallProfilesDialog(
                name = name,
                onConfirm = {
                    uninstallPrompt = null
                    uninstallLauncher.launch(uninstallIntent(packageId))
                },
                onDismiss = { uninstallPrompt = null },
            )
        }
        reinstallPrompt?.let { app ->
            ReinstallDialog(
                name = app.name,
                otherProfiles = hasOtherProfiles(),
                onConfirm = {
                    reinstallPrompt = null
                    reinstalling = app
                    uninstallLauncher.launch(uninstallIntent(app.appId))
                },
                onDismiss = { reinstallPrompt = null },
            )
        }
    }

    private fun openUrl(value: String) {
        when (val target = DeepLink.parse(value)) {
            null -> if (isHttpUrl(value)) openExternal(value)
            is DeepLink.External -> openExternal(target.url)
            else -> pendingLink.value = target
        }
    }

    private fun openExternal(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    /** Selects a bottom-bar destination, keeping the other tabs' back stacks. */
    private fun showTab(navController: NavHostController, route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    /** Puts [query] into the home search after [showTab] has the home entry on the stack. */
    private fun openHomeSearch(navController: NavHostController, query: String) {
        val owner = navController.getBackStackEntry(Routes.HOME)
        ViewModelProvider(owner, HomeViewModel.factory(zapstore.iolite, zapstore.settings, zapstore.queryEncoder::encode))
            .get(HomeViewModel::class.java)
            .openSearch(query)
    }

    /** Hands a Lightning invoice to whichever wallet app the person picks. */
    private fun payInvoice(invoice: String) {
        val view = Intent(Intent.ACTION_VIEW, "lightning:$invoice".toUri())
        runCatching { startActivity(Intent.createChooser(view, getString(R.string.zap_pick_wallet))) }
    }

    /** Copies a secret; Android 13+ hides it from the clipboard preview. */
    private fun copySecret(label: String, value: String) {
        if (value.isEmpty()) return
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun shareProfile(pubkey: String) =
        shareText("${WEB_URL}profile/${pubkey.toNpub()}", getString(R.string.action_share))

    private fun shareText(text: String, title: String = getString(R.string.diagnostics)) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        runCatching { startActivity(Intent.createChooser(send, title)) }
    }

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.switchingTabs(): Boolean =
        initialState.destination.route in Routes.TABS && targetState.destination.route in Routes.TABS

    private fun AnimatedContentTransitionScope<*>.slide(direction: AnimatedContentTransitionScope.SlideDirection) =
        slideIntoContainer(direction, tween(NAVIGATION_TRANSITION_DURATION, easing = FastOutSlowInEasing))

    private fun AnimatedContentTransitionScope<*>.slideOut(direction: AnimatedContentTransitionScope.SlideDirection) =
        slideOutOfContainer(direction, tween(NAVIGATION_TRANSITION_DURATION, easing = FastOutSlowInEasing))

    private companion object {
        const val NAVIGATION_TRANSITION_DURATION = 150

        /** The longest the splash waits for the first screen; a stalled read must not trap the user behind it. */
        const val LAUNCH_HOLD_MILLIS = 2_000L
        const val WEB_URL = "https://zapstore.dev/"
    }
}
