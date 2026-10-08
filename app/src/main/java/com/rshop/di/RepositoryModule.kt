package com.rshop.di

import com.rshop.data.preferences.DataStoreSettingsRepository
import com.rshop.data.repository.RoomGameRepository
import com.rshop.domain.repository.GameRepository
import com.rshop.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindGameRepository(impl: RoomGameRepository): GameRepository

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}
