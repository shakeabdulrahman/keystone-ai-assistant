package com.keystone.android.di

import com.keystone.android.BuildConfig
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.createHttpClient
import com.keystone.shared.data.repository.ChatRepositoryImpl
import com.keystone.shared.data.repository.SystemRepositoryImpl
import com.keystone.shared.domain.repository.ChatRepository
import com.keystone.shared.domain.repository.SystemRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Singleton
import kotlin.time.TimeSource

/**
 * Wires the KMP shared layer into Hilt. Shared code stays DI-framework-free
 * (plain constructors), so iOS can wire the same classes its own way.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = createHttpClient()

    @Provides
    @Singleton
    fun provideKeystoneApi(client: HttpClient): KeystoneApi =
        KeystoneApi(client, baseUrl = BuildConfig.API_BASE_URL)

    @Provides
    @Singleton
    fun provideSystemRepository(api: KeystoneApi): SystemRepository = SystemRepositoryImpl(api)

    @Provides
    @Singleton
    fun provideChatRepository(api: KeystoneApi): ChatRepository = ChatRepositoryImpl(api)

    /** Injected so tests can drive time with the coroutine test scheduler. */
    @Provides
    fun provideTimeSource(): TimeSource = TimeSource.Monotonic
}
