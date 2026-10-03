package com.github.jvsena42.loopky.platform

import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate

/**
 * iOS [Speaker], over `AVSpeechSynthesizer`.
 *
 * The study screen does not go through here — it plays the ViewModel's `Speak` effect with the
 * Swift `SpeechSpeaker`, which is why Listen worked while this was still a stub. What *does* need
 * it is [availableLanguages]: the deck editor's language picker asks the platform which voices
 * exist, and an empty list left every author choosing from `SpeechLanguages.COMMON` whether or not
 * the device could read any of it aloud.
 *
 * The voice is set explicitly rather than left to default. Without it the synthesizer uses the
 * *reader's* device language, which reads a Spanish card in an English accent — the same failure
 * the whole language-pair gate exists to prevent. A tag with no installed voice is reported as
 * [SpeakOutcome.LanguageUnavailable] rather than being read in the wrong one.
 *
 * It never calls `onDone`: nothing that speaks through it waits for the end. The study screen's
 * Listen button does, and `SpeechSpeaker` reports it from the synthesizer's delegate.
 */
class IosSpeaker : Speaker {

    private val synthesizer = AVSpeechSynthesizer()

    override fun speak(
        text: String,
        languageTag: String,
        rate: Float,
        onDone: () -> Unit,
    ): SpeakOutcome {
        val voice = AVSpeechSynthesisVoice.voiceWithLanguage(languageTag)
            ?: return SpeakOutcome.LanguageUnavailable
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        utterance.voice = voice
        utterance.rate = utteranceRate(rate)
        synthesizer.speakUtterance(utterance)
        return SpeakOutcome.Spoken
    }

    /**
     * `AVSpeechUtterance.rate` is a 0..1 scale, not a multiplier: the default is normal speed and
     * the minimum about half of it.
     */
    private fun utteranceRate(multiplier: Float): Float {
        val clamped = multiplier.coerceIn(SLOWEST_MULTIPLIER, 1f)
        val position = (clamped - SLOWEST_MULTIPLIER) / (1f - SLOWEST_MULTIPLIER)
        return AVSpeechUtteranceMinimumSpeechRate +
            (AVSpeechUtteranceDefaultSpeechRate - AVSpeechUtteranceMinimumSpeechRate) * position
    }

    override fun availableLanguages(): List<String> =
        AVSpeechSynthesisVoice.speechVoices()
            .mapNotNull { (it as? AVSpeechSynthesisVoice)?.language() }
            .distinct()
            .sorted()

    private companion object {
        const val SLOWEST_MULTIPLIER = 0.5f
    }
}
