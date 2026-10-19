package dev.zapstore.app

import dev.zapstore.app.screens.rememberSavedApps
import dev.zapstore.app.screens.SavedScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import dev.zapstore.app.components.LocalPendingUpdates
import dev.zapstore.app.components.ZapNavigationBar
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import dev.zapstore.app.screens.SettingsScreen
import dev.zapstore.app.screens.SettingsViewModel
import dev.zapstore.app.screens.StackDetailScreen
import dev.zapstore.app.screens.DiagnosticsScreen
import dev.zapstore.app.screens.DiagnosticsViewModel
import dev.zapstore.app.screens.StackDetailViewModel
import dev.zapstore.app.screens.StacksScreen
import dev.zapstore.app.screens.StacksViewModel
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.deepLink()?.let { pendingLink.value = it }
    }

    private fun Intent.deepLink(): DeepLink? =
        if (action == UpdateNotifier.ACTION_SHOW_UPDATES) DeepLink.MyApps
        else dataString?.takeIf { action == Intent.ACTION_VIEW }?.let(DeepLink::parse)

    override fun onDestroy() {
        zapstore.account.bridge.detach(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        zapstore.iolite.refreshConnections()
        UpdateNotifier.clear(this)
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
            onNetworkModeChange = viewModel::setNetworkMode,
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
        val openProfile = { pubkey: String -> navController.navigate(Routes.profile(pubkey)) }
        var uninstallPrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
        val uninstallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            zapstore.catalogSync.refreshInstalled()
        }
        val requestUninstall = { packageId: String, name: String ->
            if (hasOtherProfiles()) uninstallPrompt = packageId to name
            else uninstallLauncher.launch(uninstallIntent(packageId))
        }

        val link by pendingLink.collectAsStateWithLifecycle()
        LaunchedEffect(link) {
            val target = link ?: return@LaunchedEffect
            pendingLink.value = null
            when (target) {
                is DeepLink.App -> navController.navigate(Routes.app(target.appId))
                is DeepLink.Stack -> navController.navigate(Routes.stack(target.pubkey, target.identifier))
                is DeepLink.Profile -> navController.navigate(Routes.profile(target.pubkey))
                DeepLink.MyApps -> navController.navigate(Routes.UPDATES) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
        val backStack by navController.currentBackStack.collectAsStateWithLifecycle()
        val currentTab = backStack.lastOrNull { it.destination.route in Routes.TABS }?.destination?.route ?: Routes.HOME
        val updates by zapstore.catalogSync.updates.collectAsStateWithLifecycle(emptyList())
        val updateCount = updates.size
        val pendingUpdates = remember(updates) { updates.associate { it.app.appId to it.installedVersion } }
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

        val signedIn by zapstore.account.account.collectAsStateWithLifecycle()
        val navAvatar by remember(signedIn?.pubkey) {
            val pubkey = signedIn?.pubkey
            if (pubkey == null) flowOf(null)
            else zapstore.iolite.query(Query.profile(pubkey), AppConfig.profileQuery)
                .map { NavAvatar(pubkey, it.items?.picture, it.items?.eventId) }
        }.collectAsStateWithLifecycle(null)

        ToastHost()
        CompositionLocalProvider(LocalPendingUpdates provides pendingUpdates) {
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
                val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(iolite, zapstore.queryEncoder::encode))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    onSearchQueryChanged = viewModel::onSearchQueryChanged,
                    onSearchSubmitted = viewModel::submitSearch,
                    onSearchCleared = viewModel::clearSearch,
                    onAppClick = openApp,
                    onStackClick = { navController.navigate(Routes.stack(it.pubkey, it.identifier)) },
                    onSeeAllStacks = { navController.navigate(Routes.STACKS) },
                    onRemoveFilter = viewModel::removeFilter,
                    onToggleFilter = viewModel::toggleFilter,
                    onLoadMore = viewModel::loadMore,
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
                    onLoadMore = viewModel::loadMore,
                )
            }

            composable(Routes.UPDATES) {
                val viewModel: UpdatesViewModel = viewModel(factory = UpdatesViewModel.factory(zapstore))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                UpdatesScreen(
                    state = state,
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
                    onSearchQueryChanged = viewModel::setSearchQuery,
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
                        onStackClick = { navController.navigate(Routes.stack(it.pubkey, it.identifier)) },
                        onSettingsClick = openSettings,
                        saved = saved,
                        onSavedClick = openSaved,
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
                        onSettingsClick = openSettings,
                        saved = saved,
                        onSavedClick = openSaved,
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

            composable(Routes.SETTINGS) {
                val viewModel: SettingsViewModel =
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
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = state,
                    onNetworkModeChange = viewModel::setNetworkMode,
                    onSync = viewModel::sync,
                    onWipe = viewModel::wipe,
                    onSignIn = viewModel::signIn,
                    onSignOut = viewModel::signOut,
                    onUpdateIntervalChange = viewModel::setUpdateInterval,
                    onSyncIntervalChange = viewModel::setSyncInterval,
                    onAnyNetworkChange = viewModel::setAnyNetwork,
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onBugReportsChange = viewModel::setBugReports,
                    onRemoveTrustedSigner = viewModel::removeTrustedSigner,
                    onBackUpKey = viewModel::backUpKey,
                    onRestoreFromSigner = viewModel::restoreFromSigner,
                    onCopyKey = {
                        copySecret(getString(R.string.device_key), viewModel.keyNsec())
                        viewModel.keyCopied()
                    },
                    onOpenPaste = viewModel::openPaste,
                    onClosePaste = viewModel::closePaste,
                    onSubmitPaste = viewModel::submitPaste,
                    onConfirmReplace = viewModel::confirmReplace,
                    onDismissReplace = viewModel::dismissReplace,
                    onOpenWallet = viewModel::openWallet,
                    onCloseWallet = viewModel::closeWallet,
                    onConnectWallet = viewModel::connectWallet,
                    onDisconnectWallet = { viewModel.disconnectWallet() },
                    onOpenUrl = ::openUrl,
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
                )
            }

            composable(
                route = Routes.APP,
                arguments = listOf(navArgument(Routes.APP_ID_ARG) { type = NavType.StringType }),
            ) {
                val viewModel: AppDetailViewModel = viewModel(factory = AppDetailViewModel.factory(zapstore))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val saved by zapstore.lists.saved.collectAsStateWithLifecycle()
                val ignored by zapstore.lists.ignored.collectAsStateWithLifecycle()
                val appId = state.app?.appId
                AppDetailScreen(
                    state = state,
                    onStackClick = { navController.navigate(Routes.stack(it.pubkey, it.identifier)) },
                    saved = appId != null && appId in saved,
                    ignored = appId != null && appId in ignored,
                    onSave = { on ->
                        appId?.let {
                            zapstore.lists.setSaved(it, on)
                            Toasts.show(getString(if (on) R.string.toast_saved else R.string.toast_unsaved))
                        }
                    },
                    onIgnore = { on ->
                        appId?.let {
                            zapstore.lists.setIgnored(it, on)
                            Toasts.show(getString(if (on) R.string.toast_ignored else R.string.toast_unignored))
                        }
                    },
                    onShare = { state.app?.let { shareText("$APP_URL${it.appId}", it.name) } },
                    onPostComment = { viewModel.postComment(it) },
                    onReport = viewModel::report,
                    onReportClosed = viewModel::clearReport,
                    onZap = viewModel::zap,
                    onZapClosed = viewModel::clearZap,
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
                    onStartInstall = viewModel::startInstall,
                    onDismissInstallError = viewModel::dismissError,
                    onConfirmInstall = viewModel::confirmInstall,
                    onDismissInstall = viewModel::dismissInstall,
                    onUninstall = { state.app?.let { requestUninstall(it.appId, it.name) } },
                    onOpenApp = { state.app?.let { app -> packageManager.getLaunchIntentForPackage(app.appId)?.let(::startActivity) } },
                    onRefreshOrigin = viewModel::refreshOrigin,
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
                    onStackClick = { navController.navigate(Routes.stack(it.pubkey, it.identifier)) },
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
    }

    private fun openUrl(value: String) {
        if (!isHttpUrl(value)) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, value.toUri())) }
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
        const val APP_URL = "https://zapstore.dev/apps/"
    }
}
