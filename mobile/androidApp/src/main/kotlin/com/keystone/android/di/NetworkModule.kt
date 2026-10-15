package com.keystone.android.di

import com.keystone.android.BuildConfig
import com.keystone.shared.auth.AuthTokenProvider
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.createHttpClient
import com.keystone.shared.data.repository.AccountRepositoryImpl
import com.keystone.shared.data.repository.ChatRepositoryImpl
import com.keystone.shared.data.repository.SystemRepositoryImpl
import com.keystone.shared.domain.repository.AccountRepository
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
    fun provideHttpClient(tokens: AuthTokenProvider): HttpClient = createHttpClient(tokens = tokens)

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

    @Provides
    @Singleton
    fun provideAccountRepository(api: KeystoneApi): AccountRepository = AccountRepositoryImpl(api)

    /** Injected so tests can drive time with the coroutine test scheduler. */
    @Provides
    fun provideTimeSource(): TimeSource = TimeSource.Monotonic
}
