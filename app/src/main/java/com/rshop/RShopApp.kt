package com.rshop

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.rshop.data.artwork.ArtworkResolver
import com.rshop.data.artwork.ArtworkScheduler
import com.rshop.data.repository.LibraryRepository
import com.rshop.data.sync.DownloadCountScheduler
import com.rshop.data.demo.DemoCatalogSeeder
import com.rshop.data.source.SourceRepository
import com.rshop.data.sync.SyncScheduler
import com.rshop.data.work.AppNotifications
import com.rshop.download.DownloadManager
import com.rshop.di.ApplicationScope
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class RShopApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var sourceRepository: SourceRepository

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var notifications: AppNotifications

    @Inject
    lateinit var downloadManager: DownloadManager

    @Inject
    lateinit var okHttpClient: Lazy<OkHttpClient>

    @Inject
    lateinit var demoCatalogSeeder: DemoCatalogSeeder

    @Inject
    lateinit var artworkScheduler: ArtworkScheduler

    @Inject
    lateinit var artworkResolver: ArtworkResolver

    @Inject
    lateinit var libraryRepository: LibraryRepository

    @Inject
    lateinit var downloadCountScheduler: DownloadCountScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        notifications.createChannels()
        // The UI shows the local database at once and refreshes itself when these complete.
        applicationScope.launch {
            downloadManager.reconcile()
            artworkResolver.redoIfMatcherChanged()
            artworkScheduler.schedule()
            // Games already in the games folder are found once the catalogue is there to name them.
            libraryRepository.scanInstalledIfDue()
            if (sourceRepository.all().isEmpty()) {
                demoCatalogSeeder.seedIfEmpty()
            } else {
                // A sync starts the counter lookup itself when it ends; otherwise do it now.
                // Nothing starts while the user has paused synchronisation.
                if (!syncScheduler.isPaused() && !syncScheduler.syncIfStale()) downloadCountScheduler.schedule()
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.WARN)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                // Coil keeps its own disk cache, so the shared client's HTTP cache is bypassed for images.
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient.get().newBuilder().cache(null).build() }))
            }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.20).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
