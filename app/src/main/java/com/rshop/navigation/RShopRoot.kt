package com.rshop.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rshop.ui.components.ConsoleTopBar
import com.rshop.ui.theme.RShopColors
import kotlinx.coroutines.flow.Flow

@Composable
fun RShopRoot(tabSwitches: Flow<Int>) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = backStackEntry?.destination.toTopLevelDestination()

    LaunchedEffect(tabSwitches) {
        tabSwitches.collect { step ->
            // Read the live back stack: shoulder buttons only act on top-level screens, not on a game page.
            navController.currentBackStackEntry?.destination.toTopLevelDestination()
                ?.let { navController.navigateToTab(it.cycle(step)) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(RShopColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AnimatedVisibility(
            visible = currentTab != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            ConsoleTopBar(
                selected = currentTab,
                onSelect = { navController.navigateToTab(it) },
            )
        }
        RShopNavHost(navController = navController, modifier = Modifier.weight(1f))
    }
}

private fun NavDestination?.toTopLevelDestination(): TopLevelDestination? {
    val destination = this ?: return null
    return TopLevelDestination.entries.firstOrNull { tab ->
        destination.hierarchy.any { it.hasRoute(tab.routeClass) }
    }
}

fun NavHostController.navigateToTab(tab: TopLevelDestination) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
