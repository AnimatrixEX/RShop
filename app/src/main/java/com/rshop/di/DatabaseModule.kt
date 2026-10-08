package com.rshop.di

import android.content.Context
import androidx.room.Room
import com.rshop.data.database.AppDatabase
import com.rshop.data.database.dao.DownloadDao
import com.rshop.data.database.dao.FavoriteDao
import com.rshop.data.database.dao.GameDao
import com.rshop.data.database.dao.GameListDao
import com.rshop.data.database.dao.HistoryDao
import com.rshop.data.database.dao.InstalledGameDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

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
