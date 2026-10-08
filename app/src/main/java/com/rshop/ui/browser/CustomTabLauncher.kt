package com.rshop.ui.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.ui.graphics.toArgb
import androidx.core.net.toUri
import com.rshop.ui.theme.RShopColors
import timber.log.Timber

/**
 * Opens a page in a Chrome Custom Tab: the device's own browser (its cookies, its User-Agent,
 * its fingerprint), shown over RShop and themed like it, with a close button that comes back.
 * It is the real browser, so the site treats it as such; RShop neither drives it nor disguises it.
 */
object CustomTabLauncher {

    /** Returns false when no browser on the device can handle a Custom Tab. */
    fun open(context: Context, url: String): Boolean {
        val uri = url.toUriOrNull() ?: return false
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(RShopColors.Surface.toArgb())
            .setNavigationBarColor(RShopColors.Background.toArgb())
            .build()
        val intent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colors)
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .build()
        return try {
            intent.launchUrl(context, uri)
            true
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No browser to open a Custom Tab")
            false
        }
    }

    private fun String.toUriOrNull(): Uri? =
        takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }?.toUri()
}
