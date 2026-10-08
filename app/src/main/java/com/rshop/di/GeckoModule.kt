package com.rshop.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import javax.inject.Singleton

/**
 * The single Gecko (Firefox) engine for the whole app. It blocks ad and tracker resources
 * natively (enhanced tracking protection) and turns Safe Browsing on, so the in-app browser is
 * a real browser that is also hardened against ads and malware.
 */
@Module
@InstallIn(SingletonComponent::class)
object GeckoModule {

    @Provides
    @Singleton
    fun provideGeckoRuntime(@ApplicationContext context: Context): GeckoRuntime {
        val contentBlocking = ContentBlocking.Settings.Builder()
            // Block every known tracker/ad category Gecko ships lists for.
            .antiTracking(ContentBlocking.AntiTracking.STRICT)
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .build()
        val settings = GeckoRuntimeSettings.Builder()
            .contentBlocking(contentBlocking)
            .aboutConfigEnabled(false)
            .build()
        return GeckoRuntime.create(context, settings)
    }
}
