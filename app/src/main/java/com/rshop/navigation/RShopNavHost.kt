package com.rshop.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.rshop.ui.components.InstalledIdsViewModel
import com.rshop.ui.components.LocalInstalledIds
import com.rshop.ui.details.GameDetailsScreen
import com.rshop.ui.downloads.DownloadsScreen
import com.rshop.ui.favorites.FavoritesScreen
import com.rshop.ui.home.HomeScreen
import com.rshop.ui.library.LibraryScreen
import com.rshop.ui.settings.SettingsScreen
import com.rshop.ui.source.SourceSetupScreen
import com.rshop.ui.store.StoreScreen
import com.rshop.ui.browser.BrowserScreen

@Composable
fun RShopNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    val openGame: (String) -> Unit = { id -> navController.navigate(GameDetailsRoute(id)) }
    val installedIds by hiltViewModel<InstalledIdsViewModel>().ids.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalInstalledIds provides installedIds) {
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = modifier,
            enterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.98f) },
            exitTransition = { fadeOut(tween(150)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.98f) },
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    onOpenGame = openGame,
                    onOpenGenre = { genre -> navController.navigateToFilteredStore(StoreRoute(genre = genre)) },
                    onOpenPlatform = { platform -> navController.navigateToFilteredStore(StoreRoute(platform = platform)) },
                )
            }
            composable<StoreRoute> { StoreScreen(onOpenGame = openGame) }
            composable<FavoritesRoute> {
                FavoritesScreen(
                    onOpenGame = openGame,
                    onBrowseStore = { navController.navigateToTab(TopLevelDestination.Store) },
                )
            }
            composable<LibraryRoute> {
                LibraryScreen(
                    onBrowseStore = { navController.navigateToTab(TopLevelDestination.Store) },
                    onOpenGame = openGame,
                    onOpenBrowser = { gameId, url -> navController.navigate(BrowserRoute(gameId, url)) },
                )
            }
            composable<DownloadsRoute> { DownloadsScreen() }
            composable<SettingsRoute> { SettingsScreen(onOpenSourceSetup = { navController.navigate(SourceSetupRoute) }) }
            composable<SourceSetupRoute> { SourceSetupScreen(onBack = { navController.popBackStack() }) }
            composable<GameDetailsRoute> {
                GameDetailsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenBrowser = { gameId, url -> navController.navigate(BrowserRoute(gameId, url)) },
                )
            }
            composable<BrowserRoute> {
                BrowserScreen(onClose = { navController.popBackStack() })
            }
        }
    }
}

/** Opens the store with fresh filters instead of restoring the previous store state. */
private fun NavHostController.navigateToFilteredStore(route: StoreRoute) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
    }
}
