package com.github.jvsena42.loopky.domain.model

/** Why a profile or deck reached by a link is not shown until the reader asks for it (#577). */
enum class HiddenReason {
    /** pubky.app's moderation bot tagged the account with a violation label. */
    FlaggedByModerators,

    /** Someone the reader follows reported the account. */
    ReportedByFollowee,
}
