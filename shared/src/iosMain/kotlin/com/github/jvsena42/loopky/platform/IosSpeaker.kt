package com.github.jvsena42.loopky.platform

import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate

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
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * rate
        synthesizer.speakUtterance(utterance)
        return SpeakOutcome.Spoken
    }

    override fun availableLanguages(): List<String> =
        AVSpeechSynthesisVoice.speechVoices()
            .mapNotNull { (it as? AVSpeechSynthesisVoice)?.language() }
            .distinct()
            .sorted()
}
