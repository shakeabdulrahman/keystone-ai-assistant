package com.keystone.android.fakes

import com.keystone.android.voice.SpeechEvent
import com.keystone.android.voice.SpeechInput
import com.keystone.android.voice.SpeechOutput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformWhile

/** Speech input driven by the test: [hear] sends what the "microphone" picked up. */
class FakeSpeechInput(override val isAvailable: Boolean = true) : SpeechInput {
    private var channel = Channel<SpeechEvent>(Channel.UNLIMITED)
    var listens = 0
    var stops = 0

    override fun listen(): Flow<SpeechEvent> {
        listens++
        channel = Channel(Channel.UNLIMITED)
        // Like the real one: ends after the final transcript or an error.
        return channel.receiveAsFlow().transformWhile { event ->
            emit(event)
            event !is SpeechEvent.Final && event !is SpeechEvent.Error
        }
    }

    override fun stop() {
        stops++
    }

    fun hear(event: SpeechEvent) {
        channel.trySend(event)
    }
}

/** Records what would have been spoken. */
class FakeSpeechOutput(override var autoSpeakAllowed: Boolean = true) : SpeechOutput {
    val spoken = mutableListOf<String>()
    var stops = 0
    override val speaking: StateFlow<Boolean> = MutableStateFlow(false)

    override fun speak(text: String) {
        spoken += text
    }

    override fun stop() {
        stops++
    }
}
