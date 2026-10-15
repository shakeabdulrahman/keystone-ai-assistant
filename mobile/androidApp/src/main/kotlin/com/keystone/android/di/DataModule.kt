package com.keystone.android.di

import android.content.Context
import com.keystone.shared.data.local.ConversationDao
import com.keystone.shared.data.local.KeystoneDatabase
import com.keystone.shared.data.local.MessageDao
import com.keystone.shared.data.local.buildKeystoneDatabase
import com.keystone.shared.data.local.keystoneDatabaseBuilder
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.repository.ConversationRepositoryImpl
import com.keystone.shared.domain.repository.ConversationRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KeystoneDatabase =
        buildKeystoneDatabase(keystoneDatabaseBuilder(context))

    @Provides
    fun provideConversationDao(db: KeystoneDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMessageDao(db: KeystoneDatabase): MessageDao = db.messageDao()

    @Provides
    @Singleton
    fun provideConversationRepository(
        api: KeystoneApi,
        conversationDao: ConversationDao,
        messageDao: MessageDao,
    ): ConversationRepository = ConversationRepositoryImpl(api, conversationDao, messageDao)
}
