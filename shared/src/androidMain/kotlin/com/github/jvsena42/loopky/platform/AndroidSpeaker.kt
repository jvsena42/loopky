package com.github.jvsena42.loopky.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * [Speaker] backed by Android [TextToSpeech].
 *
 * Two things this has to do that the naive version does not. The locale is set **per utterance**
 * rather than once at init — a deck declares the language of each side, and the front and back of
 * the same card are routinely different ones. And [TextToSpeech.setLanguage]'s return code is
 * checked: on `LANG_MISSING_DATA`/`LANG_NOT_SUPPORTED` the engine keeps whatever voice was loaded
 * last, so ignoring it means happily reading Spanish in an English accent.
 */
class AndroidSpeaker(context: Context) : Speaker {

    private var ready = false

    /**
     * The engine initializes asynchronously, so a Listen tap in the first moments after launch
     * arrives before it is usable. Holding that one utterance and replaying it on init beats
     * dropping it, which looks to the user like a dead button.
     */
    private var pending: Utterance? = null

    /**
     * The utterance whose end is still owed to its caller. Each call gets a fresh id, so the stop
     * a `QUEUE_FLUSH` sends the one it interrupts is not mistaken for the new one finishing.
     */
    private var current: Pair<String, () -> Unit>? = null
    private var nextId = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        val queued = pending
        pending = null
        if (queued == null) return@TextToSpeech
        val outcome = if (ready) {
            speak(queued.text, queued.languageTag, queued.rate, queued.onDone)
        } else {
            SpeakOutcome.EngineUnavailable
        }
        if (outcome != SpeakOutcome.Spoken) queued.onDone()
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
        })
    }

    @Synchronized
    private fun finish(utteranceId: String?) {
        val (id, onDone) = current ?: return
        if (id != utteranceId) return
        current = null
        onDone()
    }

    override fun speak(text: String, languageTag: String, rate: Float, onDone: () -> Unit): SpeakOutcome {
        if (text.isBlank()) {
            onDone()
            return SpeakOutcome.Spoken
        }
        if (!ready) {
            // Queued, not failed: reporting a problem here would toast at the user over a race
            // that resolves itself a moment later.
            pending = Utterance(text, languageTag, rate, onDone)
            return SpeakOutcome.Spoken
        }

        val locale = Locale.forLanguageTag(languageTag)
        return when (tts.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED ->
                SpeakOutcome.LanguageUnavailable

            TextToSpeech.ERROR -> SpeakOutcome.EngineUnavailable

            else -> {
                val id = "$UTTERANCE_ID-${nextId++}"
                synchronized(this) { current = id to onDone }
                tts.setSpeechRate(rate)
                if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) {
                    synchronized(this) { current = null }
                    SpeakOutcome.EngineUnavailable
                } else {
                    SpeakOutcome.Spoken
                }
            }
        }
    }

    override fun availableLanguages(): List<String> {
        if (!ready) return emptyList()
        return runCatching { tts.availableLanguages }
            .getOrNull()
            .orEmpty()
            .map { it.toLanguageTag() }
            .sorted()
    }

    private data class Utterance(
        val text: String,
        val languageTag: String,
        val rate: Float,
        val onDone: () -> Unit,
    )

    private companion object {
        const val UTTERANCE_ID = "loopky-speak"
    }
}
