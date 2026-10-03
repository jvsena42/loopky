package com.github.jvsena42.loopky.platform

import android.content.Context
import android.speech.tts.TextToSpeech
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

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        val queued = pending
        pending = null
        if (ready && queued != null) speak(queued.text, queued.languageTag, queued.rate)
    }

    override fun speak(text: String, languageTag: String, rate: Float): SpeakOutcome {
        if (text.isBlank()) return SpeakOutcome.Spoken
        if (!ready) {
            // Queued, not failed: reporting a problem here would toast at the user over a race
            // that resolves itself a moment later.
            pending = Utterance(text, languageTag, rate)
            return SpeakOutcome.Spoken
        }

        val locale = Locale.forLanguageTag(languageTag)
        return when (tts.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED ->
                SpeakOutcome.LanguageUnavailable

            TextToSpeech.ERROR -> SpeakOutcome.EngineUnavailable

            else -> {
                tts.setSpeechRate(rate)
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
                SpeakOutcome.Spoken
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

    private data class Utterance(val text: String, val languageTag: String, val rate: Float)

    private companion object {
        const val UTTERANCE_ID = "loopky-speak"
    }
}
