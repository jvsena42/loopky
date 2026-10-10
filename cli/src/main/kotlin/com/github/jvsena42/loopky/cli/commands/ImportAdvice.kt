package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.StudyModes
import com.github.jvsena42.loopky.domain.model.frontBackOf

/** The speech and study settings a run will leave on its deck: [deck]'s, with this invocation's flags on top. */
internal class SpeechSettings(
    val listen: Boolean,
    val speak: Boolean,
    val frontLang: String?,
    val backLang: String?,
    val modes: StudyModes,
)

/**
 * Before the file is read. Not with `--resume`: the flags then overlay a deck that may already
 * carry the pair, and that case is checked once the deck is in hand.
 */
internal fun Args.requireSpeechPairForNewDeck() {
    if (has("resume")) return
    requireSpeechPair(flag("listen", default = false), flag("speak", default = false), option("front-lang"), option("back-lang"))
}

internal fun Args.speechSettings(deck: Deck?, draft: ImportDraft): SpeechSettings {
    val speak = flagOrNull("speak") ?: deck?.speakEnabled ?: false
    val type = flagOrNull("type") ?: deck?.typeEnabled ?: false
    return SpeechSettings(
        listen = flagOrNull("listen") ?: deck?.listenEnabled ?: false,
        speak = speak,
        frontLang = option("front-lang") ?: deck?.frontLang,
        backLang = option("back-lang") ?: deck?.backLang,
        modes = StudyModes(
            reverse = flagOrNull("reverse") ?: deck?.reverseEnabled ?: draft.suggestsReverse,
            graded = type || speak,
        ),
    )
}

/**
 * The draft's cards as [cardAdvice] needs them: text, and enough of each picture to tell two
 * apart. Built without uploading anything — a blob has no sha until it is uploaded, so it is
 * keyed on the draft image itself, which `MediaIndex` hands back once per distinct blob.
 */
internal fun ImportRepository.adviceCards(draft: ImportDraft): List<Card> =
    keptRows().map { row ->
        val (front, back) = draft.frontBackOf(row)
        Card(
            id = "",
            deckId = "",
            updatedAt = 0L,
            front = CardSide(text = front.takeIf { it.isNotBlank() }, imageRef = rowImage(row.index, isFront = true)?.adviceRef()),
            back = CardSide(text = back.takeIf { it.isNotBlank() }, imageRef = rowImage(row.index, isFront = false)?.adviceRef()),
        )
    }

private fun DraftCardImage.adviceRef(): MediaRef.Image = MediaRef.Image(
    path = "",
    mime = "",
    sha256 = if (url == null) "draft-${System.identityHashCode(this)}" else "",
    width = null,
    height = null,
    url = url,
)
