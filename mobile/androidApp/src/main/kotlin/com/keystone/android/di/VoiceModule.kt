package com.keystone.android.di

import com.keystone.android.voice.AndroidSpeechInput
import com.keystone.android.voice.AndroidSpeechOutput
import com.keystone.android.voice.SpeechInput
import com.keystone.android.voice.SpeechOutput
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {
    @Binds
    abstract fun bindSpeechInput(impl: AndroidSpeechInput): SpeechInput

    @Binds
    abstract fun bindSpeechOutput(impl: AndroidSpeechOutput): SpeechOutput
}
