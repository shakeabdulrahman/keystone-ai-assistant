package com.keystone.android.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.accessibility.AccessibilityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's built-in text to speech (free, on-device voices).
 *
 * The engine starts asynchronously, so sentences requested before it's ready are kept
 * and spoken once it is. `speaking` counts queued utterances, so it stays true across
 * the short gaps between streamed sentences.
 */
@Singleton
class AndroidSpeechOutput @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechOutput {

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val outstanding = AtomicInteger(0)
    private val nextId = AtomicInteger(0)
    private val waiting = mutableListOf<String>() // before the engine is ready (main thread only)
    private var ready = false
    private var failed = false

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale.getDefault())
            tts.setOnUtteranceProgressListener(progress)
            ready = true
            waiting.forEach(::enqueue)
        } else {
            failed = true
        }
        waiting.clear()
    }

    override val autoSpeakAllowed: Boolean
        get() {
            val accessibility = context.getSystemService(AccessibilityManager::class.java)
            return accessibility?.isTouchExplorationEnabled != true
        }

    override fun speak(text: String) {
        if (text.isBlank() || failed) return
        if (ready) enqueue(text) else waiting += text
    }

    override fun stop() {
        waiting.clear()
        outstanding.set(0)
        _speaking.value = false
        if (ready) tts.stop()
    }

    private fun enqueue(text: String) {
        outstanding.incrementAndGet()
        _speaking.value = true
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, "keystone-${nextId.incrementAndGet()}")
        if (result != TextToSpeech.SUCCESS) finishedOne()
    }

    private fun finishedOne() {
        if (outstanding.decrementAndGet() <= 0) {
            outstanding.set(0)
            _speaking.value = false
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) = finishedOne()

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = finishedOne()

        override fun onError(utteranceId: String?, errorCode: Int) = finishedOne()

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // stop() already reset the state.
        }
    }
}
