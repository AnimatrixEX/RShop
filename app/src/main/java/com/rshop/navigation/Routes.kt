package com.rshop.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

/** Optional filters let Home open the store pre-filtered on a category or platform. */
@Serializable
data class StoreRoute(val genre: String? = null, val platform: String? = null)

@Serializable
data object FavoritesRoute

@Serializable
data object LibraryRoute

@Serializable
data object DownloadsRoute

@Serializable
data object SettingsRoute

@Serializable
data class GameDetailsRoute(val gameId: String)

@Serializable
data object SourceSetupRoute

/** In-app browser on a game's download page; the file the user clicks is downloaded for [gameId]. */
@Serializable
data class BrowserRoute(val gameId: String, val url: String)
