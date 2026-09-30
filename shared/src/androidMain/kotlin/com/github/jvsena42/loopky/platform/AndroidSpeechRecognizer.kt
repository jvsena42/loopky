package com.github.jvsena42.loopky.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import android.speech.SpeechRecognizer as AndroidSpeech

/**
 * [SpeechRecognizer] backed by Android [android.speech.SpeechRecognizer]. The recognizer must be
 * created and used on the main thread, so [listen] hops to the main looper via [callbackFlow]'s
 * collector context expectations — callers collect from a main-dispatched coroutine. Microphone
 * permission must already be granted.
 */
class AndroidSpeechRecognizer(private val context: Context) : SpeechRecognizer {

    override fun isAvailable(): Boolean = AndroidSpeech.isRecognitionAvailable(context)

    override fun listen(languageTag: String?): Flow<SpeechEvent> = callbackFlow {
        if (!AndroidSpeech.isRecognitionAvailable(context)) {
            trySend(SpeechEvent.Error(SpeechError.Unavailable))
            close()
            return@callbackFlow
        }

        val recognizer = AndroidSpeech.createSpeechRecognizer(context)

        // The framework happily follows a final result with an error (and vice versa) on some
        // engines; whichever lands first is the attempt's outcome, and the other is dropped rather
        // than overwriting a graded result with "didn't catch that".
        var delivered = false

        @Suppress("EmptyFunctionBlock")
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(SpeechEvent.Ready) }
            override fun onBeginningOfSpeech() { trySend(SpeechEvent.BeginningOfSpeech) }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                firstTranscript(partialResults)?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                if (delivered) return
                delivered = true
                val text = firstTranscript(results)
                if (text != null) trySend(SpeechEvent.Result(text)) else trySend(SpeechEvent.Error(SpeechError.NoMatch))
                close()
            }

            override fun onError(error: Int) {
                if (delivered) return
                delivered = true
                trySend(SpeechEvent.Error(mapError(error)))
                close()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        recognizer.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        recognizer.startListening(intent)

        // Some engines accept a listen and then never finish it — no result, no error, not even
        // after the speech ends (an emulator's stub recognition service does exactly this). Nothing
        // else ends the flow, so the sheet sits on "Say the word" for good. A pronunciation attempt
        // is one word or a short phrase, so a cap this far out cannot truncate a real one: it asks
        // the engine to finalise what it has, then reports rather than waiting forever.
        launch {
            delay(LISTEN_TIMEOUT)
            if (delivered) return@launch
            recognizer.stopListening()
            delay(FINALIZE_GRACE)
            if (delivered) return@launch
            delivered = true
            trySend(SpeechEvent.Error(SpeechError.Unknown))
            close()
        }

        awaitClose {
            recognizer.stopListening()
            recognizer.destroy()
        }
    }

    private companion object {
        /** Long enough that no single-word attempt can be cut off; short enough to end a hang. */
        val LISTEN_TIMEOUT = 15.seconds

        /** [android.speech.SpeechRecognizer.stopListening] may still deliver — give it the chance. */
        val FINALIZE_GRACE = 3.seconds
    }

    private fun firstTranscript(bundle: Bundle?): String? =
        bundle?.getStringArrayList(AndroidSpeech.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Every code the framework can raise maps to something the sheet can say. The API 33 constants
     * are compile-time ints, so naming them costs nothing on an older device — the codes simply
     * never arrive there.
     */
    private fun mapError(code: Int): SpeechError = when (code) {
        AndroidSpeech.ERROR_NO_MATCH, AndroidSpeech.ERROR_SPEECH_TIMEOUT -> SpeechError.NoMatch
        AndroidSpeech.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.Permission
        AndroidSpeech.ERROR_NETWORK,
        AndroidSpeech.ERROR_NETWORK_TIMEOUT,
        AndroidSpeech.ERROR_SERVER,
        AndroidSpeech.ERROR_SERVER_DISCONNECTED,
        -> SpeechError.Network
        AndroidSpeech.ERROR_RECOGNIZER_BUSY, AndroidSpeech.ERROR_TOO_MANY_REQUESTS -> SpeechError.Busy
        // A deck declares its language, so this is the one failure the reader can actually fix.
        AndroidSpeech.ERROR_LANGUAGE_NOT_SUPPORTED,
        AndroidSpeech.ERROR_LANGUAGE_UNAVAILABLE,
        -> SpeechError.LanguageUnavailable
        AndroidSpeech.ERROR_CANNOT_CHECK_SUPPORT -> SpeechError.Unavailable
        else -> SpeechError.Unknown
    }
}
