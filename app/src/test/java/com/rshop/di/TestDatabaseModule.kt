package com.rshop.di

import com.rshop.data.database.AppDatabase
import com.rshop.data.database.dao.DownloadDao
import com.rshop.data.database.dao.FavoriteDao
import com.rshop.data.database.dao.GameDao
import com.rshop.data.database.dao.GameListDao
import com.rshop.data.database.dao.HistoryDao
import com.rshop.data.database.dao.InstalledGameDao
import com.rshop.testing.inMemoryDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Hilt tests get a fresh in-memory database per test instead of the on-disk one. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DatabaseModule::class])
object TestDatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(): AppDatabase = inMemoryDatabase()

    @Provides
    fun provideGameDao(db: AppDatabase): GameDao = db.gameDao()

    @Provides
    fun provideFavoriteDao(db: AppDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideHistoryDao(db: AppDatabase): HistoryDao = db.historyDao()

    @Provides
    fun provideInstalledGameDao(db: AppDatabase): InstalledGameDao = db.installedGameDao()

    @Provides
    fun provideDownloadDao(db: AppDatabase): DownloadDao = db.downloadDao()

    @Provides
    fun provideGameListDao(db: AppDatabase): GameListDao = db.gameListDao()
}
