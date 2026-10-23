package dev.zapstore.app

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.zapstore.app.screens.Routes

/**
 * Android TV only: Home, Updates, and Settings on a rail at the left edge. On a phone the
 * Home header buttons reach them; with a remote, a rail one Left press away is easier to
 * find and keeps the header to the search field.
 */
@Composable
fun TvNavigationRail(navController: NavHostController, updateCount: Int, modifier: Modifier = Modifier) {
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    NavigationRail(
        modifier = modifier.testTag("tvRail"),
        containerColor = ZapCanvas,
    ) {
        Tab(
            selected = route != Routes.UPDATES && route != Routes.SETTINGS,
            icon = R.drawable.ic_home,
            label = stringResource(R.string.home),
            tag = "railHome",
        ) { navController.navigateTab(Routes.HOME) }
        Tab(
            selected = route == Routes.UPDATES,
            icon = R.drawable.ic_update,
            label = stringResource(R.string.updates),
            tag = "railUpdates",
            badge = updateCount,
        ) { navController.navigateTab(Routes.UPDATES) }
        Tab(
            selected = route == Routes.SETTINGS,
            icon = R.drawable.ic_more,
            label = stringResource(R.string.settings),
            tag = "railSettings",
        ) { navController.navigateTab(Routes.SETTINGS) }
    }
}

@Composable
private fun Tab(
    selected: Boolean,
    icon: Int,
    label: String,
    tag: String,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = {
            BadgedBox(badge = { if (badge > 0) Badge { Text(badge.toString()) } }) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            }
        },
        label = { Text(label) },
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = ZapTextPrimary,
            selectedTextColor = ZapTextPrimary,
            indicatorColor = ZapSurface3,
            unselectedIconColor = ZapTextTertiary,
            unselectedTextColor = ZapTextTertiary,
        ),
        modifier = Modifier.testTag(tag),
    )
}

/** Switches tab: one copy of each tab on the stack above Home, its state kept. */
private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
