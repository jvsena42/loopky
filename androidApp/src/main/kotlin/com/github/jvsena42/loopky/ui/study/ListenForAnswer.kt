package com.github.jvsena42.loopky.ui.study

import com.github.jvsena42.loopky.platform.SpeechError
import com.github.jvsena42.loopky.platform.SpeechEvent
import com.github.jvsena42.loopky.platform.SpeechRecognizer
import com.github.jvsena42.loopky.presentation.study.StudySessionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin

/**
 * Joins [previous], not just cancels it: the previous recogniser is destroyed in the flow's
 * awaitClose, and creating a second one before that lands is answered with ERROR_RECOGNIZER_BUSY —
 * which is what a fast Try again is.
 */
internal suspend fun listenForAnswer(
    previous: Job?,
    speechRecognizer: SpeechRecognizer,
    languageTag: String,
    viewModel: StudySessionViewModel,
) {
    previous?.cancelAndJoin()
    if (!speechRecognizer.isAvailable()) {
        viewModel.onSpeechError(SpeechError.Unavailable)
        return
    }
    speechRecognizer.listen(languageTag).collect { event ->
        when (event) {
            is SpeechEvent.Result -> viewModel.onSpeechResult(event.text)
            is SpeechEvent.Error -> viewModel.onSpeechError(event.reason)
            else -> Unit
        }
    }
}
