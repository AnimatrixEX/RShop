package com.rshop.navigation

import androidx.annotation.StringRes
import com.rshop.R
import kotlin.reflect.KClass

/** The tabs of the top bar, in display order. L1/R1 cycle through them. */
enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    @StringRes val labelRes: Int,
) {
    Home(HomeRoute, HomeRoute::class, R.string.tab_home),
    Store(StoreRoute(), StoreRoute::class, R.string.tab_store),
    Favorites(FavoritesRoute, FavoritesRoute::class, R.string.tab_favorites),
    Library(LibraryRoute, LibraryRoute::class, R.string.tab_library),
    Downloads(DownloadsRoute, DownloadsRoute::class, R.string.tab_downloads),
    Settings(SettingsRoute, SettingsRoute::class, R.string.tab_settings),
    ;

    /** Wraps around at both ends, like console stores do. */
    fun cycle(step: Int): TopLevelDestination = entries[Math.floorMod(ordinal + step, entries.size)]
}
