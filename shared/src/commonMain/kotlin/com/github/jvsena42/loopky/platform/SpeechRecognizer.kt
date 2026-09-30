package com.github.jvsena42.loopky.platform

import kotlinx.coroutines.flow.Flow

/** Events emitted while listening for speech. */
sealed interface SpeechEvent {
    data object Ready : SpeechEvent
    data object BeginningOfSpeech : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Result(val text: String) : SpeechEvent
    data class Error(val reason: SpeechError) : SpeechEvent
}

/**
 * Why a listen produced no answer. Every value is *reported*, never swallowed: a recognition that
 * ends with nothing on screen is indistinguishable from the app having missed the tap.
 *
 * Entries are PascalCase, like every other enum that crosses to Swift here — Kotlin exports them
 * lowercased with the separators dropped (`SpeechError.languageunavailable`), so a SCREAMING_SNAKE
 * entry would cross under a name nothing in this repo can predict.
 */
enum class SpeechError {
    /** Speech was captured but matched nothing, or none was heard before the engine gave up. */
    NoMatch,
    Permission,
    Network,

    /** Another recognition is still running — the engine refuses a second one. */
    Busy,

    /** The device has no recognition service at all. */
    Unavailable,

    /** Recognition exists, but not for the deck's declared language. */
    LanguageUnavailable,
    Unknown,
}

/**
 * On-device speech recognition for the Speak pronunciation practice (study). Implemented per
 * platform (Android [android.speech.SpeechRecognizer] / iOS Speech framework). The caller must
 * hold microphone permission before collecting [listen]; cancelling the collector stops it.
 */
interface SpeechRecognizer {
    /** Whether recognition is available on this device. */
    fun isAvailable(): Boolean

    /** Start listening, emitting [SpeechEvent]s until the flow is cancelled or completes. */
    fun listen(languageTag: String? = null): Flow<SpeechEvent>
}
