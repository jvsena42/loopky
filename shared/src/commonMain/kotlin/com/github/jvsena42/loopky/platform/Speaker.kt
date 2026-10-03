package com.github.jvsena42.loopky.platform

/** What came of a [Speaker.speak] call. */
enum class SpeakOutcome {
    Spoken,

    /**
     * The engine has no voice for the requested language. Worth surfacing rather than swallowing:
     * the alternative is reading the text in whatever voice was loaded last, which sounds like
     * the app working and teaches the learner the wrong pronunciation.
     */
    LanguageUnavailable,

    /** No usable TTS engine — none installed, or it failed to initialize. */
    EngineUnavailable,
}

/**
 * Native text-to-speech for the study session's "Listen" button. Platform-provided via Koin (like
 * `PubkyClient`) because a TTS engine needs platform context/lifecycle — Android `TextToSpeech`,
 * iOS `AVSpeechSynthesizer`. Consumed by the UI layer when a `Speak` effect is collected.
 */
interface Speaker {

    /**
     * Read [text] aloud in [languageTag] (BCP-47, e.g. `"es-ES"`), interrupting anything already
     * being spoken. [rate] multiplies the engine's normal speed (`1f`), so `0.5f` is half as fast.
     *
     * The language is required, not optional: an engine given none falls back to the *reader's*
     * device locale, so a Spanish card on an English phone gets English phonetics. A deck that
     * has not declared its pair does not offer Listen at all — see `Deck.speechReady` — so by the
     * time a call gets here there is always a tag to pass.
     */
    fun speak(text: String, languageTag: String, rate: Float = 1f): SpeakOutcome

    /**
     * BCP-47 tags the installed engine can actually voice, for the deck language picker. Empty
     * while the engine is still initializing or when there is none, in which case the picker
     * falls back to [com.github.jvsena42.loopky.domain.model.SpeechLanguages.COMMON].
     */
    fun availableLanguages(): List<String>
}
