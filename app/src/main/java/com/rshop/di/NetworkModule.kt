package com.rshop.di

import android.content.Context
import com.rshop.BuildConfig
import com.rshop.data.source.DriveSettings
import com.rshop.scraper.drive.DriveAuthInterceptor
import com.rshop.scraper.http.MemoryCookieJar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context, driveSettings: DriveSettings): OkHttpClient =
        OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "http_cache"), 20L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Session cookies set by download pages are sent back, as a browser would.
            .cookieJar(MemoryCookieJar())
            // The user's Drive API key, added to Drive API requests only.
            .addInterceptor(DriveAuthInterceptor(driveSettings::credentials, onTokenRejected = driveSettings::rejectToken))
            .addNetworkInterceptor { chain ->
                // A User-Agent set by the caller (downloads started from the in-app browser) is kept.
                val request = chain.request()
                if (request.header("User-Agent") != null) {
                    chain.proceed(request)
                } else {
                    chain.proceed(request.newBuilder().header("User-Agent", "RShop/${BuildConfig.VERSION_NAME} (Android)").build())
                }
            }
            .build()
}
