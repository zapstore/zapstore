package dev.zapstore.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import dev.zapstore.app.screens.StackDetailViewModel
import dev.zapstore.app.screens.UpdatesScreen
import dev.zapstore.app.screens.UpdatesViewModel
import dev.zapstore.iolite.AppRecord

class MainActivity : ComponentActivity() {
    private val zapstore: ZapstoreApplication get() = application as ZapstoreApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZapstoreTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    ZapstoreNavHost(rememberNavController())
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestInstallPermission()
        zapstore.iolite.refreshConnections()
    }

    // Once per process. onResume runs again when the user leaves the settings screen.
    private fun requestInstallPermission() {
        if (BuildConfig.DEBUG || installPermissionPrompted || packageManager.canRequestPackageInstalls()) return
        installPermissionPrompted = true
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())
        runCatching { startActivity(intent) }
    }

    @androidx.compose.runtime.Composable
    private fun ZapstoreNavHost(navController: NavHostController) {
        val iolite = zapstore.iolite
        val openApp = { app: AppRecord -> navController.navigate(Routes.app(app.appId)) }
        val openProfile = { pubkey: String -> navController.navigate(Routes.profile(pubkey)) }

        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            enterTransition = { slide(AnimatedContentTransitionScope.SlideDirection.Left) },
            exitTransition = { slideOut(AnimatedContentTransitionScope.SlideDirection.Left) },
            popEnterTransition = { slide(AnimatedContentTransitionScope.SlideDirection.Right) },
            popExitTransition = { slideOut(AnimatedContentTransitionScope.SlideDirection.Right) },
        ) {
            composable(Routes.HOME) {
                val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(iolite, zapstore.queryEncoder::encode))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val updateCount by zapstore.catalogSync.updateCount.collectAsStateWithLifecycle(0)
                HomeScreen(
                    state = state,
                    onSearchQueryChanged = viewModel::onSearchQueryChanged,
                    onSearchSubmitted = viewModel::submitSearch,
                    onSearchCleared = viewModel::clearSearch,
                    onAppClick = openApp,
                    onStackClick = { navController.navigate(Routes.stack(it.pubkey, it.identifier)) },
                    onProfileClick = openProfile,
                    onUpdatesClick = { navController.navigate(Routes.UPDATES) },
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                    updateCount = updateCount,
                    onLoadMore = viewModel::loadMore,
                )
            }

            composable(Routes.UPDATES) {
                val viewModel: UpdatesViewModel = viewModel(factory = UpdatesViewModel.factory(zapstore.catalogSync))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                UpdatesScreen(state = state, onSync = viewModel::sync, onAppClick = openApp)
            }

            composable(Routes.SETTINGS) {
                val viewModel: SettingsViewModel =
                    viewModel(
                        factory = SettingsViewModel.factory(
                            iolite,
                            zapstore.catalogSync,
                            zapstore.network,
                            applicationContext::localStorageBytes,
                        ),
                    )
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = state,
                    onNetworkModeChange = viewModel::setNetworkMode,
                    onSync = viewModel::sync,
                    onWipe = viewModel::wipe,
                )
            }

            composable(
                route = Routes.STACK,
                arguments = listOf(
                    navArgument(Routes.STACK_AUTHOR_ARG) { type = NavType.StringType },
                    navArgument(Routes.STACK_ID_ARG) { type = NavType.StringType },
                ),
            ) {
                val viewModel: StackDetailViewModel = viewModel(factory = StackDetailViewModel.factory(iolite))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                StackDetailScreen(state = state, onAppClick = openApp, onProfileClick = openProfile)
            }

            composable(
                route = Routes.APP,
                arguments = listOf(navArgument(Routes.APP_ID_ARG) { type = NavType.StringType }),
            ) {
                val viewModel: AppDetailViewModel = viewModel(factory = AppDetailViewModel.factory(iolite))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                AppDetailScreen(
                    state = state,
                    onOpenUrl = ::openUrl,
                    onProfileClick = openProfile,
                    onRetryComments = viewModel::retryComments,
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                )
            }

            composable(
                route = Routes.PROFILE,
                arguments = listOf(navArgument(Routes.PUBKEY_ARG) { type = NavType.StringType }),
            ) {
                val viewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.factory(iolite))
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ProfileScreen(state = state, onOpenUrl = ::openUrl, onAppClick = openApp)
            }
        }
    }

    private fun openUrl(value: String) {
        if (!isHttpUrl(value)) return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, value.toUri())) }
    }

    private fun AnimatedContentTransitionScope<*>.slide(direction: AnimatedContentTransitionScope.SlideDirection) =
        slideIntoContainer(direction, tween(NAVIGATION_TRANSITION_DURATION, easing = FastOutSlowInEasing))

    private fun AnimatedContentTransitionScope<*>.slideOut(direction: AnimatedContentTransitionScope.SlideDirection) =
        slideOutOfContainer(direction, tween(NAVIGATION_TRANSITION_DURATION, easing = FastOutSlowInEasing))

    private companion object {
        const val NAVIGATION_TRANSITION_DURATION = 150
        var installPermissionPrompted = false
    }
}
