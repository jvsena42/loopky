package com.github.jvsena42.loopky.domain.model

/**
 * What a report says is wrong — pubky.app's report categories, so Loopky's reports land in the
 * same Reports inbox with the same labels its moderators already triage (#577). [wire] is the
 * `issueType` pubky.app's `/api/report` validates against; it refuses anything else.
 */
enum class ReportIssue(val wire: String) {
    PersonalInfo("personal-info"),
    HateSpeech("hate-speech"),
    Harassment("harassment"),
    ChildAbuse("child-abuse"),
    Terrorism("terrorism"),
    Violence("violence"),
    IllegalSales("illegal-sales"),
    SexualContent("sexual-content"),
    Copyright("copyright"),
}

/** What is being reported. A deck report also blocks its author, like a profile report. */
sealed interface ReportSubject {
    val accountPubky: String

    data class Profile(override val accountPubky: String) : ReportSubject

    data class OfDeck(val deck: Deck) : ReportSubject {
        override val accountPubky: String get() = deck.authorPubky
    }
}

/**
 * How a report went past the report itself. [blocked] is false when the report reached the
 * moderators but the block that follows it could not be written — the account is still visible,
 * and the reader has to be told so rather than assume it is gone.
 */
data class ReportOutcome(val blocked: Boolean)

object ReportLimits {
    /**
     * The reader's own words. pubky.app caps the whole reason at 1000 characters, and Loopky puts
     * a line naming the subject in front of them.
     */
    const val DETAILS_MAX_LENGTH = 700
}
