package com.github.jvsena42.loopky.platform

import com.github.jvsena42.loopky.presentation.study.StudySessionEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListenWindowTest {

    @Test
    fun shortAnswersKeepTheOriginalWindow() {
        val original = ListenWindow(pauseMillis = 1_500, capMillis = 15_000)
        assertEquals(original, ListenWindow.forAnswer("hola"))
        assertEquals(original, ListenWindow.forAnswer("buenos días"))
        assertEquals(original, ListenWindow.forAnswer("¿qué tal?"))
    }

    @Test
    fun aLongSentenceGetsAThreeSecondPauseAndAMinute() {
        val sentence = "Cuando llegué a la estación el tren ya se había ido, así que tuve que " +
            "esperar casi una hora sentado en un banco frío"
        assertEquals(ListenWindow(pauseMillis = 3_000, capMillis = 60_000), ListenWindow.forAnswer(sentence))
    }

    @Test
    fun theWindowGrowsWithTheAnswer() {
        val six = ListenWindow.forAnswer("¿Dónde está la estación de tren?")
        assertTrue(six.pauseMillis in 1_501..2_999, "pause ${six.pauseMillis}")
        assertTrue(six.capMillis in 15_001..59_999, "cap ${six.capMillis}")
    }

    @Test
    fun textWithoutSpacesIsMeasuredByLength() {
        // Japanese has no spaces, so a whole sentence would otherwise count as one word.
        val window = ListenWindow.forAnswer("昨日は雨が降っていたので一日中家で本を読んでいました")
        assertTrue(window.pauseMillis > 1_500, "pause ${window.pauseMillis}")
    }

    @Test
    fun anAsideDoesNotLengthenTheWait() {
        val effect = StudySessionEffect.StartSpeechRecognition(
            expected = "hola (informal, used with friends and family members)",
            languageTag = "es-ES",
        )
        assertEquals(ListenWindow.forAnswer("hola"), effect.window)
    }
}
