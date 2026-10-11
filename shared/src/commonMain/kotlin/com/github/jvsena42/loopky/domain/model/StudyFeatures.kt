package com.github.jvsena42.loopky.domain.model

/** A deck's four study opt-ins, as one value. */
data class StudyFeatures(
    val listen: Boolean,
    val speak: Boolean,
    val type: Boolean,
    val reverse: Boolean,
) {
    fun with(feature: StudyFeature, enabled: Boolean): StudyFeatures = when (feature) {
        StudyFeature.Listen -> copy(listen = enabled)
        StudyFeature.Speak -> copy(speak = enabled)
        StudyFeature.Typing -> copy(type = enabled)
        StudyFeature.Reverse -> copy(reverse = enabled)
    }
}

/** PascalCase, and `Typing` rather than `Type`: the entries cross to Swift lowercased. */
enum class StudyFeature { Listen, Speak, Typing, Reverse }

/**
 * What a reader changed about how they study a deck they follow. Null means "as the author set
 * it", so an opt-in the reader never touched keeps tracking the author's edits.
 */
data class StudyOverrides(
    val listen: Boolean? = null,
    val speak: Boolean? = null,
    val type: Boolean? = null,
    val reverse: Boolean? = null,
) {
    val isEmpty: Boolean get() = listen == null && speak == null && type == null && reverse == null

    fun over(author: StudyFeatures): StudyFeatures = StudyFeatures(
        listen = listen ?: author.listen,
        speak = speak ?: author.speak,
        type = type ?: author.type,
        reverse = reverse ?: author.reverse,
    )

    companion object {
        /**
         * Only the differences are kept. A choice that matches the author's is not a choice worth
         * storing: it would pin the reader to today's value of a switch they never disagreed with.
         */
        fun between(author: StudyFeatures, chosen: StudyFeatures) = StudyOverrides(
            listen = chosen.listen.takeIf { it != author.listen },
            speak = chosen.speak.takeIf { it != author.speak },
            type = chosen.type.takeIf { it != author.type },
            reverse = chosen.reverse.takeIf { it != author.reverse },
        )
    }
}

val Deck.study: StudyFeatures
    get() = StudyFeatures(listenEnabled, speakEnabled, typeEnabled, reverseEnabled)

/** The author's own opt-ins, whether or not the reader's are laid over them. */
val Deck.authoredStudy: StudyFeatures get() = authorStudy ?: study

/** This deck as its author published it, with any reader overrides taken back off. */
fun Deck.asAuthored(): Deck = withStudy(authoredStudy).copy(authorStudy = null)

/** This deck as a reader holding [overrides] studies it. */
fun Deck.studiedWith(overrides: StudyOverrides): Deck {
    val author = authoredStudy
    val mine = overrides.over(author)
    return withStudy(mine).copy(authorStudy = author.takeIf { it != mine })
}

private fun Deck.withStudy(features: StudyFeatures): Deck = copy(
    listenEnabled = features.listen,
    speakEnabled = features.speak,
    typeEnabled = features.type,
    reverseEnabled = features.reverse,
)
