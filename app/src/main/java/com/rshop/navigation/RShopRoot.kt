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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.rshop.ui.components.ConsoleTopBar
import com.rshop.ui.components.ControllerHints
import com.rshop.ui.components.ControllerHintsBar
import com.rshop.ui.components.LocalSearchSignal
import com.rshop.ui.components.SearchSignal
import kotlinx.coroutines.flow.emptyFlow
import com.rshop.ui.theme.RShopColors
import kotlinx.coroutines.flow.Flow

@Composable
fun RShopRoot(tabSwitches: Flow<Int>, searchRequests: Flow<Unit> = emptyFlow()) {
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

    val searchSignal = remember { SearchSignal() }
    LaunchedEffect(searchRequests) {
        searchRequests.collect {
            // Only from the tabs: on a game page X is not a search shortcut.
            if (navController.currentBackStackEntry?.destination.toTopLevelDestination() != null) {
                navController.navigateToTab(TopLevelDestination.Store)
                searchSignal.pending = true
            }
        }
    }
    val destination = backStackEntry?.destination
    val hints = when {
        currentTab == TopLevelDestination.Home || currentTab == TopLevelDestination.Store ||
            currentTab == TopLevelDestination.Favorites -> ControllerHints.OnCards
        currentTab != null -> ControllerHints.OnTabs
        destination?.hasRoute(BrowserRoute::class) == true -> emptyList()
        else -> ControllerHints.OnPage
    }

    CompositionLocalProvider(LocalSearchSignal provides searchSignal) {
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
        ControllerHintsBar(hints)
    }
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
