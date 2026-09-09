package com.github.jvsena42.loopky.ui.nav

import android.net.Uri
import com.github.jvsena42.loopky.presentation.profile.FollowSource

// One entry per destination, so the count climbs with the app rather than with any one screen's
// complexity — splitting it would put half the route table somewhere else for no reader's benefit.
@Suppress("TooManyFunctions")
object Routes {
    const val ONBOARDING = "onboarding"

    /**
     * The tabbed app. `guest` is set for a visitor with no account, no key and nothing signed in
     * — where a launch that finds no session lands, before the sign-in screen is ever shown.
     *
     * A nav argument rather than a session lookup because it decides the *shell*: a guest gets
     * Discover alone, since Today, Decks and Profile are all views onto a library that does not
     * exist yet. Every screen below the shell asks the repository instead, so a deck opened from
     * a deeplink gates itself correctly however it was reached.
     */
    const val MAIN = "main?guest={guest}"

    /**
     * Settings. `focus` names a row to open and focus on arrival — the image sheet's "Add key"
     * button uses it so a redirect lands *on* the Unsplash field, not merely on the screen.
     */
    const val SETTINGS = "settings?focus={focus}"

    const val DECK_DETAIL = "deck/{deckId}?author={author}"
    const val DECK_EDITOR = "deck/editor/{deckId}"
    const val EDIT_CARD = "deck/{deckId}/card/{cardId}/edit"

    /**
     * The card editor on a card that does not exist yet. A separate route rather than a flag on
     * [EDIT_CARD]: one fewer path segment, so the two patterns cannot match the same URL.
     */
    const val NEW_CARD = "deck/{deckId}/card/new"

    /** Restore an existing account from a backup — the door for a user with no working Ring. */
    const val RESTORE_START = "restore"
    const val RESTORE_PHRASE = "restore/phrase"
    const val RESTORE_FILE = "restore/file"

    /** A valid key with no account: `{pubky}`, plus who holds it. */
    const val ACCOUNT_UNREGISTERED = "account/unregistered/{pubky}?local={local}"

    /**
     * Getting a homeserver account. Flat siblings rather than a nested graph, matching the import
     * flow: the in-flight token lives in `SignupRepository`, not in nav arguments.
     *
     * `adopt` says the terminal step must register the key already on the device — set by the
     * unregistered-key screen, where the user confirmed that pubky by name — rather than minting one.
     */
    const val SIGNUP_START = "signup?adopt={adopt}"
    const val SIGNUP_LOCAL = "signup/local?adopt={adopt}"
    const val SIGNUP_PHONE = "signup/phone"
    const val SIGNUP_LIGHTNING = "signup/lightning"
    const val SIGNUP_INVITE = "signup/invite"

    const val BACKUP_START = "backup"
    const val BACKUP_PHRASE = "backup/phrase"
    const val BACKUP_QUIZ = "backup/phrase/confirm"
    const val BACKUP_FILE = "backup/file"
    const val BACKUP_RING = "backup/ring"

    const val IMPORT_PASTE = "import/paste"

    /** Bulk file import: summary + one confirm, not the swipe queue (spec §5.4). */
    const val IMPORT_BULK = "import/bulk"
    const val IMPORT_TRIAGE = "import/triage"
    const val IMPORT_TRIAGE_EDIT = "import/triage/edit/{rowIndex}"
    const val IMPORT_PUBLISH = "import/publish"

    /**
     * Study session. `deckId` omitted = study all due cards across owned decks.
     *
     * `preview` samples one deck instead: a fixed handful of its cards, graded and stored
     * nowhere, for a deck the reader has neither published nor followed. `author` is whose
     * homeserver to fetch it from, and only a preview needs it — a real session studies decks
     * that are already in the cache.
     */
    const val STUDY = "study?deckId={deckId}&preview={preview}&author={author}"

    /** Another user's public profile (their decks + follow button). */
    const val FRIEND_PROFILE = "profile/{pubky}"

    /** Every deck on the network carrying one tag — where a tag chip leads. */
    const val TAG_BROWSE = "tag/{tag}"

    /** One box over people and decks, by name or by pubky. Reached from Discover's header. */
    const val SEARCH = "search"

    /** One side of someone's follow graph. `source` is a [FollowSource] name, lowercased. */
    const val FOLLOW_LIST = "follows/{pubky}/{source}"

    /** [Routes.SETTINGS]'s `focus` value for the Unsplash access key row. */
    const val SETTINGS_FOCUS_UNSPLASH = "unsplash"

    fun settings(focus: String? = null) = if (focus != null) "settings?focus=$focus" else "settings"

    fun deckDetail(deckId: String, author: String? = null) =
        if (author != null) "deck/$deckId?author=$author" else "deck/$deckId"

    /**
     * Pubkys are z-base-32 and need no escaping, but the add-friend sheet takes free-typed text —
     * a stray character produced a route no destination matched, i.e. a tap that did nothing.
     */
    fun friendProfile(pubky: String) = "profile/" + Uri.encode(pubky)

    /** Encoded for the same reason [friendProfile] is — the pubky can come from typed text. */
    fun followList(pubky: String, source: FollowSource) =
        "follows/" + Uri.encode(pubky) + "/" + source.name.lowercase()

    /** Labels are sanitized to lowercase with no whitespace, but encode anyway — they are free text. */
    fun tagBrowse(tag: String) = "tag/" + Uri.encode(tag)
    fun deckEditor(deckId: String) = "deck/editor/$deckId"
    fun editCard(deckId: String, cardId: String) = "deck/$deckId/card/$cardId/edit"
    fun newCard(deckId: String) = "deck/$deckId/card/new"
    fun main(guest: Boolean = false) = "main?guest=$guest"

    fun study(deckId: String?) = if (deckId != null) "study?deckId=$deckId" else "study"

    /** Sample [deckId] without keeping it. [author] is whose homeserver holds the manifest. */
    fun studyPreview(deckId: String, author: String?): String =
        "study?deckId=$deckId&preview=true" + (author?.let { "&author=" + Uri.encode(it) } ?: "")
    fun triageEditCard(rowIndex: Int) = "import/triage/edit/$rowIndex"

    /** The human check. [adoptHeldKey] carries through to [signupLocal]. */
    fun signupStart(adoptHeldKey: Boolean = false): String = "signup?adopt=$adoptHeldKey"

    /** [ACCOUNT_UNREGISTERED] for a pubky, saying whether Loopky can register it itself. */
    fun unregisteredKey(pubky: String, loopkyHoldsKey: Boolean): String =
        "account/unregistered/${Uri.encode(pubky)}?local=$loopkyHoldsKey"

    /** Local redemption. [adoptHeldKey] registers the key already on the device rather than minting. */
    fun signupLocal(adoptHeldKey: Boolean = false): String = "signup/local?adopt=$adoptHeldKey"
}
