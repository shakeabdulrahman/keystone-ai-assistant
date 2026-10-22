package com.keystone.android.di

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.keystone.android.auth.AuthService
import com.keystone.android.auth.FirebaseAppCheckTokenProvider
import com.keystone.android.auth.FirebaseAuthService
import com.keystone.android.auth.FirebaseTokenProvider
import com.keystone.shared.auth.AppCheckTokenProvider
import com.keystone.shared.auth.AuthTokenProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    @Binds
    abstract fun bindAuthService(impl: FirebaseAuthService): AuthService

    @Binds
    abstract fun bindTokenProvider(impl: FirebaseTokenProvider): AuthTokenProvider

    @Binds
    abstract fun bindAppCheckProvider(impl: FirebaseAppCheckTokenProvider): AppCheckTokenProvider

    companion object {
        @Provides
        @Singleton
        fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

        @Provides
        @Singleton
        fun provideFirebaseAppCheck(): FirebaseAppCheck = FirebaseAppCheck.getInstance()
    }
}
