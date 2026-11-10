package com.github.jvsena42.loopky.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import kotlinx.coroutines.Job
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

    /** Ends the listen currently running, if any. Read and written on the main thread only. */
    private var finishCurrent: (() -> Unit)? = null

    override fun isAvailable(): Boolean = AndroidSpeech.isRecognitionAvailable(context)

    override fun finish() {
        finishCurrent?.invoke()
    }

    @Suppress("LongMethod")
    override fun listen(languageTag: String?, window: ListenWindow): Flow<SpeechEvent> = callbackFlow {
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
        // What the engine had heard so far. A stop that ends in an error or in nothing at all still
        // grades these words, or a sentence cut short by Done or the cap would be thrown away.
        var heard: String? = null
        var stopping: Job? = null

        fun deliver(text: String?, otherwise: SpeechError) {
            delivered = true
            trySend(if (text != null) SpeechEvent.Result(text) else SpeechEvent.Error(otherwise))
            close()
        }

        @Suppress("EmptyFunctionBlock")
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(SpeechEvent.Ready) }
            override fun onBeginningOfSpeech() { trySend(SpeechEvent.BeginningOfSpeech) }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                firstTranscript(partialResults)?.let {
                    heard = it
                    trySend(SpeechEvent.Partial(it))
                }
            }

            override fun onResults(results: Bundle?) {
                if (delivered) return
                deliver(firstTranscript(results) ?: heard, SpeechError.NoMatch)
            }

            override fun onError(error: Int) {
                if (delivered) return
                val reason = mapError(error)
                deliver(heard.takeIf { reason == SpeechError.NoMatch || stopping != null }, reason)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        recognizer.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Engines may ignore these, which is why the sheet also has a Done button.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, window.pauseMillis)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, window.pauseMillis)
            languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        recognizer.startListening(intent)

        // Asks the engine to finalise what it has, then reports what was heard rather than waiting
        // forever: some engines accept a listen and never finish it, not even after stopListening.
        fun stop(otherwise: SpeechError) {
            if (delivered || stopping != null) return
            recognizer.stopListening()
            stopping = launch {
                delay(FINALIZE_GRACE)
                if (!delivered) deliver(heard, otherwise)
            }
        }

        val finish = { stop(SpeechError.NoMatch) }
        finishCurrent = finish

        // The cap also ends a listen the engine never finishes (an emulator's stub recognition
        // service does exactly this), which would otherwise sit on "Say the word" for good.
        launch {
            delay(window.capMillis)
            stop(SpeechError.Unknown)
        }

        awaitClose {
            if (finishCurrent === finish) finishCurrent = null
            recognizer.stopListening()
            recognizer.destroy()
        }
    }

    private companion object {
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
