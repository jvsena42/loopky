package com.github.jvsena42.loopky.presentation.auth

/**
 * Why a signed-out visitor is being asked for an account.
 *
 * Loopky lets anyone browse Discover, open a deck, read a profile and try a deck's cards without
 * signing in — the account is the most expensive thing we ask a new user for, and asking for it
 * before they have seen anything is what loses them. Everything past *looking*, though, writes to
 * a homeserver under a pubky, so it genuinely cannot happen without one.
 *
 * This names the action that ran into that wall, so the prompt can say what signing in would
 * unlock rather than a generic "sign in required". It is carried in `UiState` rather than resolved
 * to a message here: `commonMain` holds no strings, and the two platforms word it themselves.
 *
 * The gate is *always* also enforced on the action itself. This type only decides the wording —
 * hiding a button is a courtesy, not a guard.
 */
enum class SignInReason {
    /** Subscribing to someone else's deck, so its updates and your review state have a home. */
    FollowDeck,

    /** Taking your own editable copy of a deck — N+1 writes under your pubky. */
    CloneDeck,

    /** Following a person, which writes a record into your own follow graph. */
    FollowPerson,

    /** Asking for a deck nobody has made, which is a post on your own pubky.app feed. */
    RequestDeck,
}
