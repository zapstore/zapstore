package dev.zapstore.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

class MainActivity : ComponentActivity() {
    private val repository: CatalogRepository
        get() = (application as ZapstoreApplication).catalogRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZapstoreTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    val navController = rememberNavController()
                    val context = LocalContext.current

                    NavHost(
                        navController = navController,
                        startDestination = HOME_ROUTE,
                    ) {
                        composable(HOME_ROUTE) {
                            val viewModel: HomeViewModel = viewModel(
                                factory = homeViewModelFactory(repository),
                            )
                            val state by viewModel.uiState.collectAsStateWithLifecycle()
                            HomeScreen(
                                state = state,
                                onSearchQueryChanged = viewModel::onSearchQueryChanged,
                                onSearchSubmitted = viewModel::submitSearch,
                                onSearchCleared = viewModel::clearSearch,
                                onStackClick = { stackId ->
                                    navController.navigate(stackRoute(stackId))
                                },
                                onAppClick = { identifier, author ->
                                    navController.navigate(appRoute(identifier, author))
                                },
                            )
                        }

                        composable(
                            route = STACK_ROUTE,
                            arguments = listOf(
                                navArgument(STACK_ID_ARGUMENT) { type = NavType.StringType },
                            ),
                        ) {
                            val viewModel: StackDetailViewModel = viewModel(
                                factory = stackDetailViewModelFactory(repository),
                            )
                            val state by viewModel.uiState.collectAsStateWithLifecycle()
                            StackDetailScreen(
                                state = state,
                                onBack = { navController.popBackStack() },
                                onAppClick = { identifier, author ->
                                    navController.navigate(appRoute(identifier, author))
                                },
                            )
                        }

                        composable(
                            route = APP_ROUTE,
                            arguments = listOf(
                                navArgument(APP_IDENTIFIER_ARGUMENT) { type = NavType.StringType },
                                navArgument(APP_AUTHOR_ARGUMENT) {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                            ),
                        ) {
                            val viewModel: AppDetailViewModel = viewModel(
                                factory = appDetailViewModelFactory(repository),
                            )
                            val state by viewModel.uiState.collectAsStateWithLifecycle()
                            AppDetailScreen(
                                state = state,
                                onBack = { navController.popBackStack() },
                                onOpenUrl = { value ->
                                    if (isHttpUrl(value)) {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, value.toUri()),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        repository.refreshConnections()
    }
}

private const val HOME_ROUTE = "home"
private const val STACK_ROUTE = "stack/{$STACK_ID_ARGUMENT}"
private const val APP_ROUTE =
    "app/{$APP_IDENTIFIER_ARGUMENT}?$APP_AUTHOR_ARGUMENT={$APP_AUTHOR_ARGUMENT}"

private fun stackRoute(stackId: String): String =
    "stack/${Uri.encode(stackId)}"

private fun appRoute(identifier: String, author: String?): String = buildString {
    append("app/").append(Uri.encode(identifier))
    author?.let {
        append("?").append(APP_AUTHOR_ARGUMENT).append("=").append(Uri.encode(it))
    }
}
