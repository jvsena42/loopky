package com.github.jvsena42.loopky.domain.model

/**
 * What an account holds under `/pub/pubky.app/`, the namespace every Pubky app shares, and so
 * whether Delete account may offer to remove it.
 *
 * Only [LoopkyOnly] makes that offer. [Unknown] never does: removing a profile that could not be
 * fully inspected is the one mistake here that cannot be undone.
 */
enum class PubkyAppFootprint {
    /** Nothing under `/pub/pubky.app/`. */
    Empty,

    /**
     * Only records Loopky writes. Follows, blocks and profile labels look the same whichever app
     * wrote them, so this can still be an account that used pubky.app; the user decides.
     */
    LoopkyOnly,

    /** At least one record another app wrote, or another app's namespace under `/pub/`. */
    Direct,

    /** A listing or a read failed before anything proved [Direct]. */
    Unknown,
    ;

    val offersPubkyAppDeletion: Boolean get() = this == LoopkyOnly
}
