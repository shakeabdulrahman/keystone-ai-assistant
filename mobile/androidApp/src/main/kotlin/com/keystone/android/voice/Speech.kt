package com.keystone.android.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Speech to text. ViewModels depend on this interface; tests use a fake. */
interface SpeechInput {
    /** False when the device has no speech recognition service (some emulators, de-Googled phones). */
    val isAvailable: Boolean

    /**
     * Listens until the person stops talking (or [stop] is called), emitting partial
     * transcripts as they are recognised and exactly one [SpeechEvent.Final] or
     * [SpeechEvent.Error]. Cold: collecting starts the microphone, cancelling stops it.
     */
    fun listen(): Flow<SpeechEvent>

    /** "I'm done talking": finish now and deliver the final transcript. */
    fun stop()
}

sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Error(val reason: SpeechError) : SpeechEvent
}

enum class SpeechError { NoMatch, NoPermission, Network, Busy, Unavailable, Other }

/** Text to speech. */
interface SpeechOutput {
    /** True while anything is being spoken or queued. */
    val speaking: StateFlow<Boolean>

    /**
     * Whether answers may be read aloud automatically. False while a screen reader
     * (TalkBack) is on: it already speaks, and two voices at once are unusable.
     */
    val autoSpeakAllowed: Boolean

    /** Queues [text] after anything already being spoken. */
    fun speak(text: String)

    /** Stops immediately and drops the queue. */
    fun stop()
}
