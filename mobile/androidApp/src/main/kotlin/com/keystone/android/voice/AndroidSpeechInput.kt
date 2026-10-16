package com.keystone.android.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's built-in speech recognition. Free, and on-device when the phone supports it
 * (Android 12+ with an offline model), so the audio never leaves the phone.
 *
 * SpeechRecognizer must be created and used on the main thread; the flow runs there.
 */
@Singleton
class AndroidSpeechInput @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechInput {

    private var active: SpeechRecognizer? = null

    /** The on-device recognizer lacked this language once: use the standard one from now on. */
    private var onDeviceUnusable = false

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(): Flow<SpeechEvent> = callbackFlow {
        val onDevice = canUseOnDevice()
        val recognizer = createRecognizer(onDevice)
        active = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechEvent.Ready)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.bestText()?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = results.bestText()
                trySend(if (text.isNullOrBlank()) SpeechEvent.Error(SpeechError.NoMatch) else SpeechEvent.Final(text))
                close()
            }

            override fun onError(error: Int) {
                if (onDevice && error in LANGUAGE_ERRORS) onDeviceUnusable = true // retry won't repeat it
                trySend(SpeechEvent.Error(error.toSpeechError()))
                close()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(recognizerIntent())
        awaitClose {
            // Runs on cancel too: the microphone is always released.
            recognizer.cancel()
            recognizer.destroy()
            if (active === recognizer) active = null
        }
    }.flowOn(Dispatchers.Main.immediate)

    override fun stop() {
        active?.stopListening()
    }

    private fun canUseOnDevice(): Boolean =
        !onDeviceUnusable &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun createRecognizer(onDevice: Boolean): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private companion object {
        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31):
        // the on-device model for this language isn't installed.
        val LANGUAGE_ERRORS = setOf(12, 13)
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // keep audio on the phone when possible
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
}

private fun Bundle?.bestText(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

private fun Int.toSpeechError(): SpeechError = when (this) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NoMatch
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.NoPermission
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_SERVER -> SpeechError.Network
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.Busy
    else -> SpeechError.Other
}
