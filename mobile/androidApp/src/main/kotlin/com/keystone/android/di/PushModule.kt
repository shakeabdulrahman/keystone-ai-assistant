package com.keystone.android.di

import com.keystone.android.push.FirebasePushTokens
import com.keystone.android.push.PushTokens
import com.keystone.shared.data.local.CachedJsonDao
import com.keystone.shared.data.local.KeystoneDatabase
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.repository.DeviceRepositoryImpl
import com.keystone.shared.data.repository.HomeRepositoryImpl
import com.keystone.shared.domain.repository.DeviceRepository
import com.keystone.shared.domain.repository.HomeRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PushModule {
    @Binds
    abstract fun bindPushTokens(impl: FirebasePushTokens): PushTokens

    companion object {
        @Provides
        fun provideCachedJsonDao(db: KeystoneDatabase): CachedJsonDao = db.cachedJsonDao()

        @Provides
        @Singleton
        fun provideHomeRepository(api: KeystoneApi, cache: CachedJsonDao): HomeRepository =
            HomeRepositoryImpl(api, cache)

        @Provides
        @Singleton
        fun provideDeviceRepository(api: KeystoneApi): DeviceRepository = DeviceRepositoryImpl(api, platform = "android")
    }
}
